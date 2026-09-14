package cn.coder.sanwei.enterpriseaiassistant.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * AiConfig
 *
 * @author caiyang
 * @date 2026/9/10
 */
@Configuration
public class AiConfig {

    private static final Logger log = LoggerFactory.getLogger(AiConfig.class);

    @Bean
    public ChatClient enterpriseChatClient(
            ChatClient.Builder builder,
            @Value("classpath:/prompts/enterprise-system-prompt.txt") Resource systemPromptResource
    ) {
        String systemPrompt = readRequiredPrompt(systemPromptResource);
        return builder
                .defaultSystem(systemPrompt)
                .build();
    }

    @Bean
    @Qualifier("chatUserPromptTemplate")
    public PromptTemplate chatUserPromptTemplate(
            @Value("classpath:/prompts/chat-user-prompt.txt") Resource chatUserPromptTemplate
    ) {
        String userPrompt = readRequiredPrompt(chatUserPromptTemplate);
        return new PromptTemplate(userPrompt);
    }

    private String readRequiredPrompt(Resource resource) {
        try {
            String content = resource.getContentAsString(StandardCharsets.UTF_8);

            if (content.isBlank()) {
                throw new IllegalStateException("Prompt file is empty: " + resource.getDescription());
            }

            return content;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load prompt file: " + resource.getDescription(), e);
        }
    }
}
