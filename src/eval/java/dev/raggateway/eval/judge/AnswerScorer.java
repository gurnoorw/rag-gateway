package dev.raggateway.eval.judge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.raggateway.eval.DeterministicChecks;
import dev.raggateway.eval.judge.JudgeClient.JudgeCallException;
import dev.raggateway.eval.judge.JudgeClient.Outcome;
import dev.raggateway.eval.judge.JudgeClient.Prompt;
import dev.raggateway.eval.judge.JudgeClient.Rejected;
import dev.raggateway.eval.judge.JudgeClient.Scored;
import dev.raggateway.rag.query.RagAnswer;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Scores collected RAG answers (D-53): the judge for answerable questions under the {@link SpendCap}, the
 * {@link DeterministicChecks} for everything else, and the {@code answers} section of the results file.
 *
 * <ul>
 *   <li><b>Order:</b> the free checks run first over every item; then answerable items go to the judge one at a
 *       time, in file order.</li>
 *   <li><b>Cap:</b> before each call, {@link SpendCap#allows} with the prompt's estimate; when it says no, the run
 *       stops there ({@link Report#stoppedByCap}). After the call, its reported usage is charged.</li>
 *   <li><b>Rejected verdicts</b> are listed with their reason and skipped; they are not "scored".</li>
 *   <li><b>A failed call</b> (HTTP error, I/O) is charged its worst case and stops the run: the next call would most
 *       likely fail the same way.</li>
 *   <li><b>Token estimate</b> ({@link #estimateInputTokens}): 3 characters per token, rounded up, over the system
 *       prompt, the user prompt and the verdict schema (the API adds the output format to the prompt). English
 *       averages nearer 4 characters per token, so this over-estimates; D-53 rejected {@code count_tokens}, which
 *       is itself an API call.</li>
 * </ul>
 */
public final class AnswerScorer {

    /** Characters per token for the local estimate (D-53). */
    static final int CHARS_PER_TOKEN = 3;

    /** One collected answer: the question, its gold evidence, the gateway's response and the sources' text. */
    public record Item(String id, String kind, String question, List<Gold> gold, RagAnswer response,
                       Map<String, String> sourceTexts) {
        public Item {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(question, "question");
            gold = List.copyOf(gold);
            Objects.requireNonNull(response, "response");
            sourceTexts = Map.copyOf(sourceTexts);
        }

        /** Judged: {@code conceptual} and {@code exact_term}. */
        public boolean answerable() {
            return "conceptual".equals(kind) || "exact_term".equals(kind);
        }

        public boolean unanswerable() {
            return "unanswerable".equals(kind);
        }
    }

    /** A gold passage. */
    public record Gold(String docId, String evidence) {
    }

    /** One judge call. {@link JudgeClient#judge} in production. */
    @FunctionalInterface
    public interface Judge {
        Outcome judge(Prompt prompt);
    }

    /** The judge prompt, from {@code eval/judge/prompt.txt}: a {@code [system]} part and a {@code [user]} template. */
    public static final class Template {
        public static final Path DEFAULT_FILE = Path.of("eval/judge/prompt.txt");
        private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{(question|gold_evidence|answer|cited_sources)}}");
        /** Closing tags of the prompt's own delimiters, defused in inserted text so data can't end its block. */
        private static final Pattern DELIMITER_CLOSE = Pattern.compile("(?i)</(question|gold_evidence|answer|source)");

        private final String system;
        private final String user;

        Template(String text) {
            int s = text.indexOf("[system]\n");
            int u = text.indexOf("\n[user]\n");
            if (s != 0 || u < 0) throw new IllegalArgumentException("prompt must start with [system] and have a [user] part");
            this.system = text.substring("[system]\n".length(), u).strip();
            this.user = text.substring(u + "\n[user]\n".length()).strip();
            for (String name : List.of("question", "gold_evidence", "answer", "cited_sources")) {
                if (!user.contains("{{" + name + "}}")) throw new IllegalArgumentException("prompt lacks {{" + name + "}}");
            }
        }

        public static Template load(Path file) throws IOException {
            return new Template(Files.readString(file, StandardCharsets.UTF_8));
        }

        /** The prompt for one item. One pass over the template, so inserted text is never re-expanded. */
        public Prompt build(Item item) {
            Map<String, String> values = Map.of(
                    "question", defuse(item.question()),
                    "gold_evidence", gold(item.gold()),
                    "answer", defuse(item.response().answer() == null ? "" : item.response().answer()),
                    "cited_sources", citedSources(item));
            Matcher m = PLACEHOLDER.matcher(user);
            StringBuilder sb = new StringBuilder();
            while (m.find()) m.appendReplacement(sb, Matcher.quoteReplacement(values.get(m.group(1))));
            m.appendTail(sb);
            return new Prompt(system, sb.toString());
        }

        private static String gold(List<Gold> gold) {
            List<String> parts = new ArrayList<>();
            for (Gold g : gold) parts.add("[" + defuse(g.docId()) + "] " + defuse(g.evidence()));
            return String.join("\n", parts);
        }

        /** The text of each cited source, in citation order, or "(none)". */
        private static String citedSources(Item item) {
            List<RagAnswer.Citation> cited = item.response().citations();
            if (cited.isEmpty()) return "(none)";
            StringBuilder sb = new StringBuilder();
            for (RagAnswer.Citation c : cited) {
                String text = item.sourceTexts().get(c.source());
                sb.append("<source id=\"").append(defuse(c.source())).append("\" doc=\"").append(defuse(c.docId()))
                        .append("\">\n").append(text == null ? "(text not collected)" : defuse(text))
                        .append("\n</source>\n");
            }
            return sb.toString().strip();
        }

        static String defuse(String s) {
            return DELIMITER_CLOSE.matcher(s).replaceAll("&lt;/$1");
        }
    }

    /** The result of a scoring run. {@code answers} is null when there is nothing honest to write. */
    public record Report(int of, int scored, List<String> rejected, boolean stoppedByCap, String error,
                         BigDecimal spentUsd, ObjectNode answers, List<String> lines) {
        public String summary() {
            return "scored " + scored + " of " + of;
        }
    }

    /** A dry-run estimate. */
    public record Estimate(int prompts, long inputTokens, long worstCaseOutputTokens, BigDecimal worstCaseUsd,
                           int fitUnderCap, BigDecimal capUsd) {
    }

    private final JudgeSettings settings;
    private final Template template;
    private final ObjectMapper json = new ObjectMapper();

    public AnswerScorer(JudgeSettings settings, Template template) {
        this.settings = Objects.requireNonNull(settings, "settings");
        this.template = Objects.requireNonNull(template, "template");
    }

    /** Estimated input tokens of a prompt (see the class comment). */
    public static long estimateInputTokens(Prompt prompt) {
        long chars = (long) prompt.system().length() + prompt.user().length() + JudgeVerdict.SCHEMA.length();
        return (chars + CHARS_PER_TOKEN - 1) / CHARS_PER_TOKEN;
    }

    /** Builds every judge prompt and estimates the run's worst case. Makes no call and needs no client. */
    public Estimate estimate(List<Item> items) {
        SpendCap cap = new SpendCap(settings);
        int prompts = 0;
        int fit = 0;
        long inputTokens = 0;
        BigDecimal worst = BigDecimal.ZERO;
        boolean stillFits = true;
        for (Item item : items) {
            if (!item.answerable()) continue;
            long est = estimateInputTokens(template.build(item));
            prompts++;
            inputTokens += est;
            worst = worst.add(cap.worstCase(est));
            if (stillFits && cap.allows(est)) {
                cap.chargeWorstCase(est);
                fit++;
            } else {
                stillFits = false;
            }
        }
        return new Estimate(prompts, inputTokens, prompts * settings.maxTokens(), worst, fit, settings.capUsd());
    }

    /**
     * Scores {@code items}. {@code mode} and {@code chunkTargetTokens} describe how the answers were generated; every
     * response must report that retrieval mode (a hybrid query that degraded to keyword is not a hybrid answer).
     * {@code handLabels} maps question id to the owner's correctness label (may be empty).
     */
    public Report score(List<Item> items, Judge judge, SpendCap cap, String mode, int chunkTargetTokens,
                        Map<String, Integer> handLabels) {
        List<String> lines = new ArrayList<>();
        int unanswerable = 0;
        int refused = 0;
        int citationsValid = 0;
        int modeMismatch = 0;
        for (Item item : items) {
            if (DeterministicChecks.citationsWithinSources(item.response())) citationsValid++;
            else lines.add(item.id() + ": a citation is not among the sources");
            if (!mode.equals(item.response().retrievalMode())) modeMismatch++;
            if (item.unanswerable()) {
                unanswerable++;
                if (DeterministicChecks.isRefusal(item.response())) refused++;
                else lines.add(item.id() + ": unanswerable question was answered");
            }
        }

        List<Item> answerable = items.stream().filter(Item::answerable).toList();
        List<String> rejected = new ArrayList<>();
        List<JudgeVerdict> verdicts = new ArrayList<>();
        int agree = 0;
        int labelled = 0;
        boolean stoppedByCap = false;
        String error = null;
        for (Item item : answerable) {
            Prompt prompt = template.build(item);
            long est = estimateInputTokens(prompt);
            if (!cap.allows(est)) {
                stoppedByCap = true;
                lines.add("spend cap reached: the next call's worst case $" + cap.worstCase(est).toPlainString()
                        + " exceeds the remaining $" + cap.remainingUsd().toPlainString() + "; stopped before " + item.id());
                break;
            }
            Outcome outcome;
            try {
                outcome = judge.judge(prompt);
            } catch (JudgeCallException e) {
                cap.chargeWorstCase(est);
                error = item.id() + ": " + e.getMessage();
                lines.add("stopped: " + error);
                break;
            }
            cap.charge(outcome.usage().inputTokens(), outcome.usage().outputTokens());
            switch (outcome) {
                case Scored s -> {
                    verdicts.add(s.verdict());
                    Integer label = handLabels.get(item.id());
                    if (label != null) {
                        labelled++;
                        if (label == s.verdict().correctness()) agree++;
                    }
                }
                case Rejected r -> {
                    rejected.add(item.id() + ": " + r.reason());
                    lines.add("rejected " + item.id() + ": " + r.reason());
                }
            }
        }

        int scored = verdicts.size();
        lines.add("scored " + scored + " of " + answerable.size() + " answerable; " + rejected.size() + " rejected; "
                + "unanswerable refused " + refused + " of " + unanswerable + "; citations valid " + citationsValid
                + " of " + items.size() + "; spent $" + cap.spentUsd().toPlainString() + " of $"
                + cap.capUsd().toPlainString());

        ObjectNode answers = null;
        if (scored == 0) {
            lines.add("no answers section: no verdict was accepted");
        } else if (unanswerable == 0) {
            lines.add("no answers section: the set has no unanswerable question (the schema requires at least one)");
        } else if (modeMismatch > 0) {
            lines.add("no answers section: " + modeMismatch + " responses did not run in mode " + mode
                    + " (degraded retrieval); re-collect");
        } else {
            answers = section(verdicts, answerable.size(), items.size(), citationsValid, unanswerable, refused, cap,
                    mode, chunkTargetTokens, labelled, agree);
        }
        return new Report(answerable.size(), scored, List.copyOf(rejected), stoppedByCap, error, cap.spentUsd(),
                answers, List.copyOf(lines));
    }

    private ObjectNode section(List<JudgeVerdict> verdicts, int of, int all, int citationsValid, int unanswerable,
                               int refused, SpendCap cap, String mode, int chunkTargetTokens, int labelled, int agree) {
        int correct = 0;
        int grounded = 0;
        int sum = 0;
        for (JudgeVerdict v : verdicts) {
            if (v.correctness() == 2) correct++;
            if (v.grounded()) grounded++;
            sum += v.correctness();
        }
        int n = verdicts.size();
        ObjectNode a = json.createObjectNode();
        ObjectNode judge = a.putObject("judge");
        judge.put("model", settings.model());
        judge.put("price_input_usd_per_mtok", settings.inputUsdPerMtok());
        judge.put("price_output_usd_per_mtok", settings.outputUsdPerMtok());
        judge.put("price_source", settings.priceSource());
        judge.put("effort", settings.effort());
        judge.put("cap_usd", cap.capUsd());
        judge.put("spent_usd", cap.spentUsd());
        a.put("generator_mode", mode);
        a.put("chunk_target_tokens", chunkTargetTokens);
        a.put("scored", n);
        a.put("of", of);
        a.put("correct_fraction", (double) correct / n);
        a.put("correctness_mean", (double) sum / n);
        a.put("grounded_fraction", (double) grounded / n);
        a.put("citations_valid_fraction", (double) citationsValid / all);
        ObjectNode u = a.putObject("unanswerable");
        u.put("questions", unanswerable);
        u.put("refused", refused);
        if (labelled > 0) {
            ObjectNode c = a.putObject("calibration");
            c.put("hand_labelled", labelled);
            c.put("agreement_fraction", (double) agree / labelled);
        }
        return a;
    }
}
