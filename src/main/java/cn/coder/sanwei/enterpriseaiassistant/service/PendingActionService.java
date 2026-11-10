package cn.coder.sanwei.enterpriseaiassistant.service;

import cn.coder.sanwei.enterpriseaiassistant.context.AgentExecutionContext;
import cn.coder.sanwei.enterpriseaiassistant.dto.CreateTicketParams;
import cn.coder.sanwei.enterpriseaiassistant.dto.PendingActionPreview;

import java.util.List;
import java.util.UUID;

/**
 * PendingActionService
 *
 * @author caiyang
 * @date 2026/10/23
 */
public interface PendingActionService {

    /**
     * 准备草稿
     */
    PendingActionPreview prepareCreateTicketDraft(long conversationId, UUID requestId, CreateTicketParams params, AgentExecutionContext context);

    /**
     * 激活本轮草稿
     */
    List<PendingActionPreview> activeDraftsForTurn(long conversationId, UUID requestId, long turnNo, AgentExecutionContext context);
}
