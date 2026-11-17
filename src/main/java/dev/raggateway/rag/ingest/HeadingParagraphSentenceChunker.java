package dev.raggateway.rag.ingest;

import dev.raggateway.core.TokenEstimator;
import dev.raggateway.rag.ChunkText;
import dev.raggateway.rag.Chunker;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Markdown chunker: headings first, then blocks (paragraphs, list items, code blocks), then sentences (t09,
 * DECISIONS.md D-47). Sizes are estimated tokens from the shared {@link TokenEstimator} (3 characters per token,
 * so the default 400 is about 1,200 characters; real tokens are fewer, which errs small).
 *
 * <ul>
 *   <li><b>Sections.</b> An ATX heading ({@code #} to {@code ######}) outside a code fence always starts a new
 *       chunk, and the first chunk of a section begins with its heading line. A heading with no text before the
 *       next heading is carried into the next section's first chunk, so no chunk is a bare heading, while the
 *       carried headings stay within a quarter of {@code maxTokens}; a longer run of bare headings becomes a chunk
 *       of its own, and a single heading line longer than that is packed and split like body text.</li>
 *   <li><b>Blocks.</b> Paragraphs are separated by blank lines; a fenced code block is one block. A block that
 *       fits in the current chunk joins it; one that does not, but fits in a chunk on its own, starts the next
 *       chunk whole; only a block larger than that is split into its units.</li>
 *   <li><b>Units.</b> A paragraph's units are its sentences; a line starting with a list marker or a table
 *       pipe also starts a unit. A sentence ends at {@code .}, {@code !} or {@code ?} followed by whitespace and
 *       a capital letter, digit, quote, bracket or backtick, and not after a known abbreviation ("e.g.",
 *       "i.e.", "Dr." ...) or a single-letter initial. "1.2" never ends a sentence (no whitespace after the
 *       dot). A code block larger than the maximum is split on lines.</li>
 *   <li><b>Too long.</b> Only a single sentence or code line longer than {@code maxTokens} is split, on
 *       whitespace (and a single word longer than that, by characters). The one other split: a section's first
 *       sentence that does not fit after its heading is split to the room left, so a heading never stands
 *       alone.</li>
 *   <li><b>Overlap.</b> A chunk that continues a section starts with whole sentences from the end of the
 *       previous chunk, up to {@code overlapTokens}, and at least the last sentence even when that one is longer
 *       (the target is "about 50"; a cut sentence would break the no-mid-sentence rule, and none would leave the
 *       continuation without context). Fewer, or none, when the overlap and the next block would not fit in the
 *       target together, or when the previous chunk ends with code or a fragment.</li>
 * </ul>
 * Every chunk's text is an exact span of the content (CRLF normalised to LF), so formatting survives and
 * coverage is checkable. The title is not put into chunk text: it is stored on each chunk row and the worker
 * embeds it with the chunk. Deterministic and stateless, so thread-safe.
 */
public final class HeadingParagraphSentenceChunker implements Chunker {

    private static final Pattern HEADING = Pattern.compile("^ {0,3}#{1,6}(?:[ \\t]|$)");
    private static final Pattern FENCE = Pattern.compile("^ {0,3}(`{3,}|~{3,})");
    private static final Pattern UNIT_START_LINE = Pattern.compile("^\\s*(?:[-*+]|\\d{1,9}[.)])\\s|^\\s*\\|");
    private static final Set<String> ABBREVIATIONS = Set.of(
            "e.g", "i.e", "dr", "mr", "mrs", "ms", "prof", "sr", "jr", "st", "vs", "cf", "fig", "approx");

    private final TokenEstimator estimator;
    private final int targetTokens;
    private final int overlapTokens;
    private final int maxTokens;

    public HeadingParagraphSentenceChunker(TokenEstimator estimator, int targetTokens, int overlapTokens,
                                           int maxTokens) {
        this.estimator = Objects.requireNonNull(estimator, "estimator");
        if (targetTokens <= 0 || overlapTokens < 0 || overlapTokens >= targetTokens || maxTokens < targetTokens) {
            throw new IllegalArgumentException("need 0 <= overlap < target <= max, got " + overlapTokens + " / "
                    + targetTokens + " / " + maxTokens);
        }
        this.targetTokens = targetTokens;
        this.overlapTokens = overlapTokens;
        this.maxTokens = maxTokens;
    }

    @Override
    public List<ChunkText> split(String title, String content) {
        String text = content == null ? "" : content.replace("\r\n", "\n");
        List<ChunkText> out = new ArrayList<>();
        for (Section section : sections(text)) new Packer(text, out).pack(section);
        return out;
    }

    /**
     * Largest heading (or run of bare headings carried together) that leads a chunk: a quarter of the maximum, so
     * the body always has most of the chunk and no heading run can push a chunk over the maximum.
     */
    private int headingCarryTokens() {
        return Math.max(1, maxTokens / 4);
    }

    // ---- parsing ----------------------------------------------------------------------------------------------

    private enum Kind { HEADING, SENTENCE, FRAGMENT, CODE }

    /** A span {@code [start, end)} of the text; starts and ends on a non-whitespace character. */
    private record Unit(int start, int end, Kind kind) {
    }

    /** A paragraph, list item group or code block: units in text order. */
    private record Block(List<Unit> units) {
        int start() {
            return units.getFirst().start();
        }

        int end() {
            return units.getLast().end();
        }
    }

    private record Section(Unit heading, List<Block> blocks) {
    }

    private List<Section> sections(String text) {
        List<Section> sections = new ArrayList<>();
        Unit heading = null;
        List<Block> blocks = new ArrayList<>();
        int pendingHeadingStart = -1;   // start of headings with no body, carried into the next section
        int paraStart = -1;
        int paraEnd = -1;
        int pos = 0;
        int n = text.length();
        while (pos < n) {
            int eol = lineEnd(text, pos);
            String line = text.substring(pos, eol);
            Matcher fence = FENCE.matcher(line);
            if (fence.find()) {
                if (paraStart >= 0) blocks.add(paragraph(text, paraStart, paraEnd));
                paraStart = -1;
                String marker = fence.group(1);
                int close = eol;
                int next = eol + 1;
                while (next < n) {
                    int e = lineEnd(text, next);
                    close = e;
                    if (closesFence(text.substring(next, e), marker)) break;
                    next = e + 1;
                }
                if (next >= n) close = trimEnd(text, pos, n);
                blocks.add(code(text, pos + leading(line), trimEnd(text, pos, close)));
                pos = close + 1;
                continue;
            }
            if (HEADING.matcher(line).find()) {
                if (paraStart >= 0) blocks.add(paragraph(text, paraStart, paraEnd));
                paraStart = -1;
                int carryStart = pendingHeadingStart >= 0 ? pendingHeadingStart : heading == null ? -1 : heading.start();
                if (heading != null && blocks.isEmpty()
                        && estimator.estimate(text.substring(carryStart, trimEnd(text, pos, eol))) <= headingCarryTokens()) {
                    pendingHeadingStart = carryStart;
                } else if (heading != null || !blocks.isEmpty()) {
                    // Also a run of bare headings too long to carry: it becomes a chunk of its own.
                    sections.add(section(heading, blocks, pendingHeadingStart));
                    pendingHeadingStart = -1;
                }
                heading = new Unit(pos + leading(line), trimEnd(text, pos, eol), Kind.HEADING);
                blocks = new ArrayList<>();
            } else if (line.isBlank()) {
                if (paraStart >= 0) blocks.add(paragraph(text, paraStart, paraEnd));
                paraStart = -1;
            } else {
                if (paraStart < 0) paraStart = pos + leading(line);
                paraEnd = trimEnd(text, pos, eol);
            }
            pos = eol + 1;
        }
        if (paraStart >= 0) blocks.add(paragraph(text, paraStart, paraEnd));
        if (heading != null || !blocks.isEmpty()) sections.add(section(heading, blocks, pendingHeadingStart));
        return sections;
    }

    private static Section section(Unit heading, List<Block> blocks, int pendingHeadingStart) {
        if (heading != null && pendingHeadingStart >= 0) {
            heading = new Unit(pendingHeadingStart, heading.end(), Kind.HEADING);
        }
        return new Section(heading, blocks);
    }

    private static boolean closesFence(String line, String marker) {
        String t = line.strip();
        if (t.length() < marker.length() || leading(line) > 3) return false;
        char c = marker.charAt(0);
        for (int i = 0; i < t.length(); i++) if (t.charAt(i) != c) return false;
        return true;
    }

    private Block code(String text, int start, int end) {
        if (estimator.estimate(text.substring(start, end)) <= maxTokens) {
            return new Block(List.of(new Unit(start, end, Kind.CODE)));
        }
        List<Unit> units = new ArrayList<>();
        int pos = start;
        while (pos < end) {
            int eol = Math.min(lineEnd(text, pos), end);
            int s = pos + leading(text.substring(pos, eol));
            int e = trimEnd(text, pos, eol);
            if (e > s) addUnit(text, units, s, e, Kind.CODE, maxTokens);
            pos = eol + 1;
        }
        return new Block(units);
    }

    /** A paragraph: one group per list item or table row, each split into sentences. */
    private Block paragraph(String text, int start, int end) {
        List<Unit> units = new ArrayList<>();
        int groupStart = start;
        int pos = lineEnd(text, start) + 1;
        while (pos < end) {
            int eol = Math.min(lineEnd(text, pos), end);
            if (UNIT_START_LINE.matcher(text.substring(pos, eol)).find()) {
                sentences(text, groupStart, trimEnd(text, groupStart, pos), units);
                groupStart = pos + leading(text.substring(pos, eol));
            }
            pos = eol + 1;
        }
        sentences(text, groupStart, end, units);
        return new Block(units);
    }

    private void sentences(String text, int start, int end, List<Unit> units) {
        int sentenceStart = start;
        for (int i = start; i < end; i++) {
            char c = text.charAt(i);
            if (c != '.' && c != '!' && c != '?') continue;
            int j = i + 1;
            while (j < end && isCloser(text.charAt(j))) j++;
            if (j >= end || !Character.isWhitespace(text.charAt(j))) continue;
            int k = j;
            while (k < end && Character.isWhitespace(text.charAt(k))) k++;
            if (k >= end || !startsSentence(text.charAt(k))) continue;
            if (c == '.' && isAbbreviation(text, sentenceStart, i)) continue;
            addUnit(text, units, sentenceStart, j, Kind.SENTENCE, maxTokens);
            sentenceStart = k;
            i = k - 1;
        }
        if (end > sentenceStart) addUnit(text, units, sentenceStart, end, Kind.SENTENCE, maxTokens);
    }

    private static boolean isCloser(char c) {
        return c == '"' || c == '\'' || c == ')' || c == ']' || c == '”' || c == '’' || c == '*' || c == '_';
    }

    private static boolean startsSentence(char c) {
        return Character.isUpperCase(c) || Character.isDigit(c) || c == '"' || c == '\'' || c == '(' || c == '['
                || c == '“' || c == '‘' || c == '*' || c == '_' || c == '`';
    }

    /** The word before the dot at {@code dot} is a known abbreviation or a single-letter initial. */
    private static boolean isAbbreviation(String text, int from, int dot) {
        int s = dot;
        while (s > from && (Character.isLetter(text.charAt(s - 1)) || text.charAt(s - 1) == '.')) s--;
        String word = text.substring(s, dot).toLowerCase(Locale.ROOT);
        if (word.length() == 1 && Character.isLetter(word.charAt(0))) return true;
        return ABBREVIATIONS.contains(word);
    }

    /** Adds the span as one unit, or as whitespace-split fragments when it is longer than {@code limit}. */
    private void addUnit(String text, List<Unit> units, int start, int end, Kind kind, int limit) {
        if (estimator.estimate(text.substring(start, end)) <= limit) {
            units.add(new Unit(start, end, kind));
            return;
        }
        int fragStart = -1;
        int fragEnd = -1;
        int pos = start;
        while (pos < end) {
            int wordStart = pos;
            while (wordStart < end && Character.isWhitespace(text.charAt(wordStart))) wordStart++;
            if (wordStart >= end) break;
            int wordEnd = wordStart;
            while (wordEnd < end && !Character.isWhitespace(text.charAt(wordEnd))) wordEnd++;
            if (fragStart < 0) {
                if (estimator.estimate(text.substring(wordStart, wordEnd)) > limit) {
                    cutWord(text, units, wordStart, wordEnd, limit);
                } else {
                    fragStart = wordStart;
                    fragEnd = wordEnd;
                }
                pos = wordEnd;
            } else if (estimator.estimate(text.substring(fragStart, wordEnd)) <= limit) {
                fragEnd = wordEnd;
                pos = wordEnd;
            } else {
                units.add(new Unit(fragStart, fragEnd, Kind.FRAGMENT));
                fragStart = -1;
                pos = wordStart;   // this word starts the next fragment
            }
        }
        if (fragStart >= 0) units.add(new Unit(fragStart, fragEnd, Kind.FRAGMENT));
    }

    /** A single word longer than the maximum: cut into the longest pieces that fit, on code point boundaries. */
    private void cutWord(String text, List<Unit> units, int start, int end, int limit) {
        int s = start;
        while (s < end) {
            int lo = s;                       // longest known end that fits
            int step = 256;
            int hi = Math.min(end, s + step); // first candidate
            while (estimator.estimate(text.substring(s, hi)) <= limit) {
                lo = hi;
                if (hi == end) break;
                step *= 2;
                hi = Math.min(end, s + step);
            }
            if (lo < end) {                   // binary search the largest fitting end in (lo, hi)
                int a = lo;
                int b = hi;
                while (b - a > 1) {
                    int mid = (a + b) >>> 1;
                    if (estimator.estimate(text.substring(s, mid)) <= limit) a = mid;
                    else b = mid;
                }
                lo = a;
            }
            if (lo < end && lo > s && Character.isLowSurrogate(text.charAt(lo))) lo--;
            if (lo <= s) lo = s + Character.charCount(text.codePointAt(s));
            units.add(new Unit(s, lo, Kind.FRAGMENT));
            s = lo;
        }
    }

    private static int lineEnd(String text, int pos) {
        int eol = text.indexOf('\n', pos);
        return eol < 0 ? text.length() : eol;
    }

    private static int leading(String line) {
        int i = 0;
        while (i < line.length() && Character.isWhitespace(line.charAt(i))) i++;
        return i;
    }

    /** End of {@code [from, end)} without trailing whitespace. */
    private static int trimEnd(String text, int from, int end) {
        int e = end;
        while (e > from && Character.isWhitespace(text.charAt(e - 1))) e--;
        return e;
    }

    // ---- packing ----------------------------------------------------------------------------------------------

    /** Packs one section's units into chunks. */
    private final class Packer {
        private final String text;
        private final List<ChunkText> out;
        private List<Unit> current = new ArrayList<>();

        Packer(String text, List<ChunkText> out) {
            this.text = text;
            this.out = out;
        }

        void pack(Section section) {
            Unit heading = section.heading();
            if (heading != null && estimate(heading.start(), heading.end()) <= headingCarryTokens()) {
                current.add(heading);
            } else if (heading != null) {
                // A heading line too long to lead a chunk is packed like body text, split to the maximum.
                List<Unit> parts = new ArrayList<>();
                HeadingParagraphSentenceChunker.this.addUnit(text, parts, heading.start(), heading.end(), Kind.FRAGMENT,
                        maxTokens);
                for (Unit part : parts) addUnit(part);
            }
            for (Block block : section.blocks()) {
                if (size(spanStart(block.start()), block.end()) <= targetTokens) {
                    current.addAll(block.units());
                } else if (hasBody() && estimate(block.start(), block.end()) <= maxTokens) {
                    List<Unit> previous = flush();
                    current = overlap(previous, block.start(), block.end());
                    current.addAll(block.units());
                } else {
                    for (Unit unit : block.units()) addUnit(unit);
                }
            }
            flush();
        }

        private void addUnit(Unit unit) {
            int size = size(spanStart(unit.start()), unit.end());
            if (size <= targetTokens || current.isEmpty() || (!hasBody() && size <= maxTokens)) {
                current.add(unit);
                return;
            }
            if (!hasBody()) {
                // Only the heading so far, and heading plus unit is over the maximum (a sentence near the maximum):
                // split this unit to the room the heading leaves, so the heading never stands alone.
                int room = maxTokens - estimate(current.getFirst().start(), unit.start());
                if (room > 0) {
                    List<Unit> parts = new ArrayList<>();
                    HeadingParagraphSentenceChunker.this.addUnit(text, parts, unit.start(), unit.end(), Kind.FRAGMENT, room);
                    current.add(parts.getFirst());
                    for (Unit part : parts.subList(1, parts.size())) addUnit(part);
                    return;
                }
            }
            List<Unit> previous = flush();
            current = overlap(previous, unit.start(), unit.end());
            current.add(unit);
        }

        /** Whole trailing sentences of the previous chunk, up to the overlap size, that fit with what follows. */
        private List<Unit> overlap(List<Unit> previous, int nextStart, int nextEnd) {
            List<Unit> units = new ArrayList<>();
            for (int i = previous.size() - 1; i >= 0; i--) {
                Unit u = previous.get(i);
                if (u.kind() != Kind.SENTENCE) break;
                // Whole sentences up to the overlap size; but at least the last sentence, even if it is longer,
                // so a section never continues without context (bounded below by the target).
                if (!units.isEmpty() && estimate(u.start(), previous.getLast().end()) > overlapTokens) break;
                units.addFirst(u);
            }
            while (!units.isEmpty() && estimate(units.getFirst().start(), nextEnd) > targetTokens) units.removeFirst();
            return units;
        }

        private boolean hasBody() {
            for (Unit u : current) if (u.kind() != Kind.HEADING) return true;
            return false;
        }

        private int spanStart(int fallback) {
            return current.isEmpty() ? fallback : current.getFirst().start();
        }

        private int size(int start, int end) {
            return estimate(start, end);
        }

        private int estimate(int start, int end) {
            return estimator.estimate(text.substring(start, end));
        }

        /** Emits the current chunk; returns its units. */
        private List<Unit> flush() {
            List<Unit> units = current;
            current = new ArrayList<>();
            if (units.isEmpty()) return units;
            String chunk = text.substring(units.getFirst().start(), units.getLast().end());
            out.add(new ChunkText(out.size(), chunk, estimator.estimate(chunk)));
            return units;
        }
    }
}
