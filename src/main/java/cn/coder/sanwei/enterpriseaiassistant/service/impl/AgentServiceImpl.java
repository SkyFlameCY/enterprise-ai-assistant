package cn.coder.sanwei.enterpriseaiassistant.service.impl;

import cn.coder.sanwei.enterpriseaiassistant.context.AgentExecutionContext;
import cn.coder.sanwei.enterpriseaiassistant.exception.AiServiceException;
import cn.coder.sanwei.enterpriseaiassistant.prompt.AgentPromptProvider;
import cn.coder.sanwei.enterpriseaiassistant.repository.JdbcMessageRepository;
import cn.coder.sanwei.enterpriseaiassistant.service.AgentService;
import cn.coder.sanwei.enterpriseaiassistant.tools.ProjectQueryTools;
import cn.coder.sanwei.enterpriseaiassistant.tools.TicketQueryTools;
import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.core.util.StrUtil;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * AgentServiceImpl
 *
 * @author caiyang
 * @date 2026/10/2
 */
@Service
public class AgentServiceImpl implements AgentService {

    private final ChatClient chatClient;

    private final TicketQueryTools ticketQueryTools;

    private final ProjectQueryTools projectQueryTools;

    private final JdbcMessageRepository messageRepository;

    private final AgentPromptProvider promptProvider;

    public AgentServiceImpl(
            @Qualifier("enterpriseChatClient") ChatClient chatClient,
            TicketQueryTools ticketQueryTools,
            ProjectQueryTools projectQueryTools,
            JdbcMessageRepository messageRepository,
            AgentPromptProvider promptProvider
    ) {
        this.chatClient = chatClient;
        this.ticketQueryTools = ticketQueryTools;
        this.projectQueryTools = projectQueryTools;
        this.messageRepository = messageRepository;
        this.promptProvider = promptProvider;
    }

    @Override
    public String chat(String message, AgentExecutionContext context) {
        if (StrUtil.isBlank(message)) {
            throw new IllegalArgumentException("message 不能为空");
        }

        if (context == null) {
            throw new IllegalArgumentException("身份上下文不能为空");
        }

        return chatWithMessage(List.of(new UserMessage(message)), context);
    }

    @Override
    public String chatWithMessage(List<Message> messages, AgentExecutionContext context) {
        if (CollectionUtil.isEmpty(messages)) {
            throw new IllegalArgumentException("messages 不能为空");
        }

        if (context == null) {
            throw new IllegalArgumentException("身份上下文不能为空");
        }

        for (Message item : messages) {
            if (item == null || StrUtil.isBlank(item.getText())) {
                throw new IllegalArgumentException("消息条目和正文不能为空");
            }

            MessageType role = item.getMessageType();
            if (role != MessageType.USER && role != MessageType.ASSISTANT) {
                throw new IllegalArgumentException("只允许 USER 和 ASSISTANT 消息");
            }
        }

        if (messages.getLast().getMessageType() != MessageType.USER) {
            throw new IllegalArgumentException("最后一条消息必须是当前用户输入");
        }

        try {
            String answer = chatClient.prompt()
                    .system(promptProvider.systemPrompt())
                    .messages(messages)
                    .tools(ticketQueryTools, projectQueryTools)
                    .toolContext(Map.of("executionContext", context))
                    .call()
                    .content();

            if (StrUtil.isBlank(answer)) {
                throw new AiServiceException("AI 未返回有效内容");
            }

            return answer;
        } catch (AiServiceException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new AiServiceException("Agent 模型调用失败", e);
        }
    }
}
