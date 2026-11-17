package dev.raggateway.eval;

import dev.raggateway.rag.query.RagAnswer;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Answer checks that need no judge (D-53): exact, free and repeatable.
 *
 * <ul>
 *   <li>{@link #citationsWithinSources}: every citation names a source the model was shown, with the same chunk id
 *       (D-49 drops invented labels, so a failure here means the contract broke). Run for every question.</li>
 *   <li>{@link #isRefusal}: how an unanswerable question is scored. The gateway's no-sources response
 *       ({@code no_answer: true}) is a refusal; so is a model answer that starts with "I don't know" (the RAG
 *       system prompt asks for exactly that) and cites nothing.</li>
 * </ul>
 *
 * <p>Refusal trade-off: the check accepts "I don't know" followed by more text ("I don't know. The sources cover
 * only ...") as long as nothing is cited, because a model that adds a sentence after declining still declined. An
 * answer that cites a source is treated as an answer, not a refusal, whatever it says first. Rejected: matching
 * "I don't know" anywhere in the text (a real answer can contain the phrase); asking the judge (an LLM call for a
 * string check, and not exact).
 */
public final class DeterministicChecks {

    /** Straight or typographic apostrophe, any case, at the very start after whitespace. */
    private static final Pattern REFUSAL_START = Pattern.compile("^\\s*i\\s+don['’]t\\s+know\\b");

    private DeterministicChecks() {
    }

    /** True when every citation's label is among the sources and points at the same chunk. */
    public static boolean citationsWithinSources(RagAnswer answer) {
        Map<String, String> chunkByLabel = new HashMap<>();
        for (RagAnswer.Source s : answer.sources()) chunkByLabel.put(s.source(), s.chunkId());
        for (RagAnswer.Citation c : answer.citations()) {
            String chunk = chunkByLabel.get(c.source());
            if (chunk == null || !chunk.equals(c.chunkId())) return false;
        }
        return true;
    }

    /** True when the response declines to answer (see the class comment). */
    public static boolean isRefusal(RagAnswer answer) {
        if (answer.noAnswer()) return true;
        String text = answer.answer();
        if (text == null) return false;
        return answer.citations().isEmpty() && REFUSAL_START.matcher(text.toLowerCase(Locale.ROOT)).find();
    }
}
