package cn.coder.sanwei.enterpriseaiassistant.service.impl;

import cn.coder.sanwei.enterpriseaiassistant.config.AgentMemoryProperties;
import cn.coder.sanwei.enterpriseaiassistant.context.AgentExecutionContext;
import cn.coder.sanwei.enterpriseaiassistant.dto.AgentChatResponse;
import cn.coder.sanwei.enterpriseaiassistant.entity.AgentConversation;
import cn.coder.sanwei.enterpriseaiassistant.entity.AgentMessage;
import cn.coder.sanwei.enterpriseaiassistant.exception.AiServiceException;
import cn.coder.sanwei.enterpriseaiassistant.exception.ConversationStateException;
import cn.coder.sanwei.enterpriseaiassistant.memory.ConversationHistoryBuilder;
import cn.coder.sanwei.enterpriseaiassistant.repository.JdbcMessageRepository;
import cn.coder.sanwei.enterpriseaiassistant.service.AgentService;
import cn.coder.sanwei.enterpriseaiassistant.service.ConversationAgentService;
import cn.coder.sanwei.enterpriseaiassistant.service.ConversationService;
import cn.hutool.core.util.StrUtil;
import org.springframework.ai.chat.messages.Message;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * ConversationAgentServiceImpl
 *
 * @author caiyang
 * @date 2026/10/10
 */
@Service
public class ConversationAgentServiceImpl implements ConversationAgentService {

    private final ConversationService conversationService;

    private final JdbcMessageRepository messageRepository;

    private final ConversationHistoryBuilder historyBuilder;

    private final AgentService agentService;

    private final AgentMemoryProperties memoryProperties;

    public ConversationAgentServiceImpl(ConversationService conversationService,
                                        JdbcMessageRepository messageRepository,
                                        ConversationHistoryBuilder historyBuilder,
                                        AgentService agentService,
                                        AgentMemoryProperties memoryProperties) {
        this.conversationService = conversationService;
        this.messageRepository = messageRepository;
        this.historyBuilder = historyBuilder;
        this.agentService = agentService;
        this.memoryProperties = memoryProperties;
    }

    @Override
    public AgentChatResponse chat(Long conversationId, String message, AgentExecutionContext context) {
        if (StrUtil.isBlank(message) || message.length() > 4000) {
            throw new IllegalArgumentException("message 不能为空且不得超过 4000 个字符");
        }

        if (context == null) {
            throw new IllegalArgumentException("身份上下文不能为空");
        }

        if (conversationId == null) {
            conversationId = conversationService.createConversation(context);
        }

        if (conversationId <= 0) {
            throw new IllegalArgumentException("conversationId 必须为正数");
        }

        AgentConversation conversation = conversationService.requireActiveOwnedConversation(conversationId, context);
        Long expectedVersion = conversation.getVersion();
        if (expectedVersion == null || expectedVersion < 0) {
            throw new IllegalStateException("expectedVersion 错误");
        }
        if (expectedVersion == Long.MAX_VALUE) {
            throw new ConversationStateException("服务端 version 已为 Long.MAX_VALUE");
        }

        List<AgentMessage> recentOwnedMessages = messageRepository.findRecentOwnedMessages(conversationId, context.tenantId(), context.userId(), memoryProperties.getTurnLimit());
        List<Message> messages = historyBuilder.build(recentOwnedMessages, message);

        // 调用模型回答
        String answer = agentService.chatWithMessage(messages, context);
        if (StrUtil.isBlank(answer) || answer.length() > memoryProperties.getMaxAssistantChars()) {
            throw new AiServiceException("模型回答错误");
        }

        long turnNo = conversationService.saveCompletedTurn(conversationId, expectedVersion, message, answer, context);
        return new AgentChatResponse(conversationId, turnNo, answer);
    }
}
