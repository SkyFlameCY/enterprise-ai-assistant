package cn.coder.sanwei.enterpriseaiassistant.service.impl;

import cn.coder.sanwei.enterpriseaiassistant.exception.AiServiceException;
import cn.coder.sanwei.enterpriseaiassistant.service.ChatService;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * ChatServiceImpl
 *
 * @author caiyang
 * @date 2026/9/10
 */
@Service
public class ChatServiceImpl implements ChatService {

    private final ChatClient chatClient;

    private final PromptTemplate chatUserPromptTemplate;

    public ChatServiceImpl(ChatClient chatClient,
                           @Qualifier("chatUserPromptTemplate") PromptTemplate chatUserPromptTemplate) {
        this.chatClient = chatClient;
        this.chatUserPromptTemplate = chatUserPromptTemplate;
    }

    @Override
    public String chat(String message) {
        if (message == null || message.isBlank()) {
            throw new IllegalArgumentException("message 不能为空");
        }

        try {
            Prompt prompt = chatUserPromptTemplate.create(Map.of("question", message));
            String answer = chatClient
                    .prompt(prompt)
                    .call()
                    .content();

            if (answer == null || answer.isBlank()) {
                throw new AiServiceException("AI 未返回有效内容");
            }

            return answer;
        } catch (AiServiceException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new AiServiceException("AI 服务调用失败", e);
        }
    }
}
