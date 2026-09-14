package cn.coder.sanwei.enterpriseaiassistant.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;

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

    private static final String PROMPT_LOCATION = "/prompts/enterprise-system-prompt.txt";

    @Bean
    public ChatClient enterpriseChatClient(ChatClient.Builder builder) {
        String prompt = loadSystemPrompt();
        return builder
                .defaultSystem(prompt)
                .build();
    }

    private String loadSystemPrompt() {
        ClassPathResource resource = new ClassPathResource(PROMPT_LOCATION);
        if (!resource.exists()) {
            // Record which file was not found
            log.error("System Prompt word file does not exist: {}", PROMPT_LOCATION);
            throw new IllegalStateException("Missing system prompt word file: " + PROMPT_LOCATION);
        }
        try {
            InputStream is = resource.getInputStream();
            String prompt = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            if (prompt.isBlank()) {
                log.error("System prompt word file is empty: {}", PROMPT_LOCATION);
                throw new IllegalStateException("System prompt word file is empty: " + PROMPT_LOCATION);
            }

            log.info("System prompt word loaded, source: {}, length: {} characters", PROMPT_LOCATION, prompt.length());
            return prompt;
        } catch (IOException e) {
            log.error("SystemPrompt load failed: {}", PROMPT_LOCATION, e);
            throw new IllegalStateException("SystemPrompt load failed: {}" + PROMPT_LOCATION, e);
        }
    }
}
