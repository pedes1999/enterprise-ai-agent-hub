package com.enterprisehub.rag.chunking;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ParagraphChunkerTest {

    @Test
    void chunk_nullOrBlankInput_returnsEmptyList() {
        ParagraphChunker chunker = new ParagraphChunker();

        assertThat(chunker.chunk(null)).isEmpty();
        assertThat(chunker.chunk("")).isEmpty();
        assertThat(chunker.chunk("   \n\n  ")).isEmpty();
    }

    @Test
    void chunk_singleShortParagraph_returnsOneChunkAtIndexZero() {
        ParagraphChunker chunker = new ParagraphChunker(200, 20);

        List<ParagraphChunker.Chunk> chunks = chunker.chunk("A short paragraph that fits easily in one chunk.");

        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).index()).isZero();
        assertThat(chunks.get(0).text()).isEqualTo("A short paragraph that fits easily in one chunk.");
    }

    @Test
    void chunk_multipleParagraphsUnderBudget_packedIntoOneChunk() {
        ParagraphChunker chunker = new ParagraphChunker(200, 20);
        String text = "First paragraph.\n\nSecond paragraph.\n\nThird paragraph.";

        List<ParagraphChunker.Chunk> chunks = chunker.chunk(text);

        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).text()).isEqualTo(text);
    }

    @Test
    void chunk_paragraphsExceedingBudget_splitAcrossChunksWithSequentialIndices() {
        ParagraphChunker chunker = new ParagraphChunker(30, 5);
        String text = "Paragraph one is here.\n\nParagraph two is here.\n\nParagraph three is here.";

        List<ParagraphChunker.Chunk> chunks = chunker.chunk(text);

        assertThat(chunks.size()).isGreaterThan(1);
        for (int i = 0; i < chunks.size(); i++) {
            assertThat(chunks.get(i).index()).isEqualTo(i);
        }
    }

    @Test
    void chunk_boundaryOverlap_nextChunkStartsWithTailOfPreviousChunk() {
        ParagraphChunker chunker = new ParagraphChunker(30, 10);
        String text = "Paragraph one is here.\n\nParagraph two is here.\n\nParagraph three is here.";

        List<ParagraphChunker.Chunk> chunks = chunker.chunk(text);

        assertThat(chunks.size()).isGreaterThan(1);
        for (int i = 1; i < chunks.size(); i++) {
            String previousTail = chunks.get(i - 1).text().substring(Math.max(0, chunks.get(i - 1).text().length() - 10));
            assertThat(chunks.get(i).text()).startsWith(previousTail);
        }
    }

    @Test
    void chunk_singleParagraphLargerThanBudget_fallsBackToSentenceSplitting() {
        ParagraphChunker chunker = new ParagraphChunker(40, 5);
        // One paragraph, no blank lines, but several sentences -- too long
        // for a single chunk, so the fallback (splitOversizedParagraph)
        // has to kick in and produce more than one chunk.
        String oversizedParagraph = "This is sentence one. This is sentence two. This is sentence three. This is sentence four.";

        List<ParagraphChunker.Chunk> chunks = chunker.chunk(oversizedParagraph);

        assertThat(chunks.size()).isGreaterThan(1);
        String rejoined = String.join(" ", chunks.stream().map(ParagraphChunker.Chunk::text).toList());
        assertThat(rejoined).contains("sentence one", "sentence two", "sentence three", "sentence four");
    }

    @Test
    void chunk_oneSentenceLongerThanWholeBudget_emittedAsOwnOversizedUnitRatherThanTruncated() {
        ParagraphChunker chunker = new ParagraphChunker(20, 5);
        String noPunctuationBlob = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";

        List<ParagraphChunker.Chunk> chunks = chunker.chunk(noPunctuationBlob);

        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).text()).isEqualTo(noPunctuationBlob);
    }

    @Test
    void constructor_rejectsInvalidOverlapConfiguration() {
        assertThatThrownBy(() -> new ParagraphChunker(100, 100)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ParagraphChunker(100, -1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ParagraphChunker(0, 0)).isInstanceOf(IllegalArgumentException.class);
    }
}
