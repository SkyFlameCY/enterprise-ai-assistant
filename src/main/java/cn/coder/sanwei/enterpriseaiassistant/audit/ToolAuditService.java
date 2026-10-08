package cn.coder.sanwei.enterpriseaiassistant.audit;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * ToolAuditService
 *
 * @author caiyang
 * @date 2026/10/3
 */
@Slf4j
@Service
public class ToolAuditService {

    public void record(ToolAuditEvent event) {
        log.info("tool_audit tool={} userId={} tenantId={} outcome={} "
        + "durationMs={} resultCount={} errorCode={}",
                event.toolName(),
                event.userId(),
                event.tenantId(),
                event.outcome(),
                event.durationMs(),
                event.resultCount(),
                event.errorCode()
        );
    }
}
