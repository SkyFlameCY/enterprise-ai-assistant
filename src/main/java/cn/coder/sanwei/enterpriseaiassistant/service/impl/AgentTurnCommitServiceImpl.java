package cn.coder.sanwei.enterpriseaiassistant.service.impl;

import cn.coder.sanwei.enterpriseaiassistant.context.AgentExecutionContext;
import cn.coder.sanwei.enterpriseaiassistant.dto.PendingActionPreview;
import cn.coder.sanwei.enterpriseaiassistant.model.AgentTurnCommitResult;
import cn.coder.sanwei.enterpriseaiassistant.service.AgentTurnCommitService;
import cn.coder.sanwei.enterpriseaiassistant.service.ConversationService;
import cn.coder.sanwei.enterpriseaiassistant.service.PendingActionService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * AgentTurnCommitServiceImpl
 *
 * @author caiyang
 * @date 2026/10/27
 */
@Service
public class AgentTurnCommitServiceImpl implements AgentTurnCommitService {

    private final ConversationService conversationService;

    private final PendingActionService pendingActionService;

    public AgentTurnCommitServiceImpl(ConversationService conversationService,
                                      PendingActionService pendingActionService) {
        this.conversationService = conversationService;
        this.pendingActionService = pendingActionService;
    }

    @Override
    @Transactional
    public AgentTurnCommitResult commitCompletedTurn(
            long conversationId,
            long expectedVersion,
            UUID requestId,
            String userContent,
            String assistantContent,
            AgentExecutionContext context) {

        long turnNo = conversationService.saveCompletedTurn(conversationId, expectedVersion, userContent, assistantContent, context);
        List<PendingActionPreview> previews = pendingActionService.activeDraftsForTurn(conversationId, requestId, turnNo, context);

        return new AgentTurnCommitResult(turnNo, previews);
    }
}
