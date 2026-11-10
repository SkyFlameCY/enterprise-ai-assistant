package cn.coder.sanwei.enterpriseaiassistant.tools;

import cn.coder.sanwei.enterpriseaiassistant.audit.ToolAuditEvent;
import cn.coder.sanwei.enterpriseaiassistant.audit.ToolAuditService;
import cn.coder.sanwei.enterpriseaiassistant.context.AgentExecutionContext;
import cn.coder.sanwei.enterpriseaiassistant.dto.CreateTicketParams;
import cn.coder.sanwei.enterpriseaiassistant.dto.PendingActionPreview;
import cn.coder.sanwei.enterpriseaiassistant.exception.AiServiceException;
import cn.coder.sanwei.enterpriseaiassistant.service.PendingActionService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * TicketCommandTools
 *
 * @author caiyang
 * @date 2026/10/27
 */
@Component
@Slf4j
public class TicketCommandTools {

    private final PendingActionService pendingActionService;

    private final ToolAuditService toolAuditService;

    public TicketCommandTools(PendingActionService pendingActionService,
                              ToolAuditService toolAuditService) {
        this.pendingActionService = pendingActionService;
        this.toolAuditService = toolAuditService;
    }

    @Tool(description = """
            该工具返回的是已保存的 DRAFT 预览，只代表“草稿准备好了”。
            改工具不创建业务工单。
            用户没提出创建意图时不能主动生成草稿，缺少必填信息先询问。
            """)
    public PendingActionPreview prepareCreateTicket(
            @ToolParam(description = "项目编号")
            String projectCode,
            @ToolParam(description = "工单标题")
            String title,
            @ToolParam(description = "工单描述")
            String description,
            @ToolParam(description = "工单优先级，仅 LOW/MEDIUM/HIGH")
            String priority,
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

            if (!(toolContext.getContext().get("conversationId") instanceof Long conversationId) || conversationId <= 0) {
                throw new IllegalArgumentException("ToolContext 中 conversationId 必须为正数 Long");
            }

            if (!(toolContext.getContext().get("requestId") instanceof UUID requestId)) {
                throw new IllegalArgumentException("ToolContext 中 requestId 必须为 UUID");
            }

            if (!context.permissions().contains("ticket:create")) {
                throw new AccessDeniedException("没有工单创建权限");
            }

            PendingActionPreview draft = pendingActionService.prepareCreateTicketDraft(
                    conversationId,
                    requestId,
                    new CreateTicketParams(projectCode, title, description, priority),
                    context
            );

            if (draft == null) {
                throw new AiServiceException("创建工单草稿失败");
            }

            outcome = "SUCCESS";
            resultCount = 1;
            errorCode = null;
            return draft;
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
                    "prepareCreateTicket",
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
                log.warn("tool_audit_write_failed tool=prepareCreateTicket errorType={}",
                        auditError.getClass().getSimpleName());
            }
        }
    }
}
