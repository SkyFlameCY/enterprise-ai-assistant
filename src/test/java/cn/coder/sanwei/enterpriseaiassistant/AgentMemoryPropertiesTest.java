package cn.coder.sanwei.enterpriseaiassistant;

import cn.coder.sanwei.enterpriseaiassistant.config.AgentMemoryProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** 配置绑定的小型真实 Spring 上下文；不加载主应用、.env、模型或数据库。 */
class AgentMemoryPropertiesTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(PropertiesConfiguration.class);

    @Test
    void shouldUseSafeDefaultsWhenMemorySettingsAreMissing() {
        runner.run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(AgentMemoryProperties.class);
            var properties = context.getBean(AgentMemoryProperties.class);
            assertEquals(5, properties.getTurnLimit());
            assertEquals(16000, properties.getMaxContextChars());
            assertEquals(8000, properties.getMaxAssistantChars());
        });
    }

    @Test
    void shouldBindPlannedTopLevelPrefixWithoutSeparateValueInjection() {
        runner.withPropertyValues("agent.memory.turn-limit=7", "agent.memory.max-context-chars=12000",
                "agent.memory.max-assistant-chars=6000").run(context -> {
            assertThat(context).hasNotFailed();
            var properties = context.getBean(AgentMemoryProperties.class);
            assertEquals(7, properties.getTurnLimit());
            assertEquals(12000, properties.getMaxContextChars());
            assertEquals(6000, properties.getMaxAssistantChars());
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"agent.memory.turn-limit=0", "agent.memory.turn-limit=21",
            "agent.memory.max-context-chars=3999", "agent.memory.max-context-chars=64001",
            "agent.memory.max-assistant-chars=0", "agent.memory.max-assistant-chars=16001"})
    void shouldRejectOutOfRangeConfigurationAtStartup(String invalidSetting) {
        // 测试夹具兼容当前遗留 @Value，确保失败原因必须是计划前缀的范围校验。
        // 修复后这三个旧键不再参与绑定，也不应该覆盖 agent.memory 的值。
        runner.withPropertyValues("spring.agent.memory.turn-limit=5", "spring.agent.memory.max-context-chars=16000",
                        "spring.agent.memory.max-assistant-chars=8000", invalidSetting)
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void shouldPutApplicationMemorySettingsUnderTheSameTopLevelPrefix() throws Exception {
        // 仅把 YAML 当配置数据读取，不解析其中的环境变量，也不执行 config.import。
        var sources = new YamlPropertySourceLoader().load("test-yaml", new ClassPathResource("application.yaml"));
        var source = sources.getFirst();
        assertEquals(5, source.getProperty("agent.memory.turn-limit"));
        assertEquals(16000, source.getProperty("agent.memory.max-context-chars"));
        assertEquals(8000, source.getProperty("agent.memory.max-assistant-chars"));
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(AgentMemoryProperties.class)
    static class PropertiesConfiguration {
    }
}
