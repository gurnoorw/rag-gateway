package dev.raggateway.eval;

import ch.qos.logback.classic.Level;
import com.zaxxer.hikari.HikariDataSource;
import dev.raggateway.core.CharRatioTokenEstimator;
import dev.raggateway.rag.Embedder;
import dev.raggateway.rag.RagProperties;
import dev.raggateway.rag.RetrievalMode;
import dev.raggateway.rag.ingest.FakeEmbedder;
import dev.raggateway.rag.ingest.OllamaEmbedder;
import dev.raggateway.rag.query.AclAudit;
import dev.raggateway.rag.query.ChunkSearchRepository;
import dev.raggateway.rag.query.HybridRetriever;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

import javax.sql.DataSource;

import java.io.IOException;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The eval command line (t13, DECISIONS.md D-53):
 * <pre>
 * ./gradlew eval --args="retrieval --modes keyword --measured-on 'cloud container, 4 vCPU / 15 GB'"
 * ./gradlew eval --args="retrieval --modes keyword,vector,hybrid --embedder nomic-embed-text --measured-on '…'"
 * ./gradlew eval --args="retrieval --modes keyword,vector,hybrid --embedder fake --out build/eval/smoke.json --measured-on '…'"
 * </pre>
 * {@code retrieval} indexes the loaded {@code k8s-docs} collection into tenant {@code eval} at each chunk size
 * ({@link EvalIndexer}), scores every mode asked at each size ({@link RetrievalEvalRunner}), runs the ACL eval at
 * 400 ({@link AclEvalRunner}) and writes one results file ({@link EvalResultsWriter}).
 *
 * <p>Options: {@code --modes} (default {@code keyword}), {@code --sizes} (default {@code 200,400,800}),
 * {@code --embedder none|fake|nomic-embed-text} (default {@code none}: keyword rows only), {@code --measured-on}
 * (required, no default, C14), {@code --out} (default {@code eval/results/<run_id>.json}; a fake run must write under
 * {@code build/eval/}), {@code --acl-mode keyword|vector|hybrid|off} (default hybrid when asked for, else keyword),
 * {@code --questions} (default {@code eval/questions.v1.jsonl}), {@code --source-tenant} (default {@code acme}, the
 * seeded demo tenant), {@code --source-collection} ({@code k8s-docs}), {@code --tenant} ({@code eval}),
 * {@code --jdbc-url} (default {@code DB_URL} or {@code jdbc:postgresql://localhost:5432/raggateway}; user and
 * password from {@code POSTGRES_USER}/{@code POSTGRES_PASSWORD}, defaults as in application.yml),
 * {@code --ollama-url} (default {@code GATEWAY_OLLAMA_BASE_URL} or {@code http://localhost:11434}) and
 * {@code --allow-dirty} (runs uncommitted code and writes under {@code build/eval/}).
 *
 * <p>A committed results file names the commit that produced it, so a run into {@code eval/results/} needs a
 * clean tracked tree, as {@code bench/run-all.sh} does.
 */
public final class EvalMain {

    static final Path QUESTIONS = Path.of("eval/questions.v1.jsonl");
    static final Path MANIFEST = Path.of("data/corpus-manifest.tsv");
    static final Set<String> FLAGS = Set.of("--allow-dirty");

    private EvalMain() {
    }

    public static void main(String[] args) throws Exception {
        quietLogs();
        try {
            System.exit(run(args, Path.of("").toAbsolutePath()));
        } catch (IllegalArgumentException e) {
            System.err.println("error: " + e.getMessage());
            System.exit(2);
        }
    }

    static int run(String[] args, Path repo) throws IOException {
        Options o = parse(args);
        try (HikariDataSource ds = dataSource(o.jdbcUrl)) {
            return run(o, repo, ds);
        }
    }

    /** As {@link #run(String[], Path)} on a given database (tests); {@code --jdbc-url} is ignored. */
    static int run(String[] args, Path repo, DataSource ds) throws IOException {
        return run(parse(args), repo, ds);
    }

    private static Options parse(String[] args) {
        if (args.length == 0 || !args[0].equals("retrieval")) {
            throw new IllegalArgumentException("usage: retrieval --measured-on '<hardware label>' [--modes keyword]"
                    + " [--embedder none|fake|nomic-embed-text] [--sizes 200,400,800] [--out <path>]; see EvalMain");
        }
        return Options.parse(Arrays.copyOfRange(args, 1, args.length));
    }

    private static int run(Options o, Path repo, DataSource ds) throws IOException {
        ZonedDateTime now = ZonedDateTime.now(ZoneOffset.UTC);
        String runId = EvalResultsWriter.runId(now, o.measuredOn, o.embedder);
        Path out = o.out(runId, repo);
        String commit = gitCommit(repo);

        EvalSet set = EvalSet.load(repo.resolve(o.questions), repo.resolve(EvalSet.SCHEMA));
        String corpusCommit = manifestCommit(repo.resolve(MANIFEST));
        System.out.printf("run %s (commit %s, embedder %s), eval set %s: %d questions%n", runId, commit,
                o.embedder.kind, set.name(), set.questions().size());

        JdbcTemplate template = new JdbcTemplate(ds);
        JdbcClient jdbc = JdbcClient.create(template);
        var tx = new DataSourceTransactionManager(ds);
        RagProperties rag = RagProperties.defaults();
        Embedder indexEmbedder = o.embedder == EvalResultsWriter.Embedder.NOMIC ? ollama(o.ollamaUrl)
                : new FakeEmbedder(rag.embedDimensions());
        Embedder queryEmbedder = o.embedder == EvalResultsWriter.Embedder.NONE ? new NoQueryEmbedder()
                : indexEmbedder;
        String embedderId = o.embedder == EvalResultsWriter.Embedder.NOMIC ? EvalIndexer.NOMIC : EvalIndexer.FAKE;
        Set<String> accepted = o.embedder == EvalResultsWriter.Embedder.NONE
                ? Set.of(EvalIndexer.FAKE, EvalIndexer.NOMIC) : Set.of(embedderId);

        var indexer = new EvalIndexer(template, tx, indexEmbedder, embedderId, accepted,
                new CharRatioTokenEstimator());
        List<EvalIndexer.SourceDocument> source = indexer.readSource(o.sourceTenant, o.sourceCollection);
        Set<String> allGroups = EvalIndexer.allGroups(source);

        try (var retriever = new HybridRetriever(new ChunkSearchRepository(jdbc, tx, rag), queryEmbedder, rag,
                new SimpleMeterRegistry())) {
            var retrievalRunner = new RetrievalEvalRunner(retriever, jdbc);
            List<RetrievalEvalRunner.Result> rows = new ArrayList<>();
            Set<EvalIndexer.ChunkSize> sizes = new LinkedHashSet<>(o.sizes);
            if (o.aclMode != null) sizes.add(EvalIndexer.ChunkSize.C400);
            for (EvalIndexer.ChunkSize size : sizes) {
                long t0 = System.nanoTime();
                EvalIndexer.Report r = indexer.index(source, o.tenant, size);
                System.out.printf("index %s: %d documents (%d indexed, %d unchanged, %d deleted), %d chunks, %.1f s%n",
                        size.collection(), r.documents(), r.indexed(), r.unchanged(), r.deleted(), r.chunks(),
                        (System.nanoTime() - t0) / 1e9);
                if (!o.sizes.contains(size)) continue;
                for (RetrievalMode mode : o.modes) {
                    RetrievalEvalRunner.Result res = retrievalRunner.run(set, o.tenant, size, mode, allGroups);
                    rows.add(res);
                    print(res);
                }
            }
            AclEvalRunner.Result acl = null;
            if (o.aclMode != null) {
                acl = new AclEvalRunner(retriever, new AclAudit(jdbc)).run(set, o.tenant,
                        EvalIndexer.ChunkSize.C400, o.aclMode);
                System.out.printf("acl %s @400: %d questions, %d leaked chunks %s, unauthorised no-answer %.3f,"
                                + " authorised recall@5 %.3f%n", label(acl.mode()), acl.questions(),
                        acl.unauthorisedLeakedChunks(), acl.leakedChunkIds(), acl.unauthorisedNoAnswerFraction(),
                        acl.authorisedRecallAt5());
            }
            var info = new EvalResultsWriter.RunInfo(runId, o.measuredOn, now.toLocalDate().toString(), commit,
                    corpusCommit, source.size(), set.name(), set.questions().size(), o.embedder);
            Path written = EvalResultsWriter.write(EvalResultsWriter.build(info, rows, acl), out, repo,
                    EvalResultsWriter.SCHEMA);
            System.out.println("wrote " + repo.relativize(written)
                    + (o.embedder == EvalResultsWriter.Embedder.FAKE ? "  (" + EvalResultsWriter.FAKE_STAMP + ")" : ""));
            return acl != null && acl.unauthorisedLeakedChunks() > 0 ? 1 : 0;
        } finally {
            if (indexEmbedder instanceof AutoCloseable c) {
                try {
                    c.close();
                } catch (Exception ignored) {
                    // closing an HTTP client at exit
                }
            }
        }
    }

    private static void print(RetrievalEvalRunner.Result r) {
        var s = r.summary();
        System.out.printf("%-7s @%d: %d questions, recall@1 %.3f, recall@5 %.3f, recall@10 %.3f, MRR %.3f, nDCG@10 %.3f%n",
                label(r.mode()), r.size().targetTokens, s.questions(), s.recallAt1(), s.recallAt5(), s.recallAt10(),
                s.mrr(), s.ndcgAt10());
        StringBuilder line = new StringBuilder("        first relevant rank (relevant chunks):");
        for (var q : r.questions()) {
            line.append(' ').append(q.questionId()).append('=')
                    .append(q.firstRelevantRank() == 0 ? "-" : q.firstRelevantRank())
                    .append('(').append(q.totalRelevant()).append(')');
        }
        System.out.println(line);
    }

    private static String label(RetrievalMode mode) {
        return mode.name().toLowerCase(Locale.ROOT);
    }

    /** The parsed command line. */
    record Options(List<RetrievalMode> modes, List<EvalIndexer.ChunkSize> sizes, EvalResultsWriter.Embedder embedder,
                   String measuredOn, String outArg, RetrievalMode aclMode, Path questions, String sourceTenant,
                   String sourceCollection, String tenant, String jdbcUrl, String ollamaUrl, boolean allowDirty) {

        static Options parse(String[] args) {
            Map<String, String> v = new HashMap<>();
            for (int i = 0; i < args.length; i++) {
                String a = args[i];
                if (FLAGS.contains(a)) {
                    v.put(a, "true");
                } else if (a.startsWith("--") && i + 1 < args.length) {
                    v.put(a, args[++i]);
                } else {
                    throw new IllegalArgumentException("unexpected argument: " + a);
                }
            }
            Set<String> known = Set.of("--modes", "--sizes", "--embedder", "--measured-on", "--out", "--acl-mode",
                    "--questions", "--source-tenant", "--source-collection", "--tenant", "--jdbc-url", "--ollama-url",
                    "--allow-dirty");
            for (String k : v.keySet()) {
                if (!known.contains(k)) throw new IllegalArgumentException("unknown option " + k);
            }
            String measuredOn = v.get("--measured-on");
            if (measuredOn == null || measuredOn.isBlank()) {
                throw new IllegalArgumentException("--measured-on is required (the hardware label, C14)");
            }
            List<RetrievalMode> modes = Arrays.stream(v.getOrDefault("--modes", "keyword").split(","))
                    .map(String::trim).map(m -> RetrievalMode.valueOf(m.toUpperCase(Locale.ROOT))).distinct().toList();
            List<EvalIndexer.ChunkSize> sizes = Arrays.stream(v.getOrDefault("--sizes", "200,400,800").split(","))
                    .map(String::trim).map(s -> EvalIndexer.ChunkSize.ofTarget(Integer.parseInt(s))).distinct().toList();
            var embedder = EvalResultsWriter.Embedder.of(v.getOrDefault("--embedder", "none"));
            if (embedder == EvalResultsWriter.Embedder.NONE && modes.stream().anyMatch(m -> m != RetrievalMode.KEYWORD)) {
                throw new IllegalArgumentException("--embedder none runs keyword rows only; vector and hybrid need"
                        + " --embedder nomic-embed-text (or fake, for a smoke run under build/eval/)");
            }
            String aclArg = v.getOrDefault("--acl-mode",
                    modes.contains(RetrievalMode.HYBRID) ? "hybrid" : "keyword");
            RetrievalMode aclMode = aclArg.equals("off") ? null : RetrievalMode.valueOf(aclArg.toUpperCase(Locale.ROOT));
            if (embedder == EvalResultsWriter.Embedder.NONE && aclMode != null && aclMode != RetrievalMode.KEYWORD) {
                throw new IllegalArgumentException("--embedder none runs the ACL eval in keyword mode only");
            }
            String outArg = v.get("--out");
            if (embedder == EvalResultsWriter.Embedder.FAKE && outArg == null) {
                throw new IllegalArgumentException("--embedder fake needs --out build/eval/<file>.json ("
                        + EvalResultsWriter.FAKE_STAMP + "; never under eval/results/)");
            }
            return new Options(modes, sizes, embedder, measuredOn, outArg, aclMode,
                    Path.of(v.getOrDefault("--questions", QUESTIONS.toString())),
                    v.getOrDefault("--source-tenant", "acme"), v.getOrDefault("--source-collection", "k8s-docs"),
                    v.getOrDefault("--tenant", "eval"),
                    v.getOrDefault("--jdbc-url", env("DB_URL", "jdbc:postgresql://localhost:5432/raggateway")),
                    v.getOrDefault("--ollama-url", env("GATEWAY_OLLAMA_BASE_URL", "http://localhost:11434")),
                    v.containsKey("--allow-dirty"));
        }

        /** Where the file goes; checked before any work, so a refused run costs nothing. */
        Path out(String runId, Path repo) {
            Path smoke = repo.resolve(EvalResultsWriter.SMOKE_DIR);
            Path target;
            if (outArg != null) {
                target = repo.resolve(outArg).toAbsolutePath().normalize();
            } else if (allowDirty) {
                target = smoke.resolve(runId + ".json");
            } else {
                target = repo.resolve(EvalResultsWriter.RESULTS_DIR).resolve(runId + ".json");
            }
            if (allowDirty && !target.startsWith(smoke)) {
                throw new IllegalArgumentException("--allow-dirty writes only under build/eval/, not " + target);
            }
            if (embedder == EvalResultsWriter.Embedder.FAKE && !target.startsWith(smoke)) {
                throw new IllegalArgumentException("a fake-embedder run writes only under build/eval/ ("
                        + EvalResultsWriter.FAKE_STAMP + "), not " + target);
            }
            if (!target.startsWith(smoke) && dirty(repo)) {
                throw new IllegalArgumentException("uncommitted changes: commit first (a results file names the commit"
                        + " that produced it), or pass --allow-dirty to write under build/eval/");
            }
            return target;
        }
    }

    private static HikariDataSource dataSource(String url) {
        var ds = new HikariDataSource();
        ds.setJdbcUrl(url);
        ds.setUsername(env("POSTGRES_USER", "raggateway"));
        ds.setPassword(env("POSTGRES_PASSWORD", "raggateway"));   // the local compose default, as application.yml
        ds.setMaximumPoolSize(4);
        ds.setPoolName("eval");
        return ds;
    }

    private static Embedder ollama(String baseUrl) {
        return new OllamaEmbedder(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build(), baseUrl,
                EvalIndexer.NOMIC, 768, Duration.ofSeconds(60));
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    static String manifestCommit(Path manifest) throws IOException {
        for (String line : Files.readAllLines(manifest)) {
            if (line.startsWith("# commit:")) return line.substring("# commit:".length()).trim();
        }
        throw new IllegalStateException(manifest + " has no '# commit:' line");
    }

    private static String gitCommit(Path repo) {
        return git(repo, "rev-parse", "--short=12", "HEAD").trim();
    }

    private static boolean dirty(Path repo) {
        return !git(repo, "status", "--porcelain", "--untracked-files=no").isBlank();
    }

    private static String git(Path repo, String... args) {
        List<String> command = new ArrayList<>(List.of("git", "-C", repo.toString()));
        command.addAll(List.of(args));
        try {
            Process p = new ProcessBuilder(command).redirectErrorStream(true).start();
            String output = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (p.waitFor() != 0) throw new IllegalArgumentException("git " + String.join(" ", args) + " failed: " + output);
            return output;
        } catch (IOException e) {
            throw new IllegalArgumentException("git is required to name the commit of a run: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    /** Keyword-only runs: the retriever must never embed a question. */
    static final class NoQueryEmbedder implements Embedder {
        @Override
        public int dimensions() {
            return 768;
        }

        @Override
        public List<float[]> embedDocuments(List<String> texts) {
            throw new IllegalStateException("a keyword-only run embeds no text");
        }

        @Override
        public float[] embedQuery(String text) {
            throw new IllegalStateException("a keyword-only run compares no vectors");
        }
    }

    /** The eval prints its own lines; the libraries' INFO and DEBUG logs (Hikari, per-retrieval lines) are noise. */
    private static void quietLogs() {
        if (LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME) instanceof ch.qos.logback.classic.Logger root) {
            root.setLevel(Level.WARN);
        }
    }
}
