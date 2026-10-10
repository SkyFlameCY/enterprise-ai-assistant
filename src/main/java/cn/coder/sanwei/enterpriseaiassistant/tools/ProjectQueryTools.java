package cn.coder.sanwei.enterpriseaiassistant.tools;

import cn.coder.sanwei.enterpriseaiassistant.audit.ToolAuditEvent;
import cn.coder.sanwei.enterpriseaiassistant.audit.ToolAuditService;
import cn.coder.sanwei.enterpriseaiassistant.context.AgentExecutionContext;
import cn.coder.sanwei.enterpriseaiassistant.dto.ProjectSummary;
import cn.coder.sanwei.enterpriseaiassistant.repository.JdbcBusinessRepository;
import cn.hutool.core.util.StrUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

/**
 * ProjectQueryTools
 *
 * @author caiyang
 * @date 2026/10/8
 */
@Slf4j
@Component
public class ProjectQueryTools {

    private final ToolAuditService toolAuditService;

    private final JdbcBusinessRepository businessRepository;

    public ProjectQueryTools(ToolAuditService toolAuditService,
                             JdbcBusinessRepository businessRepository) {
        this.toolAuditService = toolAuditService;
        this.businessRepository = businessRepository;
    }

    @Tool(description = """
            按项目编号查询当前登录用户所属租户的项目本身的信息。
            只需要 projectCode，不需要提供工单状态。
            返回 null 时说明“未找到可访问的项目”。
            回答时展示 projectCode、name、status。
            status 是项目状态，不得按工单状态解释。
            这是只读工具，不会创建、修改或删除项目。
            """)
    public ProjectSummary queryProject(
            @ToolParam(description = "项目编号，例如 EA-2026")
            String projectCode,
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

            if (!context.permissions().contains("project:read")) {
                throw new AccessDeniedException("没有项目查询权限");
            }

            ProjectSummary result = queryProjectData(projectCode, context);
            resultCount = result == null ? 0 : 1;
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
            ToolAuditEvent event = new ToolAuditEvent(
                    "queryProject",
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
                log.warn("tool_audit_write_failed tool=queryProject errorType={}", auditError.getClass().getSimpleName());
            }
        }
    }

    private ProjectSummary queryProjectData(String projectCode, AgentExecutionContext context) {
        if (StrUtil.isBlank(projectCode) || projectCode.length() > 64) {
            throw new IllegalArgumentException("项目编号不能为空且不能超过64个字符");
        }

        return businessRepository.findProject(context.tenantId(), projectCode);
    }
}
