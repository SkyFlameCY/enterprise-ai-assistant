package cn.coder.sanwei.enterpriseaiassistant.tool;

import cn.coder.sanwei.enterpriseaiassistant.audit.ToolAuditEvent;
import cn.coder.sanwei.enterpriseaiassistant.audit.ToolAuditService;
import cn.coder.sanwei.enterpriseaiassistant.context.AgentExecutionContext;
import cn.coder.sanwei.enterpriseaiassistant.dto.TicketDetail;
import cn.coder.sanwei.enterpriseaiassistant.dto.TicketSummary;
import cn.coder.sanwei.enterpriseaiassistant.repository.JdbcBusinessRepository;
import cn.hutool.core.util.StrUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * TicketQueryTools
 *
 * @author caiyang
 * @date 2026/10/2
 */
@Slf4j
@Component
public class TicketQueryTools {

    private final ToolAuditService toolAuditService;

    private final JdbcBusinessRepository businessRepository;

    public TicketQueryTools(ToolAuditService toolAuditService,
                            JdbcBusinessRepository businessRepository) {
        this.toolAuditService = toolAuditService;
        this.businessRepository = businessRepository;
    }

    @Tool(description = """
            查询当前登录用户所属租户的工单，最多返回10条。
            按项目编号和状态筛选。这是只读工具，不会创建或修改工单。
            """)
    public List<TicketSummary> searchTickets(
            @ToolParam(description = "项目编号，例如 EA-2026")
            String projectCode,
            @ToolParam(description = "工单状态：OPEN、IN_PROGRESS、RESOLVED")
            String status,
            ToolContext toolContext
    ) {
        long start = System.nanoTime();
        AgentExecutionContext context = null;
        String outcome = "FAILED";
        String errorCode = "TOOL_ERROR";
        int resultCount = 0;

        try {
            if (toolContext == null
                    || toolContext.getContext() == null
                    || !(toolContext.getContext().get("executionContext") instanceof AgentExecutionContext identity)) {
                throw new IllegalArgumentException("工具调用缺少有效身份上下文");
            }

            context = identity;

            if (!context.permissions().contains("ticket:read")) {
                throw new AccessDeniedException("没有工单查询权限");
            }

            List<TicketSummary> result = queryTickets(projectCode, status, context);

            resultCount = result.size();
            outcome = "SUCCESS";
            errorCode = null;
            return result;
        } catch (AccessDeniedException e) {
            outcome = "DENIED";
            errorCode = "FORBIDDEN";
            throw e;
        } catch (IllegalArgumentException e) {
            errorCode = context == null ? "INVALID_CONTEXT" : "INVALID_ARGUMENT";
            throw e;
        } finally {
            long durationMs = (System.nanoTime() - start) / 1_000_000L;

            ToolAuditEvent event = new ToolAuditEvent(
                    "searchTickets",
                    context == null ? null : context.userId(),
                    context == null ? null : context.tenantId(),
                    outcome,
                    durationMs,
                    resultCount,
                    errorCode
            );

            try {
                toolAuditService.record(event);
            } catch (RuntimeException auditError) {
                log.warn("tool_audit_write_failed tool=searchTickets errorType={}",
                        auditError.getClass().getSimpleName());
            }
        }
    }

    @Tool(description = """
            按工单编号查询当前登录用户所属租户的工单详情。
            返回 null 表示未找到可访问的工单。
            这是只读工具，不会创建或修改工单。
            """)
    public TicketDetail getTicketDetail(
            @ToolParam(description = "工单编号，例如 TK-001")
            String ticketNo,
            ToolContext toolContext
    ) {
        long start = System.nanoTime();
        AgentExecutionContext context = null;
        String outcome = "FAILED";
        String errorCode = "TOOL_ERROR";
        int resultCount = 0;

        try {
            if (toolContext == null || toolContext.getContext() == null || !((toolContext.getContext().get("executionContext")) instanceof AgentExecutionContext identity)) {
                throw new IllegalArgumentException("工具调用缺少有效身份上下文");
            }

            context = identity;

            if (!context.permissions().contains("ticket:read")) {
                throw new AccessDeniedException("没有工单查询权限");
            }

            TicketDetail result = queryTicketDetail(ticketNo, context);
            resultCount = result == null ? 0 : 1;
            outcome = "SUCCESS";
            errorCode = null;
            return result;
        } catch (AccessDeniedException e) {
            outcome = "DENIED";
            errorCode = "FORBIDDEN";
            throw e;
        } catch (IllegalArgumentException e) {
            errorCode = context == null
                    ? "INVALID_CONTEXT" : "INVALID_ARGUMENT";
            throw e;
        } finally {
            ToolAuditEvent event = new ToolAuditEvent(
                    "getTicketDetail",
                    context == null ? null : context.userId(),
                    context == null ? null : context.tenantId(),
                    outcome,
                    (System.nanoTime() - start) / 1_000_000L,
                    resultCount,
                    errorCode
            );

            try {
                toolAuditService.record(event);
            } catch (RuntimeException auditError) {
                log.warn("tool_audit_write_failed tool=getTicketDetail errorType={}",
                        auditError.getClass().getSimpleName());
            }
        }
    }

    private List<TicketSummary> queryTickets(
            String projectCode,
            String status,
            AgentExecutionContext context
    ) {
        if (StrUtil.isBlank(projectCode) || StrUtil.isBlank(status)) {
            throw new IllegalArgumentException("项目编号或工单状态都不能为空");
        }

        if (projectCode.length() > 64) {
            throw new IllegalArgumentException("项目编号不能超过64个字符");
        }

        if (!List.of("OPEN","IN_PROGRESS","RESOLVED").contains(status)) {
            throw new IllegalArgumentException("工单状态不符合要求");
        }

        return businessRepository.findTickets(context.tenantId(), projectCode, status);
    }

    private TicketDetail queryTicketDetail(
            String ticketNo,
            AgentExecutionContext context
    ) {
        if (StrUtil.isBlank(ticketNo) || ticketNo.length() > 64) {
            throw new IllegalArgumentException("工单编号不能为空且不能超过64个字符");
        }

        return businessRepository.findTicketDetail(context.tenantId(), ticketNo);
    }
}
