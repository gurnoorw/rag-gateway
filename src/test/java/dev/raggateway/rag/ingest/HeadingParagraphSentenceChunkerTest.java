package dev.raggateway.rag.ingest;

import dev.raggateway.core.CharRatioTokenEstimator;
import dev.raggateway.core.TokenEstimator;
import dev.raggateway.rag.ChunkText;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The chunker on the round-prep fixture ({@code rag/chunker-fixture.md}: three heading levels, a ~630-token
 * paragraph, a list, a code block, abbreviations and version numbers) with the D-47 sizes, 400/50/400 estimated
 * tokens (AC-t09-6).
 */
class HeadingParagraphSentenceChunkerTest {

    static final TokenEstimator ESTIMATOR = new CharRatioTokenEstimator();
    static final int TARGET = 400;
    static final int OVERLAP = 50;
    static final int MAX = 400;

    static String fixture;
    static List<ChunkText> chunks;

    final HeadingParagraphSentenceChunker chunker = new HeadingParagraphSentenceChunker(ESTIMATOR, TARGET, OVERLAP, MAX);

    @BeforeAll
    static void chunkFixture() throws IOException {
        try (InputStream in = HeadingParagraphSentenceChunkerTest.class.getResourceAsStream("/rag/chunker-fixture.md")) {
            fixture = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        chunks = new HeadingParagraphSentenceChunker(ESTIMATOR, TARGET, OVERLAP, MAX).split("Batch work", fixture);
    }

    @Test
    void fixtureHasTheLongParagraphThePlanDescribes() {
        String longest = Arrays.stream(fixture.split("\n\n")).max((a, b) -> a.length() - b.length()).orElseThrow();
        assertThat(ESTIMATOR.estimate(longest)).isGreaterThan(MAX);
        assertThat(chunks).hasSizeGreaterThan(5);
    }

    @Test
    void ac_t09_6_noChunkAboveTheMaximum() {
        for (ChunkText c : chunks) {
            assertThat(c.estimatedTokens()).isEqualTo(ESTIMATOR.estimate(c.text())).isLessThanOrEqualTo(MAX);
        }
    }

    @Test
    void ac_t09_6_ordinalsAreConsecutive() {
        assertThat(chunks.stream().map(ChunkText::ordinal)).containsExactlyElementsOf(
                IntStream.range(0, chunks.size()).boxed().toList());
    }

    @Test
    void ac_t09_6_noChunkEndsMidSentence() {
        for (ChunkText c : chunks) {
            String t = c.text();
            assertThat(t).as("chunk %d", c.ordinal())
                    .satisfiesAnyOf(
                            s -> assertThat(s).endsWith("."),
                            s -> assertThat(s).endsWith("!"),
                            s -> assertThat(s).endsWith("?"),
                            s -> assertThat(s).endsWith(":"),
                            s -> assertThat(s).endsWith("```"));
            assertThat(t).doesNotEndWith("e.g.").doesNotEndWith("i.e.").doesNotEndWith("Dr.")
                    .doesNotEndWith("Version 1.").doesNotEndWith(" 1.");
        }
        // The abbreviation and version sentences stay whole in whichever chunk holds them.
        assertThat(chunks).anySatisfy(c -> assertThat(c.text()).contains(
                "A run that fails because its node was drained is usually counted as well, e.g. when the node is "
                        + "replaced during an upgrade."));
        assertThat(chunks).anySatisfy(c -> assertThat(c.text()).contains(
                "Version 1.2 of the example tool changed this rule, so read the release notes before you rely on it."));
        assertThat(chunks).anySatisfy(c -> assertThat(c.text()).contains("Dr. Smith's team measured"));
        assertThat(chunks).anySatisfy(c -> assertThat(c.text()).contains(
                "i.e. from limits that were set too low, so they raised the memory limit before they raised the retry limit."));
    }

    @Test
    void ac_t09_6_sentencesSplitOnlyAtRealEnds() {
        String text = "Use a tool, e.g. Tool. Dr. Smith said so. Version 1.2 works, i.e. Fine! Is it? Yes.";
        // A heading-free paragraph longer than the max forces sentence splitting; here we use a tiny limit so each
        // sentence is its own chunk and the split points are visible.
        var tiny = new HeadingParagraphSentenceChunker(ESTIMATOR, 10, 0, 10);
        List<String> parts = tiny.split("t", text).stream().map(ChunkText::text).toList();
        assertThat(parts).containsExactly(
                "Use a tool, e.g. Tool.", "Dr. Smith said so.", "Version 1.2 works, i.e. Fine!", "Is it? Yes.");
    }

    @Test
    void ac_t09_6_consecutiveChunksInASectionOverlap() {
        int pairs = 0;
        for (int i = 1; i < chunks.size(); i++) {
            String next = chunks.get(i).text();
            if (next.startsWith("#")) continue;   // a heading starts a new section: no overlap across sections
            String prev = chunks.get(i - 1).text();
            String shared = longestSuffixThatIsPrefix(prev, next);
            assertThat(shared).as("overlap between chunks %d and %d", i - 1, i).isNotEmpty();
            // Up to the overlap size, or a single sentence when the last sentence alone is longer (D-47 "~50").
            boolean oneSentence = !shared.matches("(?s).*[.!?] [A-Z].*");
            assertThat(ESTIMATOR.estimate(shared) <= OVERLAP || oneSentence).as("overlap size: %s", shared).isTrue();
            assertThat(shared).as("overlap is whole sentences").endsWith(".");
            assertThat(shared.length()).isLessThan(prev.length());
            pairs++;
        }
        assertThat(pairs).as("the long paragraph gives at least one continued section").isPositive();
    }

    @Test
    void ac_t09_6_chunksCoverTheWholeText() {
        boolean[] covered = new boolean[fixture.length()];
        int from = 0;
        for (ChunkText c : chunks) {
            int at = fixture.indexOf(c.text(), Math.max(0, from - c.text().length()));
            assertThat(at).as("chunk %d is a span of the text, in order", c.ordinal()).isGreaterThanOrEqualTo(0);
            Arrays.fill(covered, at, at + c.text().length(), true);
            from = at + c.text().length();
        }
        for (int i = 0; i < fixture.length(); i++) {
            if (!Character.isWhitespace(fixture.charAt(i))) {
                assertThat(covered[i]).as("character %d (%s) covered", i, fixture.substring(i, Math.min(fixture.length(), i + 30)))
                        .isTrue();
            }
        }
    }

    @Test
    void ac_t09_6_aHeadingStartsANewChunk() {
        List<String> headings = new ArrayList<>();
        boolean inCode = false;
        for (String line : fixture.split("\n")) {
            if (line.startsWith("```")) inCode = !inCode;
            else if (!inCode && line.matches("#{1,6} .*")) headings.add(line);
        }
        assertThat(headings).hasSizeGreaterThanOrEqualTo(8);
        for (String h : headings) {
            assertThat(chunks).as(h).anySatisfy(c -> assertThat(c.text()).startsWith(h));
        }
        for (ChunkText c : chunks) {
            long inner = Arrays.stream(c.text().split("\n")).skip(1).filter(l -> l.matches("#{1,6} .*")).count();
            assertThat(inner).as("no heading inside chunk %d", c.ordinal()).isZero();
        }
    }

    @Test
    void codeBlockAndListStayWhole() {
        assertThat(chunks).anySatisfy(c -> assertThat(c.text()).contains(
                "```yaml\nkind: BatchTask\nspec:\n  completions: 5\n  parallelism: 2\n  retryLimit: 3\n  deadlineSeconds: 600\n```"));
        assertThat(chunks).anySatisfy(c -> assertThat(c.text()).contains(
                "- the number of successes needed to finish the task;\n- the number of runs allowed at the same time;"));
    }

    @Test
    void headingWithoutBodyIsCarriedIntoTheNextSection() {
        List<ChunkText> out = chunker.split("t", "# Top\n\n## Sub\n\nBody text here.\n");
        assertThat(out).extracting(ChunkText::text).containsExactly("# Top\n\n## Sub\n\nBody text here.");
    }

    @Test
    void hashLinesInsideCodeAreNotHeadings() {
        List<ChunkText> out = chunker.split("t", "## Run\n\n```bash\n# install\nmake\n```\n");
        assertThat(out).extracting(ChunkText::text).containsExactly("## Run\n\n```bash\n# install\nmake\n```");
    }

    @Test
    void ac_t09_6_aSentenceLongerThanTheMaxIsSplitOnWhitespace() {
        String sentence = IntStream.range(0, 1500).mapToObj(i -> "word" + i).collect(Collectors.joining(" ")) + ".";
        List<ChunkText> out = chunker.split("t", "## Long\n\n" + sentence + "\n\nAfter it.");
        assertThat(out).hasSizeGreaterThan(3);
        for (ChunkText c : out) assertThat(c.estimatedTokens()).isLessThanOrEqualTo(MAX);
        assertThat(out.getFirst().text()).as("the heading never stands alone").startsWith("## Long\n\nword0 word1 ");
        // Cut only between words: rejoining the pieces' words gives the sentence back, in order, once each.
        List<String> words = out.stream().flatMap(c -> Arrays.stream(c.text().split("\\s+")))
                .filter(w -> w.startsWith("word")).toList();
        assertThat(String.join(" ", words)).isEqualTo(sentence);
        assertThat(out.getLast().text()).endsWith("After it.");
    }

    @Test
    void aWordLongerThanTheMaxIsCutByCharacters() {
        String word = "x".repeat(5000);
        List<ChunkText> out = chunker.split("t", word);
        assertThat(out).allSatisfy(c -> assertThat(c.estimatedTokens()).isLessThanOrEqualTo(MAX));
        assertThat(out.stream().map(ChunkText::text).collect(Collectors.joining())).isEqualTo(word);
    }

    @Test
    void deterministic() {
        assertThat(chunker.split("Batch work", fixture)).isEqualTo(chunks);
    }

    @Test
    void shortDocumentIsOneChunkAndCrlfIsNormalised() {
        assertThat(chunker.split("t", "# T\r\n\r\nOne line.\r\n")).extracting(ChunkText::text)
                .containsExactly("# T\n\nOne line.");
    }

    @Test
    void rejectsInconsistentSizes() {
        assertThatThrownBy(() -> new HeadingParagraphSentenceChunker(ESTIMATOR, 100, 100, 100))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new HeadingParagraphSentenceChunker(ESTIMATOR, 400, 50, 300))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static String longestSuffixThatIsPrefix(String prev, String next) {
        for (int len = Math.min(prev.length(), next.length()); len > 0; len--) {
            if (prev.endsWith(next.substring(0, len))) return next.substring(0, len);
        }
        return "";
    }

    // ---- t09 review: bounded inputs for a 1 MB document (D-47 heap bound) ----

    @Test
    void aLongRunOfBareHeadingsNeverMakesAChunkAboveTheMaximum() {
        String doc = "# h\n".repeat(20_000) + "\nThe body.";
        List<ChunkText> out = chunker.split("t", doc);
        assertThat(out).allSatisfy(c -> assertThat(c.estimatedTokens()).isLessThanOrEqualTo(MAX));
        assertThat(out.getLast().text()).endsWith("The body.");
    }

    @Test
    void aHeadingLineLongerThanTheMaximumIsSplitLikeBodyText() {
        String doc = "# " + "word ".repeat(2_000) + "\n\nThe body.";
        List<ChunkText> out = chunker.split("t", doc);
        assertThat(out).hasSizeGreaterThan(1)
                .allSatisfy(c -> assertThat(c.estimatedTokens()).isLessThanOrEqualTo(MAX));
    }

    @Test
    void aOneMegabyteDocumentWithoutBreaksIsChunkedWithinTheMaximum() {
        for (String doc : List.of("x".repeat(1_000_000), "word ".repeat(200_000), "- item\n".repeat(140_000))) {
            List<ChunkText> out = chunker.split("t", doc);
            assertThat(out).allSatisfy(c -> assertThat(c.estimatedTokens()).isLessThanOrEqualTo(MAX));
        }
    }
}
