package com.enterprisehub.rag.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.Type;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/**
 * One chunk (see ParagraphChunker) of one ingested document, plus its
 * embedding. embedding is mapped via VectorType (a hand-written Hibernate 6
 * UserType -- pgvector-java ships the JDBC-level PGvector type but not a
 * ready Hibernate mapping, see VectorType's javadoc). metadata is stored as
 * genuine jsonb (matching the task's data model) via Hibernate 6's built-in
 * JSON JdbcType applied to a plain String field -- the string already IS the
 * serialized JSON (page number, source offsets, etc. -- see IngestionService),
 * round-tripped as-is rather than mapped to a POJO, the same "just carry it
 * through" spirit as agent_executions.input_parameters (a TEXT column) even
 * though this column is a real jsonb one.
 */
@Entity
@Table(name = "document_chunk")
@Getter
@Setter
@NoArgsConstructor
public class DocumentChunk {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "knowledge_source_id", nullable = false)
    private UUID knowledgeSourceId;

    @Column(name = "document_name", nullable = false)
    private String documentName;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    @Type(VectorType.class)
    @Column(nullable = false, columnDefinition = "vector(768)")
    private float[] embedding;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private String metadata = "{}";

    @Column(name = "chunk_index", nullable = false)
    private int chunkIndex;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();
}
