package com.enterprisehub.gateway.rag;

import com.enterprisehub.dto.KnowledgeSourceSummary;
import com.enterprisehub.rag.entity.KnowledgeSource;
import com.enterprisehub.rag.repository.KnowledgeSourceRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

@Service
public class KnowledgeSourceService {

    private static final List<String> VALID_SOURCE_TYPES = List.of("upload", "url", "repo");

    private final KnowledgeSourceRepository repository;

    public KnowledgeSourceService(KnowledgeSourceRepository repository) {
        this.repository = repository;
    }

    public KnowledgeSourceSummary create(UUID tenantId, String name, String sourceType) {
        if (name == null || name.isBlank()) {
            throw new RagException(HttpStatus.BAD_REQUEST, "name is required");
        }
        if (sourceType == null || !VALID_SOURCE_TYPES.contains(sourceType)) {
            throw new RagException(HttpStatus.BAD_REQUEST, "sourceType must be one of " + VALID_SOURCE_TYPES);
        }
        KnowledgeSource source = new KnowledgeSource();
        source.setTenantId(tenantId);
        source.setName(name);
        source.setSourceType(sourceType);
        return toSummary(repository.save(source));
    }

    public List<KnowledgeSourceSummary> list(UUID tenantId) {
        return repository.findByTenantId(tenantId).stream().map(this::toSummary).toList();
    }

    /** Also the tenant-ownership check every other rag endpoint relies on -- 404s rather than leaking whether an id exists for a different tenant. */
    public KnowledgeSource getOwned(UUID tenantId, UUID knowledgeSourceId) {
        return repository.findByIdAndTenantId(knowledgeSourceId, tenantId)
                .orElseThrow(() -> new RagException(HttpStatus.NOT_FOUND, "No knowledge source " + knowledgeSourceId));
    }

    private KnowledgeSourceSummary toSummary(KnowledgeSource source) {
        return new KnowledgeSourceSummary(source.getId().toString(), source.getName(), source.getSourceType(), source.getCreatedAt());
    }
}
