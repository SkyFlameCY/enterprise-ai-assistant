package cn.coder.sanwei.enterpriseaiassistant.service;

import cn.coder.sanwei.enterpriseaiassistant.context.AgentExecutionContext;
import cn.coder.sanwei.enterpriseaiassistant.dto.AgentChatResponse;

/**
 * ConversationAgentService
 *
 * @author caiyang
 * @date 2026/10/10
 */
public interface ConversationAgentService {

    AgentChatResponse chat(Long conversationId, String message, AgentExecutionContext context);
}
