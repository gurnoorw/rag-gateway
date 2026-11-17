package dev.raggateway.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.raggateway.validation.NetworkntOutputValidator;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The versioned eval set ({@code eval/questions.v<N>.jsonl}, t13, DECISIONS.md D-53): one question per line, each
 * line checked against {@code eval/questions.schema.json} when loaded, so a malformed label fails the run instead of
 * scoring quietly. Whether each evidence passage really is in its document's cleaned text is checked separately by
 * {@code scripts/eval/check_evidence.py}, which uses the loader's cleaning code.
 *
 * @param name      {@code questions.v1} (the file name without {@code .jsonl}; the results file's {@code eval_set})
 * @param questions in file order
 */
public record EvalSet(String name, List<Question> questions) {

    public static final Path SCHEMA = Path.of("eval/questions.schema.json");
    private static final Pattern FILE_NAME = Pattern.compile("(questions\\.v[0-9]+)\\.jsonl");
    private static final ObjectMapper JSON = new ObjectMapper();

    public EvalSet {
        Objects.requireNonNull(name, "name");
        questions = List.copyOf(questions);
    }

    public enum Kind {
        CONCEPTUAL, EXACT_TERM, UNANSWERABLE, ACL;

        static Kind of(String value) {
            return valueOf(value.toUpperCase(Locale.ROOT));
        }
    }

    /** A gold label: a document id and a passage copied verbatim from that document's cleaned text. */
    public record Gold(String docId, String evidence) {
        public Gold {
            Objects.requireNonNull(docId, "docId");
            Objects.requireNonNull(evidence, "evidence");
        }
    }

    /**
     * @param requiredGroups for an {@code acl} question, the groups of the authorised asker; empty otherwise
     */
    public record Question(String id, Kind kind, String question, List<Gold> gold, Set<String> requiredGroups) {
        public Question {
            gold = List.copyOf(gold);
            requiredGroups = Set.copyOf(requiredGroups);
        }

        /** Conceptual and exact-term questions: the ones recall@k, MRR and nDCG@10 are computed over. */
        public boolean answerable() {
            return kind == Kind.CONCEPTUAL || kind == Kind.EXACT_TERM;
        }

        /** The documents named by the gold labels, in label order. */
        public Set<String> goldDocIds() {
            Set<String> ids = new LinkedHashSet<>();
            gold.forEach(g -> ids.add(g.docId()));
            return ids;
        }
    }

    public List<Question> answerable() {
        return questions.stream().filter(Question::answerable).toList();
    }

    public List<Question> ofKind(Kind kind) {
        return questions.stream().filter(q -> q.kind() == kind).toList();
    }

    /** Reads and schema-checks the file; the schema is read from {@code schema}. */
    public static EvalSet load(Path file, Path schema) {
        Matcher m = FILE_NAME.matcher(file.getFileName().toString());
        if (!m.matches()) {
            throw new IllegalArgumentException("an eval set file is named questions.v<N>.jsonl, got " + file);
        }
        String schemaJson;
        List<String> lines;
        try {
            schemaJson = Files.readString(schema);
            lines = Files.readAllLines(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        NetworkntOutputValidator validator = new NetworkntOutputValidator(JSON);
        List<Question> questions = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.isBlank()) continue;
            List<String> errors = validator.validate(schemaJson, line);
            if (!errors.isEmpty()) {
                throw new IllegalArgumentException(file + " line " + (i + 1) + " does not match " + schema + ": "
                        + errors);
            }
            Question q = parse(line);
            if (!ids.add(q.id())) throw new IllegalArgumentException(file + " line " + (i + 1) + ": duplicate id " + q.id());
            questions.add(q);
        }
        return new EvalSet(m.group(1), questions);
    }

    private static Question parse(String line) {
        try {
            JsonNode n = JSON.readTree(line);
            List<Gold> gold = new ArrayList<>();
            n.path("gold").forEach(g -> gold.add(new Gold(g.path("doc_id").asText(), g.path("evidence").asText())));
            Set<String> groups = new LinkedHashSet<>();
            n.path("required_groups").forEach(g -> groups.add(g.asText()));
            return new Question(n.path("id").asText(), Kind.of(n.path("kind").asText()), n.path("question").asText(),
                    gold, groups);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
