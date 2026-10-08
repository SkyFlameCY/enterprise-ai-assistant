package cn.coder.sanwei.enterpriseaiassistant.audit;

/**
 * ToolAuditEvent
 *
 * @author caiyang
 * @date 2026/10/3
 */
public record ToolAuditEvent(
        String toolName,
        Long userId,
        Long tenantId,
        String outcome,
        long durationMs,
        int resultCount,
        String errorCode
) {
}
