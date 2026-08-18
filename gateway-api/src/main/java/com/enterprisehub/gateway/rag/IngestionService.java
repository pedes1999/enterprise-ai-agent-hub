package com.enterprisehub.gateway.rag;

import com.enterprisehub.dto.IngestDocumentResponse;
import com.enterprisehub.rag.chunking.ParagraphChunker;
import com.enterprisehub.rag.entity.DocumentChunk;
import com.enterprisehub.rag.entity.KnowledgeSource;
import com.enterprisehub.rag.ingest.DocumentTextExtractor;
import com.enterprisehub.rag.repository.DocumentChunkRepository;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Synchronous by design (confirmed with the user): the upload endpoint
 * blocks through extract -> chunk -> embed -> store and returns the actual
 * result, no job queue. embedAll() below is what makes this "batched calls"
 * rather than one embedding API call per chunk -- LangChain4j's
 * OpenAiEmbeddingModel/GoogleAiEmbeddingModel already split a large segment
 * list into that provider's own max-batch-size groups internally, so a
 * single embedAll() call over every chunk in a document is already the
 * batched approach, not something this class needs to chunk further itself.
 */
@Service
public class IngestionService {

    private final KnowledgeSourceService knowledgeSourceService;
    private final DocumentChunkRepository documentChunkRepository;
    private final EmbeddingProviderResolver embeddingProviderResolver;
    private final DocumentTextExtractor textExtractor = new DocumentTextExtractor();
    private final ParagraphChunker chunker = new ParagraphChunker();

    public IngestionService(KnowledgeSourceService knowledgeSourceService, DocumentChunkRepository documentChunkRepository,
                             EmbeddingProviderResolver embeddingProviderResolver) {
        this.knowledgeSourceService = knowledgeSourceService;
        this.documentChunkRepository = documentChunkRepository;
        this.embeddingProviderResolver = embeddingProviderResolver;
    }

    public IngestDocumentResponse ingest(UUID tenantId, UUID userId, UUID knowledgeSourceId, String filename, byte[] content) {
        KnowledgeSource source = knowledgeSourceService.getOwned(tenantId, knowledgeSourceId);
        if (content == null || content.length == 0) {
            throw new RagException(HttpStatus.BAD_REQUEST, "Uploaded file is empty");
        }

        String text = textExtractor.extract(content, filename);
        List<ParagraphChunker.Chunk> chunks = chunker.chunk(text);
        if (chunks.isEmpty()) {
            throw new RagException(HttpStatus.BAD_REQUEST, "No extractable text found in " + filename);
        }

        EmbeddingModel embeddingModel = embeddingProviderResolver.resolve(tenantId, userId);
        List<TextSegment> segments = chunks.stream().map(chunk -> TextSegment.from(chunk.text())).toList();
        List<Embedding> embeddings = embeddingModel.embedAll(segments).content();

        List<DocumentChunk> toSave = new ArrayList<>(chunks.size());
        for (int i = 0; i < chunks.size(); i++) {
            DocumentChunk documentChunk = new DocumentChunk();
            documentChunk.setTenantId(tenantId);
            documentChunk.setKnowledgeSourceId(source.getId());
            documentChunk.setDocumentName(filename);
            documentChunk.setContent(chunks.get(i).text());
            documentChunk.setEmbedding(embeddings.get(i).vector());
            documentChunk.setChunkIndex(chunks.get(i).index());
            toSave.add(documentChunk);
        }
        documentChunkRepository.saveAll(toSave);

        return new IngestDocumentResponse(source.getId().toString(), filename, toSave.size());
    }
}
