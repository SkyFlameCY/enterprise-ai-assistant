package cn.coder.sanwei.enterpriseaiassistant.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.UUID;

/**
 * JdbcActionAuditRepository
 *
 * @author caiyang
 * @date 2026/10/22
 */
@Repository
public class JdbcActionAuditRepository {

    private final JdbcTemplate jdbcTemplate;

    public JdbcActionAuditRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 返回影响行数，正常应为 1。
     * eventType 和 paramsHash 由 Service 决定，不能直接采用模型自报值。
     * 02A 的 businessRef 传 null；以后确认成功才传实际 ticketNo。
     */
    public int insert(UUID actionId, long tenantId, long userId, String eventType, String paramsHash, String businessRef) {
        String sql = """
                INSERT INTO agent_action_audit (
                    action_id, tenant_id, user_id,
                    event_type, params_hash, business_ref
                )
                VALUES (?, ?, ?, ?, ?, ?)
                """;

        return jdbcTemplate.update(sql, actionId, tenantId, userId, eventType, paramsHash, businessRef);
    }
}
