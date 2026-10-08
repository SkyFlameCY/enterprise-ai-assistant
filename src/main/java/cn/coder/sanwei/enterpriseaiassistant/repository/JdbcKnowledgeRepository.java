package cn.coder.sanwei.enterpriseaiassistant.repository;

import cn.coder.sanwei.enterpriseaiassistant.entity.KnowledgeChunk;
import cn.coder.sanwei.enterpriseaiassistant.entity.KnowledgeDocument;
import cn.coder.sanwei.enterpriseaiassistant.model.KnowledgeSearchHit;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * JdbcKnowledgeRepository
 *
 * @author caiyang
 * @date 2026/9/28
 */
@Repository
public class JdbcKnowledgeRepository {

    private final JdbcTemplate jdbcTemplate;

    public JdbcKnowledgeRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    private final RowMapper<KnowledgeDocument> knowledgeDocumentRowMapper = (rs, rowNum) -> {
        KnowledgeDocument document = new KnowledgeDocument();
        document.setId(rs.getLong("id"));
        document.setTenantId(rs.getLong("tenant_id"));
        document.setDocumentKey(rs.getString("document_key"));
        document.setVersion(rs.getInt("version"));
        document.setTitle(rs.getString("title"));
        document.setSourceRef(rs.getString("source_ref"));
        document.setContentSha256(rs.getString("content_sha256"));
        document.setStatus(rs.getString("status"));
        document.setStatus(rs.getString("status"));
        document.setPermissionMetadata(rs.getString("permission_metadata"));
        document.setCreatedAt(rs.getTimestamp("created_at").toLocalDateTime());
        document.setUpdatedAt(rs.getTimestamp("updated_at").toLocalDateTime());
        return document;
    };

    private final RowMapper<KnowledgeChunk> knowledgeChunkRowMapper = (rs, rowNum) -> {
        KnowledgeChunk chunk = new KnowledgeChunk();
        chunk.setId(rs.getLong("id"));
        chunk.setDocumentId(rs.getLong("document_id"));
        chunk.setChunkIndex(rs.getInt("chunk_index"));
        chunk.setContent(rs.getString("content"));
        chunk.setContentSha256(rs.getString("content_sha256"));
        chunk.setEmbedding(rs.getString("embedding"));
        chunk.setEmbeddingModel(rs.getString("embedding_model"));
        chunk.setEmbeddingRevision(rs.getString("embedding_revision"));
        chunk.setCreatedAt(rs.getTimestamp("created_at").toLocalDateTime());
        return chunk;
    };

    private final RowMapper<KnowledgeSearchHit> knowledgeSearchHitRowMapper = (rs, rowNum) -> {
        KnowledgeSearchHit searchHit = new KnowledgeSearchHit(
                rs.getLong("chunk_id"),
                rs.getLong("document_id"),
                rs.getString("title"),
                rs.getString("source_ref"),
                rs.getInt("chunk_index"),
                rs.getString("content"),
                1.0 - rs.getDouble("distance"));
        return searchHit;
    };

    public Long insertDocument(KnowledgeDocument document) {
        String sql = "INSERT INTO knowledge_document(tenant_id, document_key, version, title, source_ref, content_sha256, status, permission_metadata) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, CAST( ? AS jsonb )) RETURNING id";
        return jdbcTemplate.queryForObject(sql, Long.class, document.getTenantId(), document.getDocumentKey(), document.getVersion(), document.getTitle(), document.getSourceRef(), document.getContentSha256(), document.getStatus(), document.getPermissionMetadata());
    }

    public Long insertChunk(KnowledgeChunk chunk) {
        String sql = "INSERT INTO knowledge_chunk(document_id, chunk_index, content, content_sha256, embedding, embedding_model, embedding_revision) " +
                "VALUES (?, ?, ?, ?, CAST( ? AS vector(1024) ), ?, ?) RETURNING id";
        return jdbcTemplate.queryForObject(sql, Long.class, chunk.getDocumentId(), chunk.getChunkIndex(), chunk.getContent(), chunk.getContentSha256(), chunk.getEmbedding(), chunk.getEmbeddingModel(), chunk.getEmbeddingRevision());
    }

    public KnowledgeDocument findDocumentById(Long id) {
        String sql = "SELECT * FROM knowledge_document WHERE id = ?";
        return jdbcTemplate.queryForObject(sql, knowledgeDocumentRowMapper, id);
    }

    public KnowledgeChunk findChunkById(Long id) {
        String sql = "SELECT * FROM knowledge_chunk WHERE id = ?";
        return jdbcTemplate.queryForObject(sql, knowledgeChunkRowMapper, id);
    }

    /**
     * 按租户检索 Top-K
     */
    public List<KnowledgeSearchHit> findTopK(long tenantId, String model, String revision, String queryVector, int limit) {
        String sql = """
                    SELECT c.id AS chunk_id, c.document_id, d.title, d.source_ref,
                               c.chunk_index, c.content,
                               c.embedding <=> CAST(? AS vector(1024)) AS distance
                    FROM knowledge_chunk c
                    JOIN knowledge_document d ON d.id = c.document_id
                    WHERE d.tenant_id = ?
                      AND d.status = 'ACTIVE'
                      AND c.embedding_model = ?
                      AND c.embedding_revision = ?
                    ORDER BY distance ASC, c.id ASC
                    LIMIT ?
                    """;
        return jdbcTemplate.query(sql, knowledgeSearchHitRowMapper, queryVector, tenantId, model, revision, limit);
    }

    /**
     * 查询是否存在相同文档
     */
    public boolean existsDocument(long tenantId, String documentKey, int version) {
        String sql = """
                    SELECT EXISTS(
                        SELECT 1 FROM knowledge_document
                                 WHERE tenant_id = ? AND document_key = ? AND version = ?
                    )
                    """;
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject(sql, Boolean.class, tenantId, documentKey, version));
    }
}
