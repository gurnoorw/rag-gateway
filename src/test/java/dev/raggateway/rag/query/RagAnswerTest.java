package dev.raggateway.rag.query;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.annotation.JsonInclude;
import dev.raggateway.api.dto.UsageDto;
import dev.raggateway.core.Usage;
import dev.raggateway.rag.RetrievedChunk;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Citations are only labels that are both in the answer and in the prompt (AC-t10-6, unit level). */
class RagAnswerTest {

    static List<RagAnswer.Source> fiveSources() {
        List<RetrievedChunk> chunks = new ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            chunks.add(new RetrievedChunk("chunk-" + i, "doc-" + i, "Title " + i, i, "text " + i, 1.0 / (60 + i), i, 0));
        }
        return RagAnswer.sources(chunks);
    }

    static List<String> labels(List<RagAnswer.Citation> citations) {
        return citations.stream().map(RagAnswer.Citation::source).toList();
    }

    @Test
    void sourcesAreLabelledInOrderAndCarryTheirRanks() {
        List<RagAnswer.Source> sources = fiveSources();

        assertThat(sources).extracting(RagAnswer.Source::source).containsExactly("S1", "S2", "S3", "S4", "S5");
        assertThat(sources.get(2).chunkId()).isEqualTo("chunk-3");
        assertThat(sources.get(2).keywordRank()).isEqualTo(3);
        assertThat(sources.get(2).vectorRank()).isZero();
    }

    @Test
    void ac_t10_6_anInventedLabelIsDropped() {
        List<RagAnswer.Citation> citations = RagAnswer.citations(
                "Pods restart [S2]. Also see [S9] and [S6].", fiveSources());

        assertThat(labels(citations)).containsExactly("S2");
        assertThat(citations.get(0).chunkId()).isEqualTo("chunk-2");
        assertThat(citations.get(0).docId()).isEqualTo("doc-2");
        assertThat(citations.get(0).title()).isEqualTo("Title 2");
    }

    @Test
    void citationsAreInOrderOfFirstCitationWithoutDuplicates() {
        List<RagAnswer.Citation> citations = RagAnswer.citations(
                "First [S3], then [S1][S3], and [S1, S4] and [ S5 ].", fiveSources());

        assertThat(labels(citations)).containsExactly("S3", "S1", "S4", "S5");
    }

    @Test
    void labelsThatOnlyLookLikeOnesAreNotCitations() {
        List<RagAnswer.Citation> citations = RagAnswer.citations(
                "S1 without brackets, [S0], [S01], [s2], (S3), [Source 4], [S1x]", fiveSources());

        assertThat(citations).isEmpty();
    }

    @Test
    void anAnswerWithoutCitationsHasNone() {
        assertThat(RagAnswer.citations("I don't know.", fiveSources())).isEmpty();
        assertThat(RagAnswer.citations(null, fiveSources())).isEmpty();
    }

    @Test
    void theBodyUsesSnakeCaseAndKeepsANullModel() throws Exception {
        // The application's Jackson drops nulls by default; the response keeps every field.
        ObjectMapper json = new ObjectMapper().setDefaultPropertyInclusion(JsonInclude.Include.NON_NULL);
        var body = new RagAnswer(RagAnswer.NO_ANSWER_TEXT, List.of(), List.of(), true, "hybrid", null,
                UsageDto.of(Usage.ZERO), "rid");

        JsonNode node = json.readTree(json.writeValueAsString(body));

        assertThat(node.has("model")).isTrue();
        assertThat(node.get("model").isNull()).isTrue();
        assertThat(node.get("no_answer").asBoolean()).isTrue();
        assertThat(node.get("retrieval_mode").asText()).isEqualTo("hybrid");
        assertThat(node.get("request_id").asText()).isEqualTo("rid");
        assertThat(node.at("/usage/total_tokens").asInt()).isZero();

        var source = fiveSources().get(0);
        JsonNode s = json.readTree(json.writeValueAsString(source));
        assertThat(s.fieldNames()).toIterable()
                .containsExactly("source", "chunk_id", "doc_id", "title", "score", "keyword_rank", "vector_rank");
    }
}
