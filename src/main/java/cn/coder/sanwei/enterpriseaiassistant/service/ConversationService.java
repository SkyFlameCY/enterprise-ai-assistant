package cn.coder.sanwei.enterpriseaiassistant.service;

import cn.coder.sanwei.enterpriseaiassistant.context.AgentExecutionContext;
import cn.coder.sanwei.enterpriseaiassistant.entity.AgentConversation;

/**
 * ConversationService
 *
 * @author caiyang
 * @date 2026/10/9
 */
public interface ConversationService {

    AgentConversation requireActiveOwnedConversation(long conversationId, AgentExecutionContext context);

    long saveCompletedTurn(long conversationId, long expectedVersion, String userContent, String assistantContent, AgentExecutionContext context);

    long createConversation(AgentExecutionContext context);
}
