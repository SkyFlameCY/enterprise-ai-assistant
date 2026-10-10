package cn.coder.sanwei.enterpriseaiassistant.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

/**
 * AgentMemoryProperties
 *
 * @author caiyang
 * @date 2026/10/9
 */
@Component
@ConfigurationProperties(prefix = "agent.memory")
@Validated
@Getter
@Setter
public class AgentMemoryProperties {

    @Min(1)
    @Max(20)
    private int turnLimit = 5;

    @Min(4000)
    @Max(64000)
    private int maxContextChars = 16000;

    @Min(1)
    @Max(16000)
    private int maxAssistantChars = 8000;

}
