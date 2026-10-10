package cn.coder.sanwei.enterpriseaiassistant.repository;

import cn.coder.sanwei.enterpriseaiassistant.entity.AgentConversation;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * JdbcConversationRepository
 *
 * @author caiyang
 * @date 2026/10/8
 */
@Repository
public class JdbcConversationRepository {

    private final JdbcTemplate jdbcTemplate;

    public JdbcConversationRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Long createConversation(long tenantId, long userId, String title) {
        String sql = "INSERT INTO agent_conversation (tenant_id, user_id, title) " +
                "VALUES(?, ?, ?) " +
                "RETURNING id";
        return jdbcTemplate.queryForObject(sql, Long.class, tenantId, userId, title);
    }

    public AgentConversation findOwnedConversation(long conversationId, long tenantId, long userId) {
        String sql = "SELECT id, tenant_id, user_id, title, status, version, created_at, updated_at " +
                "FROM agent_conversation " +
                "WHERE id = ? " +
                "AND tenant_id = ? " +
                "AND user_id = ?";
        List<AgentConversation> rows = jdbcTemplate.query(
                sql,
                ((rs, rowNum) -> {
                    AgentConversation agentConversation = new AgentConversation();
                    agentConversation.setId(rs.getLong("id"));
                    agentConversation.setTenantId(rs.getLong("tenant_id"));
                    agentConversation.setUserId(rs.getLong("user_id"));
                    agentConversation.setTitle(rs.getString("title"));
                    agentConversation.setStatus(rs.getString("status"));
                    agentConversation.setVersion(rs.getLong("version"));
                    agentConversation.setCreatedAt(rs.getObject("created_at", OffsetDateTime.class));
                    agentConversation.setUpdatedAt(rs.getObject("updated_at", OffsetDateTime.class));
                    return agentConversation;
                }),
                conversationId,
                tenantId,
                userId
        );

        return rows.isEmpty() ? null : rows.getFirst();
    }

    public int advanceVersionIfActiveOwned(long conversationId, long tenantId, long userId, long expectedVersion) {
        if (conversationId <= 0 || tenantId <= 0 || userId <= 0 || expectedVersion < 0 || expectedVersion == Long.MAX_VALUE) {
            throw new IllegalArgumentException("conversationId、tenantId、userId 都必须为正数；expectedVersion 必须非负且不能等于 Long.MAX_VALUE");
        }

        String sql = "UPDATE agent_conversation " +
                "SET version = version + 1, updated_at = CURRENT_TIMESTAMP " +
                "WHERE id = ? AND tenant_id = ? AND user_id = ? AND status = 'ACTIVE' AND version = ?";
        return jdbcTemplate.update(sql, conversationId, tenantId, userId, expectedVersion);
    }
}