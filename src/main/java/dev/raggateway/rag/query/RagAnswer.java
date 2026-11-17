package dev.raggateway.rag.query;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import dev.raggateway.api.dto.UsageDto;
import dev.raggateway.rag.RetrievedChunk;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Body of a {@code POST /v1/rag/query} 200 response (t10, DECISIONS.md D-49). Every field is always present
 * ({@code model} is {@code null} for a no-answer response), so clients see one shape.
 *
 * @param answer        the model's text, or {@link #NO_ANSWER_TEXT}
 * @param citations     the labels the answer cites that exist in the prompt, in order of first citation, without
 *                      duplicates, mapped to their chunks; invented labels are dropped
 * @param sources       every chunk put in the prompt, in rank order, with how retrieval ranked it
 * @param noAnswer      true when retrieval found nothing and the model was not called
 * @param retrievalMode the mode that actually ran ({@code keyword} after a hybrid degradation)
 * @param model         the model that served the answer ({@code null} when no model was called)
 * @param usage         tokens of the answer call (zero for a cache hit or a no-answer response)
 * @param requestId     same as {@code X-Request-Id}
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record RagAnswer(@JsonProperty("answer") String answer,
                        @JsonProperty("citations") List<Citation> citations,
                        @JsonProperty("sources") List<Source> sources,
                        @JsonProperty("no_answer") boolean noAnswer,
                        @JsonProperty("retrieval_mode") String retrievalMode,
                        @JsonProperty("model") String model,
                        @JsonProperty("usage") UsageDto usage,
                        @JsonProperty("request_id") String requestId) {

    /** Fixed answer when retrieval returns no chunk: the model is not asked to guess (LLD, D-49). */
    public static final String NO_ANSWER_TEXT =
            "I don't know: no sources in this collection match the question for this API key.";

    /**
     * A bracket holding one label or several separated by commas: {@code [S1]}, {@code [S1, S3]}. Labels are
     * compared as strings with the prompt's own labels, so {@code [S01]} or {@code [S0]} never match.
     */
    private static final Pattern CITATION_GROUP = Pattern.compile("\\[\\s*(S\\d+(?:\\s*,\\s*S\\d+)*)\\s*]");
    private static final Pattern LABEL = Pattern.compile("S\\d+");

    public RagAnswer {
        citations = List.copyOf(citations);
        sources = List.copyOf(sources);
    }

    /** One chunk the answer cites. */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Citation(@JsonProperty("source") String source,
                           @JsonProperty("chunk_id") String chunkId,
                           @JsonProperty("doc_id") String docId,
                           @JsonProperty("title") String title) {
    }

    /** One chunk the model saw. Ranks are 1-based, 0 when the chunk was not in that leg's list. */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Source(@JsonProperty("source") String source,
                         @JsonProperty("chunk_id") String chunkId,
                         @JsonProperty("doc_id") String docId,
                         @JsonProperty("title") String title,
                         @JsonProperty("score") double score,
                         @JsonProperty("keyword_rank") int keywordRank,
                         @JsonProperty("vector_rank") int vectorRank) {

        static Source of(int rank, RetrievedChunk c) {
            return new Source(RagPromptBuilder.label(rank), c.chunkId(), c.docId(), c.title(), c.score(),
                    c.keywordRank(), c.vectorRank());
        }
    }

    /** The prompt's sources, labelled {@code S1..Sk} in the chunks' order (the same labels as the prompt). */
    static List<Source> sources(List<RetrievedChunk> chunks) {
        List<Source> sources = new ArrayList<>(chunks.size());
        for (int i = 0; i < chunks.size(); i++) sources.add(Source.of(i + 1, chunks.get(i)));
        return sources;
    }

    /**
     * Citations of {@code answer}: labels that appear in it and are among {@code sources}, in order of first
     * citation, each once. A label the model invented ({@code [S9]} with five sources) is dropped.
     */
    static List<Citation> citations(String answer, List<Source> sources) {
        Map<String, Source> byLabel = new LinkedHashMap<>();
        for (Source s : sources) byLabel.put(s.source(), s);
        Map<String, Citation> cited = new LinkedHashMap<>();
        Matcher group = CITATION_GROUP.matcher(answer == null ? "" : answer);
        while (group.find()) {
            Matcher label = LABEL.matcher(group.group(1));
            while (label.find()) {
                Source s = byLabel.get(label.group());
                if (s != null) cited.putIfAbsent(s.source(), new Citation(s.source(), s.chunkId(), s.docId(), s.title()));
            }
        }
        return List.copyOf(cited.values());
    }
}
