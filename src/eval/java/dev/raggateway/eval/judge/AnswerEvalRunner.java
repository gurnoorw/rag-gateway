package dev.raggateway.eval.judge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.raggateway.rag.query.RagAnswer;
import dev.raggateway.validation.NetworkntOutputValidator;

import java.io.IOException;
import java.io.PrintStream;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Array;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Command line for the answer eval (t13, D-53). Two steps, so scoring (which can cost money) never has to
 * regenerate answers, and a dry run needs nothing but a file:
 *
 * <pre>
 * answers collect --questions eval/questions.v1.jsonl --out build/eval/answers.jsonl
 *                 [--gateway http://localhost:8080] [--collection k8s-c400] [--top-k 5] [--mode hybrid]
 *                 [--tenant eval] [--jdbc-url jdbc:postgresql://localhost:5432/raggateway]
 * answers score   --answers build/eval/answers.jsonl (--dry-run | --confirm-spend)
 *                 [--cap-usd 0.20] [--mode hybrid] [--chunk-target-tokens 400] [--calibration labels.jsonl]
 *                 [--into eval/results/&lt;run_id&gt;.json | --out build/eval/answers-section.json]
 *                 [--settings eval/judge/judge.properties] [--prompt eval/judge/prompt.txt]
 * </pre>
 *
 * <ul>
 *   <li><b>collect</b> asks {@code POST /v1/rag/query} each {@code conceptual}, {@code exact_term} and
 *       {@code unanswerable} question ({@code acl} questions belong to the ACL eval) with the key in
 *       {@code EVAL_GATEWAY_API_KEY}, then reads the text of every source chunk from Postgres (the response carries
 *       ids, not text; the judge needs the text for {@code grounded}). One JSON line per question. No judge.</li>
 *   <li><b>score --dry-run</b> builds every judge prompt and prints the estimated tokens and worst-case cost. It
 *       never builds a client, so it can make no call.</li>
 *   <li><b>score --confirm-spend</b> builds the client (key from {@code EVAL_JUDGE_API_KEY} only), scores under the
 *       cap and writes the {@code answers} section: into a results file with {@code --into} (the whole file is then
 *       validated against {@code eval/results.schema.json} before it is written), otherwise to {@code --out}
 *       (validated against the schema's {@code answers} part). Without either flag the command refuses; the client
 *       is never built.</li>
 * </ul>
 * {@code EvalMain} dispatches {@code answers ...} here; {@link #main} also runs it alone.
 */
public final class AnswerEvalRunner {

    /** The gateway key the collect step uses (an eval-tenant key; never a provider key). */
    public static final String GATEWAY_KEY_ENV = "EVAL_GATEWAY_API_KEY";
    static final Path RESULTS_SCHEMA = Path.of("eval/results.schema.json");
    static final Path RESULTS_DIR = Path.of("eval/results");
    private static final Set<String> MODES = Set.of("keyword", "vector", "hybrid");

    /** Builds the judge client; {@link JudgeClient#create} in production, a counting factory in tests. */
    @FunctionalInterface
    public interface ClientFactory {
        JudgeClient create(JudgeSettings settings, String baseUrl, Function<String, String> env);
    }

    /** Chunk id to chunk text. */
    @FunctionalInterface
    public interface ChunkTexts {
        Map<String, String> texts(List<String> chunkIds);
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    private AnswerEvalRunner() {
    }

    public static void main(String[] args) {
        int code = run(args, System.getenv(), System.out, JudgeClient::create);
        if (code != 0) System.exit(code);
    }

    /** Runs one command; returns the exit code (0 ok, 1 failed, 2 usage error). */
    public static int run(String[] args, Map<String, String> env, PrintStream out, ClientFactory factory) {
        try {
            List<String> list = new ArrayList<>(List.of(args));
            if (!list.isEmpty() && "answers".equals(list.get(0))) list.remove(0);
            if (list.isEmpty()) throw new UsageException("expected 'collect' or 'score'");
            String command = list.remove(0);
            Options o = Options.parse(list);
            return switch (command) {
                case "collect" -> collect(o, env, out, null);
                case "score" -> score(o, env, out, factory);
                default -> throw new UsageException("unknown command '" + command + "'");
            };
        } catch (UsageException e) {
            out.println("usage: " + e.getMessage());
            return 2;
        } catch (IOException | RuntimeException e) {
            out.println("failed: " + e.getMessage());
            return 1;
        }
    }

    // ---------------------------------------------------------------- collect

    /** The collect step. {@code texts} null means read chunk text from Postgres. */
    static int collect(Options o, Map<String, String> env, PrintStream out, ChunkTexts texts) throws IOException {
        Path questions = Path.of(o.required("questions"));
        Path outFile = Path.of(o.required("out"));
        String gateway = o.get("gateway", "http://localhost:8080");
        String collection = o.get("collection", "k8s-c400");
        int topK = Integer.parseInt(o.get("top-k", "5"));
        String mode = mode(o);
        String key = env.get(GATEWAY_KEY_ENV);
        if (key == null || key.isBlank()) throw new UsageException(GATEWAY_KEY_ENV + " is not set");
        if (texts == null) {
            texts = new JdbcChunkTexts(o.get("jdbc-url", env.getOrDefault("DB_URL",
                    "jdbc:postgresql://localhost:5432/raggateway")),
                    env.getOrDefault("POSTGRES_USER", "raggateway"), env.getOrDefault("POSTGRES_PASSWORD", "raggateway"),
                    o.get("tenant", "eval"), collection);
        }

        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        List<String> lines = new ArrayList<>();
        int asked = 0;
        for (String line : Files.readAllLines(questions, StandardCharsets.UTF_8)) {
            if (line.isBlank()) continue;
            JsonNode q = JSON.readTree(line);
            String kind = q.path("kind").asText();
            if ("acl".equals(kind)) continue;
            ObjectNode body = JSON.createObjectNode();
            body.put("collection", collection);
            body.put("question", q.path("question").asText());
            body.put("top_k", topK);
            body.put("mode", mode);
            HttpRequest request = HttpRequest.newBuilder(URI.create(gateway + "/v1/rag/query"))
                    .timeout(Duration.ofSeconds(120))
                    .header("Authorization", "Bearer " + key)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)))
                    .build();
            HttpResponse<String> response;
            try {
                response = http.send(request, HttpResponse.BodyHandlers.ofString());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted", e);
            }
            if (response.statusCode() != 200) {
                throw new IOException(q.path("id").asText() + ": gateway returned HTTP " + response.statusCode());
            }
            JsonNode answerJson = JSON.readTree(response.body());
            RagAnswer answer = JSON.treeToValue(answerJson, RagAnswer.class);
            List<String> chunkIds = answer.sources().stream().map(RagAnswer.Source::chunkId).toList();
            Map<String, String> byChunk = chunkIds.isEmpty() ? Map.of() : texts.texts(chunkIds);
            ObjectNode sourceTexts = JSON.createObjectNode();
            for (RagAnswer.Source s : answer.sources()) {
                String text = byChunk.get(s.chunkId());
                if (text != null) sourceTexts.put(s.source(), text);
            }
            ObjectNode item = JSON.createObjectNode();
            item.put("id", q.path("id").asText());
            item.put("kind", kind);
            item.put("question", q.path("question").asText());
            item.set("gold", q.path("gold"));
            item.set("response", answerJson);
            item.set("source_texts", sourceTexts);
            lines.add(JSON.writeValueAsString(item));
            asked++;
        }
        if (outFile.getParent() != null) Files.createDirectories(outFile.getParent());
        Files.write(outFile, lines, StandardCharsets.UTF_8);
        out.println("collected " + asked + " answers into " + outFile);
        return 0;
    }

    /** Reads chunk text from the {@code chunks} table, scoped to the eval tenant and collection. */
    static final class JdbcChunkTexts implements ChunkTexts {
        private final String url;
        private final String user;
        private final String password;
        private final String tenant;
        private final String collection;

        JdbcChunkTexts(String url, String user, String password, String tenant, String collection) {
            this.url = url;
            this.user = user;
            this.password = password;
            this.tenant = tenant;
            this.collection = collection;
        }

        @Override
        public Map<String, String> texts(List<String> chunkIds) {
            String sql = "SELECT chunk_id::text, text FROM chunks"
                    + " WHERE chunk_id::text = ANY(?) AND tenant_id = ? AND collection_id = ?";
            try (Connection c = DriverManager.getConnection(url, user, password);
                 PreparedStatement ps = c.prepareStatement(sql)) {
                Array ids = c.createArrayOf("text", chunkIds.toArray());
                ps.setArray(1, ids);
                ps.setString(2, tenant);
                ps.setString(3, collection);
                Map<String, String> texts = new HashMap<>();
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) texts.put(rs.getString(1), rs.getString(2));
                }
                return texts;
            } catch (SQLException e) {
                throw new IllegalStateException("reading chunk text failed: " + e.getSQLState(), e);
            }
        }
    }

    // ---------------------------------------------------------------- score

    static int score(Options o, Map<String, String> env, PrintStream out, ClientFactory factory) throws IOException {
        boolean dryRun = o.flag("dry-run");
        boolean confirm = o.flag("confirm-spend");
        if (dryRun == confirm) {
            throw new UsageException("give exactly one of --dry-run (estimate, no client) or --confirm-spend (real"
                    + " judge calls under the cap)");
        }
        JudgeSettings settings = JudgeSettings.load(Path.of(o.get("settings", JudgeSettings.DEFAULT_FILE.toString())));
        if (o.has("cap-usd")) settings = settings.withCap(new BigDecimal(o.required("cap-usd")));
        AnswerScorer.Template template = AnswerScorer.Template.load(
                Path.of(o.get("prompt", AnswerScorer.Template.DEFAULT_FILE.toString())));
        AnswerScorer scorer = new AnswerScorer(settings, template);
        List<AnswerScorer.Item> items = readItems(Path.of(o.required("answers")));
        String mode = mode(o);
        int chunkTarget = Integer.parseInt(o.get("chunk-target-tokens", "400"));

        if (dryRun) {
            AnswerScorer.Estimate e = scorer.estimate(items);
            out.println("dry run (no client, no network): " + items.size() + " answers, " + e.prompts()
                    + " judge prompts for model " + settings.model());
            out.println("estimated input tokens " + e.inputTokens() + " (3 characters per token), worst-case output"
                    + " tokens " + e.worstCaseOutputTokens() + " (max-tokens " + settings.maxTokens() + " per call)");
            out.println("estimated worst-case cost $" + e.worstCaseUsd().toPlainString() + " at $"
                    + settings.inputUsdPerMtok().toPlainString() + " / $" + settings.outputUsdPerMtok().toPlainString()
                    + " per million input / output tokens; cap $" + settings.capUsd().toPlainString());
            out.println(e.fitUnderCap() == e.prompts()
                    ? "all " + e.prompts() + " calls fit under the cap in the worst case"
                    : "only " + e.fitUnderCap() + " of " + e.prompts() + " calls fit under the cap in the worst case");
            return 0;
        }

        Path into = o.has("into") ? Path.of(o.required("into")) : null;
        Path outFile = Path.of(o.get("out", "build/eval/answers-section.json"));
        if (into == null && outFile.toAbsolutePath().normalize().startsWith(RESULTS_DIR.toAbsolutePath().normalize())) {
            throw new UsageException("a lone answers section is not a results file; use --into eval/results/<run_id>.json");
        }
        ObjectNode results = null;
        if (into != null) {
            results = (ObjectNode) JSON.readTree(into.toFile());
            if (results.has("answers")) throw new UsageException(into + " already has an answers section");
        }
        Map<String, Integer> labels = o.has("calibration") ? readLabels(Path.of(o.required("calibration"))) : Map.of();

        AnswerScorer.Report report;
        try (JudgeClient client = factory.create(settings, o.get("judge-base-url", JudgeClient.DEFAULT_BASE_URL),
                env::get)) {
            report = scorer.score(items, client::judge, new SpendCap(settings), mode, chunkTarget, labels);
        }
        report.lines().forEach(out::println);
        out.println(report.summary());
        if (report.answers() == null) return 1;

        NetworkntOutputValidator validator = new NetworkntOutputValidator(JSON);
        String schema = Files.readString(RESULTS_SCHEMA, StandardCharsets.UTF_8);
        ObjectMapper pretty = JSON.copy().enable(SerializationFeature.INDENT_OUTPUT);
        if (into != null) {
            results.set("answers", report.answers());
            String doc = JSON.writeValueAsString(results);
            List<String> errors = validator.validate(schema, doc);
            if (!errors.isEmpty()) throw new IllegalStateException("results file would not match its schema: " + errors);
            Files.writeString(into, pretty.writeValueAsString(results) + "\n", StandardCharsets.UTF_8);
            out.println("wrote the answers section into " + into);
        } else {
            List<String> errors = validator.validate(answersSchema(schema), JSON.writeValueAsString(report.answers()));
            if (!errors.isEmpty()) throw new IllegalStateException("answers section would not match its schema: " + errors);
            if (outFile.getParent() != null) Files.createDirectories(outFile.getParent());
            Files.writeString(outFile, pretty.writeValueAsString(report.answers()) + "\n", StandardCharsets.UTF_8);
            out.println("wrote the answers section to " + outFile);
        }
        return 0;
    }

    /** The {@code answers} part of the results schema as a schema of its own (with the shared {@code $defs}). */
    static String answersSchema(String resultsSchema) throws IOException {
        JsonNode root = JSON.readTree(resultsSchema);
        ObjectNode sub = ((ObjectNode) root.path("properties").path("answers")).deepCopy();
        sub.set("$schema", root.path("$schema"));
        sub.set("$defs", root.path("$defs"));
        return JSON.writeValueAsString(sub);
    }

    /** Reads a file written by {@code collect}. */
    public static List<AnswerScorer.Item> readItems(Path file) throws IOException {
        List<AnswerScorer.Item> items = new ArrayList<>();
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            if (line.isBlank()) continue;
            JsonNode n = JSON.readTree(line);
            List<AnswerScorer.Gold> gold = new ArrayList<>();
            for (JsonNode g : n.path("gold")) {
                gold.add(new AnswerScorer.Gold(g.path("doc_id").asText(), g.path("evidence").asText()));
            }
            Map<String, String> texts = new LinkedHashMap<>();
            n.path("source_texts").properties().forEach(e -> texts.put(e.getKey(), e.getValue().asText()));
            items.add(new AnswerScorer.Item(n.path("id").asText(), n.path("kind").asText(),
                    n.path("question").asText(), gold, JSON.treeToValue(n.path("response"), RagAnswer.class), texts));
        }
        return items;
    }

    /** Owner's hand labels: one {@code {"id": "q01", "correctness": 0|1|2}} per line. */
    static Map<String, Integer> readLabels(Path file) throws IOException {
        Map<String, Integer> labels = new HashMap<>();
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            if (line.isBlank()) continue;
            JsonNode n = JSON.readTree(line);
            int c = n.path("correctness").asInt(-1);
            if (c < 0 || c > 2) throw new IllegalArgumentException("calibration label for " + n.path("id") + " must be 0, 1 or 2");
            labels.put(n.path("id").asText(), c);
        }
        return labels;
    }

    private static String mode(Options o) {
        String mode = o.get("mode", "hybrid");
        if (!MODES.contains(mode)) throw new UsageException("--mode must be one of " + MODES);
        return mode;
    }

    // ---------------------------------------------------------------- options

    static final class UsageException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        UsageException(String message) {
            super(message);
        }
    }

    /** {@code --name value} and bare {@code --flag} options. */
    static final class Options {
        private static final Set<String> FLAGS = Set.of("dry-run", "confirm-spend");
        private final Map<String, String> values = new HashMap<>();

        static Options parse(List<String> args) {
            Options o = new Options();
            for (int i = 0; i < args.size(); i++) {
                String a = args.get(i);
                if (!a.startsWith("--")) throw new UsageException("unexpected argument '" + a + "'");
                String name = a.substring(2);
                if (FLAGS.contains(name)) {
                    o.values.put(name, "true");
                } else {
                    if (i + 1 >= args.size()) throw new UsageException("--" + name + " needs a value");
                    o.values.put(name, args.get(++i));
                }
            }
            return o;
        }

        boolean flag(String name) {
            return values.containsKey(name);
        }

        boolean has(String name) {
            return values.containsKey(name);
        }

        String get(String name, String fallback) {
            return values.getOrDefault(name, fallback);
        }

        String required(String name) {
            String v = values.get(name);
            if (v == null) throw new UsageException("--" + name + " is required");
            return v;
        }
    }
}
