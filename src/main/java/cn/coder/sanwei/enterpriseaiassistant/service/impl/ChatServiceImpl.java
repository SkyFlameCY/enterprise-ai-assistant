package cn.coder.sanwei.enterpriseaiassistant.service.impl;

import cn.coder.sanwei.enterpriseaiassistant.exception.AiServiceException;
import cn.coder.sanwei.enterpriseaiassistant.service.ChatService;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import java.util.HashMap;

/**
 * ChatServiceImpl
 *
 * @author caiyang
 * @date 2026/9/10
 */
@Service
public class ChatServiceImpl implements ChatService {

    private final ChatClient chatClient;

    @Value("classpath:/prompts/chat-user-prompt.txt")
    private Resource chatUserResource;

    public ChatServiceImpl(ChatClient chatClient) {
        this.chatClient = chatClient;
    }

    @Override
    public String chat(String message) {
        if (message == null || message.isBlank()) {
            throw new IllegalArgumentException("message 不能为空");
        }

        try {
            PromptTemplate promptTemplate = new PromptTemplate(chatUserResource);
            HashMap<String, Object> map = new HashMap<>();
            map.put("question", message);
            Prompt prompt = promptTemplate.create(map);
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
