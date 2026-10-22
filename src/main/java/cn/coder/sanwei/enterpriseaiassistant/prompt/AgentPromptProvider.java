package cn.coder.sanwei.enterpriseaiassistant.prompt;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * AgentPromptProvider
 * 提示词统一读取
 * @author caiyang
 * @date 2026/10/10
 */
@Component
public class AgentPromptProvider {

    private final String systemPrompt;

    public AgentPromptProvider(
            @Value("classpath:/prompts/agent/core-system.txt")
            Resource coreResource,
            @Value("classpath:/prompts/agent/ticket-workflow.txt")
            Resource workFlowResource,
            @Value("classpath:/prompts/agent/knowledge-workflow.txt")
            Resource knowledgeWorkFlowResource
    ) {
        this.systemPrompt = readRequired(coreResource)
                + "\n\n"
                + readRequired(workFlowResource)
                + "\n\n"
                + readRequired(knowledgeWorkFlowResource);

    }

    public String systemPrompt() {
        return systemPrompt;
    }

    private static String readRequired(Resource resource) {
        try {
            String content = resource.getContentAsString(StandardCharsets.UTF_8).strip();

            if (content.isBlank()) {
                throw new IllegalStateException("提示词文件不能为空：" + resource.getDescription());
            }

            return content;
        } catch (IOException e) {
            throw new IllegalStateException("读取提示词文件失败" + resource.getDescription(), e);
        }
    }
}
