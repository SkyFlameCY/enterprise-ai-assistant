package cn.coder.sanwei.enterpriseaiassistant.tools;

import cn.coder.sanwei.enterpriseaiassistant.audit.ToolAuditEvent;
import cn.coder.sanwei.enterpriseaiassistant.audit.ToolAuditService;
import cn.coder.sanwei.enterpriseaiassistant.context.AgentExecutionContext;
import cn.coder.sanwei.enterpriseaiassistant.model.KnowledgeSearchHit;
import cn.coder.sanwei.enterpriseaiassistant.service.KnowledgeRetrievalService;
import cn.hutool.core.util.StrUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * KnowledgeQueryTools
 *
 * @author caiyang
 * @date 2026/10/21
 */
@Component
@Slf4j
public class KnowledgeQueryTools {

    private final KnowledgeRetrievalService retrievalService;

    private final ToolAuditService toolAuditService;

    public KnowledgeQueryTools(
            KnowledgeRetrievalService retrievalService,
            ToolAuditService toolAuditService
    ) {
        this.retrievalService = retrievalService;
        this.toolAuditService = toolAuditService;
    }

    @Tool(description = """
            用于检索企业制度、规范和内部知识，返回来源片段；
            空列表表示没有找到可访问的匹配内部规定。
            """)
    public List<KnowledgeSearchHit> searchKnowledge(
            @ToolParam(description = "问题，例如：公司销售出差报销制度是什么")
            String question,
            ToolContext toolContext) {

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

            if (!context.permissions().contains("knowledge:read")) {
                throw new AccessDeniedException("没有查询企业知识库的权限");
            }

            if (StrUtil.isBlank(question) || question.length() > 4000) {
                throw new IllegalArgumentException("question 必须非空白且长度不能超过 4000 个字符");
            }

            List<KnowledgeSearchHit> result = retrievalService.retrieval(question, 3, 0.25, context);

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
            long duration = (System.nanoTime() - start) / 1_000_000L;

            ToolAuditEvent event = new ToolAuditEvent(
                    "searchKnowledge",
                    context == null ? null : context.userId(),
                    context == null ? null : context.tenantId(),
                    outcome,
                    duration,
                    resultCount,
                    errorCode
            );
            try {
                toolAuditService.record(event);
            } catch (RuntimeException auditError) {
                log.warn("tool_audit_write_failed tool=searchKnowledge errorType={}",
                        auditError.getClass().getSimpleName());
            }
        }
    }
}
