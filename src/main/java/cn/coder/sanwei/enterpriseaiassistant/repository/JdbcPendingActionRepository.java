package cn.coder.sanwei.enterpriseaiassistant.repository;

import cn.coder.sanwei.enterpriseaiassistant.entity.PendingAgentAction;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * JdbcPendingActionRepository
 *
 * @author caiyang
 * @date 2026/10/22
 */
@Repository
public class JdbcPendingActionRepository {

    private final JdbcTemplate jdbcTemplate;

    public JdbcPendingActionRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    private static final String SELECT_ACTION = """
            SELECT id, tenant_id, user_id, conversation_id, request_id,
            source_turn_no, action_type, status, params_json,
            preview_hash, expires_at, result_json, created_at, updated_at
            FROM agent_pending_action
            """;

    // 对应 MyBatis 的 resultMap：把结果集的一行转成 Java 对象
    private static final RowMapper<PendingAgentAction> ROW_MAPPER = (rs, rowNum) -> {
        PendingAgentAction action = new PendingAgentAction();
        action.setId(rs.getObject("id", UUID.class));
        action.setTenantId(rs.getLong("tenant_id"));
        action.setUserId(rs.getLong("user_id"));
        action.setConversationId(rs.getLong("conversation_id"));
        action.setRequestId(rs.getObject("request_id", UUID.class));
        // DRAFT 的来源轮次为 NULL，不能用 getLong 把 NULL 读成 0。
        action.setSourceTurnNo(rs.getObject("source_turn_no", Long.class));
        action.setActionType(rs.getString("action_type"));
        action.setStatus(rs.getString("status"));
        action.setParamsJson(rs.getString("params_json"));
        action.setPreviewHash(rs.getString("preview_hash"));
        action.setExpiresAt(rs.getObject("expires_at", OffsetDateTime.class));
        action.setResultJson(rs.getString("result_json"));
        action.setCreatedAt(rs.getObject("created_at", OffsetDateTime.class));
        action.setUpdatedAt(rs.getObject("updated_at", OffsetDateTime.class));
        return action;
    };

    /**
     * 只插入 CREATE_TICKET 草稿，不是任意状态的通用 insert
     * 新插入返回 actionId；同请求同参数已存在则返回 null
     * actionId 和 expiresAt 均由 Service 预先生成
     */
    public UUID insertDraftIfAbsent(PendingAgentAction action) {
        Objects.requireNonNull(action, "action 不能为空");
        Objects.requireNonNull(action.getId(), "actionId 必须由 Service 生成");

        String sql = """
                INSERT INTO agent_pending_action (
                    id, tenant_id, user_id, conversation_id, request_id, 
                    source_turn_no, action_type, status, params_json, 
                    preview_hash, expires_at, result_json
                ) VALUES (
                    ?, ?, ?, ?, ?, NULL, 'CREATE_TICKET', 'DRAFT', CAST(? AS jsonb), ?, ?, NULL
                ) ON CONFLICT (
                    tenant_id, user_id, request_id, action_type, preview_hash
                ) DO NOTHING RETURNING id
                """;

        List<UUID> ids = jdbcTemplate.queryForList(
                sql,
                UUID.class,
                action.getId(),
                action.getTenantId(),
                action.getUserId(),
                action.getConversationId(),
                action.getRequestId(),
                action.getParamsJson(),
                action.getPreviewHash(),
                action.getExpiresAt()
        );

        return ids.isEmpty() ? null : ids.getFirst();
    }

    /**
     * 按草稿唯一键查原记录，Service 还需要核对其 conversationId
     */
    public PendingAgentAction findByRequestAndHash(long tenantId, long userId, UUID requestId, String actionType, String previewHash) {
        String sql = SELECT_ACTION + """
                WHERE tenant_id = ? 
                    AND user_id = ? 
                    AND request_id = ? 
                    AND action_type = ? 
                    AND preview_hash = ?
                """;

        List<PendingAgentAction> rows = jdbcTemplate.query(
                sql,
                ROW_MAPPER,
                tenantId,
                userId,
                requestId,
                actionType,
                previewHash
        );

        return rows.isEmpty() ? null : rows.getFirst();
    }

    /**
     * 只能按操作编号 + 当前租户 + 当前用户查询
     */
    public PendingAgentAction findOwnedById(UUID actionId, long tenantId, long userId) {
        String sql = SELECT_ACTION + """
                WHERE id = ? 
                    AND tenant_id = ?
                    AND user_id = ?
                """;

        List<PendingAgentAction> rows = jdbcTemplate.query(sql, ROW_MAPPER, actionId, tenantId, userId);

        return rows.isEmpty() ? null : rows.getFirst();
    }

    /**
     * 读取本次编排、本会话、本人的 DRAFT，供轮次保存后激活
     */
    public List<PendingAgentAction> findDraftByRequest(long conversationId, UUID requestId, long tenantId, long userId) {
        String sql = SELECT_ACTION + """
                WHERE conversation_id = ? 
                    AND request_id = ? 
                    AND tenant_id = ? 
                    AND user_id = ? 
                    AND status = 'DRAFT'
                ORDER BY created_at ASC, id ASC
                """;

        return jdbcTemplate.query(sql, ROW_MAPPER, conversationId, requestId, tenantId, userId);
    }

    /**
     * 只能激活未过期、仍为 DRAFT 的本人本轮操作。
     * 返回影响行数；Service 必须检查为 1，并在同事务写 PREVIEW_READY 审计。
     * now 来自 Service 注入的 Clock，不从模型或客户端获取。
     */
    public int activeDraft(
            UUID actionId,
            long conversationId,
            UUID requestId,
            long tenantId,
            long userId,
            long turnNo,
            OffsetDateTime now) {

        String sql = """
                UPDATE agent_pending_action
                SET status = 'PENDING_CONFIRMATION',
                    source_turn_no = ?,
                    updated_at = ?
                WHERE id = ?
                    AND conversation_id = ?
                    AND request_id = ?
                    AND tenant_id = ?
                    AND user_id = ?
                    AND status = 'DRAFT'
                    AND expires_at > ?
                """;

        return jdbcTemplate.update(sql, turnNo, now, actionId, conversationId, requestId, tenantId, userId, now);
    }
}
