package cn.coder.sanwei.enterpriseaiassistant.service;

import cn.coder.sanwei.enterpriseaiassistant.context.AgentExecutionContext;
import cn.coder.sanwei.enterpriseaiassistant.model.AgentTurnCommitResult;

import java.util.UUID;

/**
 * AgentTurnCommitService
 *
 * @author caiyang
 * @date 2026/10/27
 */
public interface AgentTurnCommitService {

    AgentTurnCommitResult commitCompletedTurn(
            long conversationId,
            long expectedVersion,
            UUID requestId,
            String userContent,
            String assistantContent,
            AgentExecutionContext context
    );
}
