package com.enterprisehub.rag.chunking;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Splits a document into embedding-sized chunks along paragraph boundaries
 * first, falling back to sentence boundaries only for a single paragraph
 * that alone exceeds the chunk budget, with a configurable character overlap
 * carried from the tail of one chunk into the start of the next.
 *
 * Why not naive fixed-size splitting (every N characters, no regard for
 * content): it's simpler and perfectly index-friendly, but it routinely
 * severs a sentence -- or an entire idea -- mid-thought at an arbitrary
 * character offset. An embedding computed over half a sentence plus the
 * unrelated start of the next one is a worse semantic representation of
 * either sentence than embedding them whole, which measurably hurts
 * retrieval recall; and a chunk shown to a user (or an LLM) as a citation
 * that begins or ends mid-word reads as broken. Paragraph-aware chunking
 * costs a little more implementation complexity (this class, versus a
 * one-line substring loop) and produces variable-sized chunks instead of
 * perfectly uniform ones, but keeps each chunk a coherent unit of meaning,
 * which is what both the embedding model and a human reading a citation
 * actually need. The overlap (a slice of the previous chunk's tail
 * prepended to the next) exists so a sentence that happens to fall right on
 * a chunk boundary still appears in full in at least one chunk, instead of
 * being split in half by the chunker itself.
 */
public class ParagraphChunker {

    private static final Pattern PARAGRAPH_BOUNDARY = Pattern.compile("\\n\\s*\\n");
    private static final Pattern SENTENCE_BOUNDARY = Pattern.compile("(?<=[.!?])\\s+");

    public record Chunk(String text, int index) {
    }

    private final int maxChunkChars;
    private final int overlapChars;

    /** ~2000 chars (~500 tokens) is comfortably inside every supported embedding model's input limit with room for several chunks per typical page; ~15% overlap. */
    public ParagraphChunker() {
        this(2000, 300);
    }

    public ParagraphChunker(int maxChunkChars, int overlapChars) {
        if (maxChunkChars <= 0) {
            throw new IllegalArgumentException("maxChunkChars must be positive");
        }
        if (overlapChars < 0 || overlapChars >= maxChunkChars) {
            throw new IllegalArgumentException("overlapChars must be non-negative and smaller than maxChunkChars");
        }
        this.maxChunkChars = maxChunkChars;
        this.overlapChars = overlapChars;
    }

    public List<Chunk> chunk(String text) {
        List<Chunk> chunks = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return chunks;
        }

        List<String> units = splitIntoUnits(text);
        StringBuilder buffer = new StringBuilder();
        for (String unit : units) {
            if (buffer.length() > 0 && buffer.length() + 2 + unit.length() > maxChunkChars) {
                chunks.add(new Chunk(buffer.toString(), chunks.size()));
                buffer = new StringBuilder(overlapTail(buffer.toString()));
            }
            if (buffer.length() > 0) {
                buffer.append("\n\n");
            }
            buffer.append(unit);
        }
        if (!buffer.isEmpty()) {
            chunks.add(new Chunk(buffer.toString(), chunks.size()));
        }
        return chunks;
    }

    /** Paragraphs, expanded into sentences in place whenever a single paragraph alone exceeds maxChunkChars -- see class javadoc. */
    private List<String> splitIntoUnits(String text) {
        List<String> units = new ArrayList<>();
        for (String paragraph : PARAGRAPH_BOUNDARY.split(text)) {
            String trimmed = paragraph.strip();
            if (trimmed.isEmpty()) {
                continue;
            }
            if (trimmed.length() <= maxChunkChars) {
                units.add(trimmed);
            } else {
                units.addAll(splitOversizedParagraph(trimmed));
            }
        }
        return units;
    }

    private List<String> splitOversizedParagraph(String paragraph) {
        List<String> units = new ArrayList<>();
        StringBuilder buffer = new StringBuilder();
        for (String sentence : SENTENCE_BOUNDARY.split(paragraph)) {
            String trimmed = sentence.strip();
            if (trimmed.isEmpty()) {
                continue;
            }
            // A single sentence longer than the whole chunk budget (rare --
            // typically a URL or code blob with no punctuation) is emitted
            // as its own oversized unit rather than truncated: silently
            // dropping part of the source text would be worse than one
            // chunk that runs over budget.
            if (buffer.length() > 0 && buffer.length() + 1 + trimmed.length() > maxChunkChars) {
                units.add(buffer.toString());
                buffer = new StringBuilder();
            }
            if (buffer.length() > 0) {
                buffer.append(' ');
            }
            buffer.append(trimmed);
        }
        if (!buffer.isEmpty()) {
            units.add(buffer.toString());
        }
        return units;
    }

    private String overlapTail(String chunkText) {
        if (chunkText.length() <= overlapChars) {
            return chunkText;
        }
        return chunkText.substring(chunkText.length() - overlapChars);
    }
}
