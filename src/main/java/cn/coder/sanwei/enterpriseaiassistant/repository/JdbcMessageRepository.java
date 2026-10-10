package cn.coder.sanwei.enterpriseaiassistant.repository;

import cn.coder.sanwei.enterpriseaiassistant.entity.AgentMessage;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * JdbcMessageRepository
 *
 * @author caiyang
 * @date 2026/10/9
 */
@Repository
public class JdbcMessageRepository {

    private final JdbcTemplate jdbcTemplate;

    public JdbcMessageRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public int insertTurn(long conversationId, long turnNo, String userContent, String assistantContent) {
        String sql = "INSERT INTO agent_message (conversation_id, turn_no, role, content) " +
                "VALUES (?, ?, 'USER', ?),(?, ?, 'ASSISTANT', ?)";
        return jdbcTemplate.update(sql, conversationId, turnNo, userContent, conversationId, turnNo, assistantContent);
    }

    public List<AgentMessage> findRecentOwnedMessages(long conversationId, long tenantId, long userId, int turnLimit) {
        if (turnLimit < 1 || turnLimit >20) {
            throw new IllegalArgumentException("turnLimit 必须在 1～20 之间");
        }
        String sql = "WITH recent_turns AS ( " +
                "SELECT m.conversation_id, m.turn_no " +
                "FROM agent_message m " +
                "JOIN agent_conversation c ON c.id = m.conversation_id " +
                "WHERE c.id = ? AND c.tenant_id = ? AND c.user_id = ? " +
                "GROUP BY m.conversation_id, m.turn_no " +
                "HAVING COUNT(*) = 2 " +
                "ORDER BY m.turn_no DESC " +
                "LIMIT ? ) " +
                "SELECT m.id, m.conversation_id, m.turn_no, m.role, m.content, m.created_at " +
                "FROM agent_message m " +
                "JOIN recent_turns r ON r.conversation_id = m.conversation_id " +
                "AND r.turn_no = m.turn_no ORDER BY m.turn_no ASC, CASE m.role WHEN 'USER' THEN 0 ELSE 1 END";
        return jdbcTemplate.query(
                sql,
                ((rs, rowNum) -> {
                    AgentMessage message = new AgentMessage();
                    message.setId(rs.getLong("id"));
                    message.setConversationId(rs.getLong("conversation_id"));
                    message.setTurnNo(rs.getLong("turn_no"));
                    message.setRole(rs.getString("role"));
                    message.setContent(rs.getString("content"));
                    message.setCreatedAt(rs.getObject("created_at", OffsetDateTime.class));
                    return message;
                }),
                conversationId,
                tenantId,
                userId,
                turnLimit
        );
    }

    public long findMaxOwnedTurnNo(long conversationId, long tenantId, long userId) {
        if (conversationId <= 0 || tenantId <= 0 || userId <= 0) {
            throw new IllegalArgumentException("conversationId、tenantId、userId 都必须为正数");
        }

        String sql = "SELECT COALESCE(MAX(m.turn_no), 0) " +
                "FROM agent_message m " +
                "JOIN agent_conversation c ON c.id = m.conversation_id " +
                "WHERE c.id = ? AND c.tenant_id = ? AND c.user_id = ?";

        Long turnNo = jdbcTemplate.queryForObject(sql, Long.class, conversationId, tenantId, userId);
        return turnNo == null ? 0L : turnNo;
    }
}
