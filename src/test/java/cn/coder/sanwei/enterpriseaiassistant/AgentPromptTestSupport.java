package cn.coder.sanwei.enterpriseaiassistant;

import cn.coder.sanwei.enterpriseaiassistant.prompt.AgentPromptProvider;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

/** 只加载真实提示词 Bean，按生产 @Value 注入；不启动主应用、模型、数据库或 .env。 */
final class AgentPromptTestSupport {

    private AgentPromptTestSupport() {
    }

    static AgentPromptProvider load() {
        try (var context = new AnnotationConfigApplicationContext(AgentPromptProvider.class)) {
            return context.getBean(AgentPromptProvider.class);
        }
    }
}
