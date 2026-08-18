package com.enterprisehub.gateway.rag;

import com.enterprisehub.dto.AgentKnowledgeSourceBindingSummary;
import com.enterprisehub.dto.CreateKnowledgeSourceRequest;
import com.enterprisehub.dto.IngestDocumentResponse;
import com.enterprisehub.dto.KnowledgeSourceSummary;
import com.enterprisehub.dto.RetrievalQueryRequest;
import com.enterprisehub.dto.RetrievedChunkResult;
import com.enterprisehub.gateway.security.PlatformPrincipal;
import com.enterprisehub.rag.retrieval.RetrievalQueryService;
import com.enterprisehub.rag.retrieval.RetrievedChunk;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.UUID;

/**
 * ADMIN and DEVELOPER can each create/ingest/query knowledge sources --
 * same access tier as vendor-credentials (see its javadoc), since both are
 * "bring your own setup, not everyone's business" surfaces, not
 * READONLY-visible ones. Attaching a source to an agent is ADMIN-only: it
 * changes what every tenant member's runs of that agent do, the same tier
 * as the tenant-wide "Agent defaults" preference (see TenantSettingsController).
 */
@RestController
@RequestMapping("/knowledge-sources")
public class KnowledgeSourceController {

    private static final int DEFAULT_TOP_K = 5;
    private static final int MAX_TOP_K = 20;

    private final KnowledgeSourceService knowledgeSourceService;
    private final IngestionService ingestionService;
    private final RetrievalQueryService retrievalQueryService;
    private final AgentKnowledgeSourceBindingService bindingService;

    public KnowledgeSourceController(KnowledgeSourceService knowledgeSourceService, IngestionService ingestionService,
                                      RetrievalQueryService retrievalQueryService, AgentKnowledgeSourceBindingService bindingService) {
        this.knowledgeSourceService = knowledgeSourceService;
        this.ingestionService = ingestionService;
        this.retrievalQueryService = retrievalQueryService;
        this.bindingService = bindingService;
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN','DEVELOPER')")
    public ResponseEntity<KnowledgeSourceSummary> create(@AuthenticationPrincipal PlatformPrincipal principal,
                                                           @RequestBody CreateKnowledgeSourceRequest request) {
        var summary = knowledgeSourceService.create(UUID.fromString(principal.tenantId()), request.name(), request.sourceType());
        return ResponseEntity.status(HttpStatus.CREATED).body(summary);
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN','DEVELOPER')")
    public ResponseEntity<List<KnowledgeSourceSummary>> list(@AuthenticationPrincipal PlatformPrincipal principal) {
        return ResponseEntity.ok(knowledgeSourceService.list(UUID.fromString(principal.tenantId())));
    }

    @PostMapping("/{id}/documents")
    @PreAuthorize("hasAnyRole('ADMIN','DEVELOPER')")
    public ResponseEntity<IngestDocumentResponse> ingest(@AuthenticationPrincipal PlatformPrincipal principal,
                                                           @PathVariable UUID id,
                                                           @RequestParam("file") MultipartFile file) {
        byte[] content;
        try {
            content = file.getBytes();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read uploaded file", e);
        }
        var response = ingestionService.ingest(UUID.fromString(principal.tenantId()), UUID.fromString(principal.userId()),
                id, file.getOriginalFilename(), content);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/{id}/query")
    @PreAuthorize("hasAnyRole('ADMIN','DEVELOPER')")
    public ResponseEntity<List<RetrievedChunkResult>> query(@AuthenticationPrincipal PlatformPrincipal principal,
                                                              @PathVariable UUID id,
                                                              @RequestBody RetrievalQueryRequest request) {
        knowledgeSourceService.getOwned(UUID.fromString(principal.tenantId()), id);
        int topK = request.topK() == null ? DEFAULT_TOP_K : Math.min(Math.max(request.topK(), 1), MAX_TOP_K);
        List<RetrievedChunk> results = retrievalQueryService.query(
                principal.tenantId(), principal.userId(), id, request.query(), topK);
        return ResponseEntity.ok(results.stream()
                .map(r -> new RetrievedChunkResult(r.chunkId(), r.documentName(), r.content(), r.score()))
                .toList());
    }

    /**
     * "agent-bindings/{agentSlug}" ahead of the {id}-scoped PUT/DELETE below
     * is a different path shape (two segments after knowledge-sources here,
     * three there) -- no routing ambiguity, same non-issue noted elsewhere
     * in this codebase for literal path segments ahead of a {id} pattern
     * (see AgentExecutionController's "usage" vs "{id}").
     */
    @GetMapping("/agent-bindings/{agentSlug}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<AgentKnowledgeSourceBindingSummary> getBindingForAgent(@AuthenticationPrincipal PlatformPrincipal principal,
                                                                                  @PathVariable String agentSlug) {
        return bindingService.findForAgent(UUID.fromString(principal.tenantId()), agentSlug)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    @PutMapping("/{id}/agent-bindings/{agentSlug}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> attachToAgent(@AuthenticationPrincipal PlatformPrincipal principal,
                                               @PathVariable UUID id,
                                               @PathVariable String agentSlug) {
        bindingService.attach(UUID.fromString(principal.tenantId()), id, agentSlug);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{id}/agent-bindings/{agentSlug}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> detachFromAgent(@AuthenticationPrincipal PlatformPrincipal principal,
                                                 @PathVariable UUID id,
                                                 @PathVariable String agentSlug) {
        bindingService.detach(UUID.fromString(principal.tenantId()), agentSlug);
        return ResponseEntity.noContent().build();
    }
}
