package dev.raggateway.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.raggateway.validation.NetworkntOutputValidator;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Writes one eval run (t13, DECISIONS.md D-53).
 * <ul>
 *   <li><b>Committed runs</b> ({@code embedder.kind} {@code none} or {@code nomic-embed-text}) are checked against
 *       {@code eval/results.schema.json} before anything is written; a file that does not match is never
 *       written. The README's eval table is read from these files ({@code scripts/check_readme_numbers.py}).</li>
 *   <li><b>Fake-embedder runs</b> are smoke runs. They are written only under {@code build/eval/} (anywhere else is
 *       refused, {@code eval/results/} above all, AC-t13-4), and the embedder and every row carry the note
 *       {@value #FAKE_STAMP}. The schema rejects {@code kind: fake} by design, so these files are not
 *       schema-checked: they are the same shape plus the stamp.</li>
 * </ul>
 */
public final class EvalResultsWriter {

    public static final String FAKE_STAMP = "fake embedder, not a model";
    public static final Path SCHEMA = Path.of("eval/results.schema.json");
    public static final Path RESULTS_DIR = Path.of("eval/results");
    public static final Path SMOKE_DIR = Path.of("build/eval");
    public static final String CORPUS_REPO = "kubernetes/website";

    private static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    private static final DateTimeFormatter RUN_TS = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmm'Z'");

    /** The embedder a run used. */
    public enum Embedder {
        /** Keyword-only: no vectors were compared. */
        NONE("none"),
        /** Feature hashing, not a model: smoke runs only, never committed (D-18). */
        FAKE("fake"),
        NOMIC("nomic-embed-text");

        public final String kind;

        Embedder(String kind) {
            this.kind = kind;
        }

        public static Embedder of(String kind) {
            for (Embedder e : values()) if (e.kind.equals(kind)) return e;
            throw new IllegalArgumentException("--embedder must be none, fake or nomic-embed-text, got " + kind);
        }
    }

    /**
     * What the run was, besides its numbers.
     *
     * @param documents source documents indexed
     * @param questions questions in the eval set (all kinds)
     */
    public record RunInfo(String runId, String measuredOn, String date, String gitCommit, String corpusCommit,
                          int documents, String evalSet, int questions, Embedder embedder) {
        public RunInfo {
            Objects.requireNonNull(runId, "runId");
            if (measuredOn == null || measuredOn.isBlank()) throw new IllegalArgumentException("--measured-on is required");
            Objects.requireNonNull(embedder, "embedder");
        }
    }

    private EvalResultsWriter() {
    }

    /** {@code <yyyyMMdd'T'HHmm'Z'>-<measured-on as a slug>}, plus {@code -fake} for a smoke run. */
    public static String runId(ZonedDateTime at, String measuredOn, Embedder embedder) {
        String slug = measuredOn.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
        if (slug.isEmpty()) slug = "run";
        return at.withZoneSameInstant(ZoneOffset.UTC).format(RUN_TS) + "-" + slug
                + (embedder == Embedder.FAKE ? "-fake" : "");
    }

    /** The results document. {@code acl} may be null (not run: the section is absent, never zero). */
    public static ObjectNode build(RunInfo info, List<RetrievalEvalRunner.Result> retrieval, AclEvalRunner.Result acl) {
        if (retrieval.isEmpty()) throw new IllegalArgumentException("a run has at least one retrieval row");
        boolean fake = info.embedder() == Embedder.FAKE;
        ObjectNode doc = JSON.createObjectNode();
        doc.put("run_id", info.runId());
        doc.put("measured_on", info.measuredOn());
        doc.put("date", info.date());
        doc.put("git_commit", info.gitCommit());
        ObjectNode corpus = doc.putObject("corpus");
        corpus.put("repo", CORPUS_REPO);
        corpus.put("commit", info.corpusCommit());
        corpus.put("documents", info.documents());
        corpus.put("eval_set", info.evalSet());
        corpus.put("questions", info.questions());
        ObjectNode embedder = doc.putObject("embedder");
        embedder.put("kind", info.embedder().kind);
        if (fake) embedder.put("note", FAKE_STAMP);
        else if (info.embedder() == Embedder.NONE) {
            embedder.put("note", "keyword-only: Postgres full-text ranking (ts_rank_cd); no vectors were compared");
        }
        ArrayNode rows = doc.putArray("retrieval");
        for (RetrievalEvalRunner.Result r : retrieval) {
            ObjectNode row = rows.addObject();
            row.put("mode", r.mode().name().toLowerCase(Locale.ROOT));
            row.put("chunk_target_tokens", r.size().targetTokens);
            row.put("questions", r.summary().questions());
            row.put("recall_at_1", r.summary().recallAt1());
            row.put("recall_at_5", r.summary().recallAt5());
            row.put("recall_at_10", r.summary().recallAt10());
            row.put("mrr", r.summary().mrr());
            row.put("ndcg_at_10", r.summary().ndcgAt10());
            if (fake) row.put("note", FAKE_STAMP);
        }
        if (acl != null) {
            ObjectNode a = doc.putObject("acl");
            a.put("mode", acl.mode().name().toLowerCase(Locale.ROOT));
            a.put("chunk_target_tokens", acl.size().targetTokens);
            a.put("questions", acl.questions());
            a.put("unauthorised_leaked_chunks", acl.unauthorisedLeakedChunks());
            a.put("unauthorised_no_answer_fraction", acl.unauthorisedNoAnswerFraction());
            a.put("authorised_recall_at_5", acl.authorisedRecallAt5());
            if (fake) a.put("note", FAKE_STAMP);
        }
        return doc;
    }

    /**
     * Writes {@code doc} to {@code out} after the checks above. Relative paths are resolved against
     * {@code repoRoot}.
     *
     * @throws IllegalArgumentException a fake-embedder run outside {@code build/eval/}
     * @throws IllegalStateException    a committed run that does not match the schema (nothing is written)
     */
    public static Path write(ObjectNode doc, Path out, Path repoRoot, Path schema) {
        Path root = repoRoot.toAbsolutePath().normalize();
        Path target = root.resolve(out).toAbsolutePath().normalize();
        boolean fake = Embedder.FAKE.kind.equals(doc.path("embedder").path("kind").asText());
        if (fake && !target.startsWith(root.resolve(SMOKE_DIR))) {
            throw new IllegalArgumentException("a fake-embedder run writes only under " + SMOKE_DIR + "/ (" + FAKE_STAMP
                    + "), never to " + target);
        }
        try {
            String text = JSON.writeValueAsString(doc) + "\n";
            if (!fake) {
                String schemaJson = Files.readString(root.resolve(schema));
                List<String> errors = new NetworkntOutputValidator(JSON).validate(schemaJson, text);
                if (!errors.isEmpty()) {
                    throw new IllegalStateException("results do not match " + schema + "; nothing written: " + errors);
                }
            }
            Files.createDirectories(target.getParent());
            Files.writeString(target, text);
            return target;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
