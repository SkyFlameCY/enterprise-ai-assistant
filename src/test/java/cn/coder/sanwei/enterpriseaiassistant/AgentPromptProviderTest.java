package cn.coder.sanwei.enterpriseaiassistant;

import cn.coder.sanwei.enterpriseaiassistant.prompt.AgentPromptProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.core.io.AbstractResource;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.Resource;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;

/** 验证真实 @Value 资源注入，不启动主应用、模型、数据库或读取 .env。 */
class AgentPromptProviderTest {

    private static final List<String> PATHS = List.of("prompts/agent/core-system.txt",
            "prompts/agent/ticket-workflow.txt", "prompts/agent/knowledge-workflow.txt");

    @Test
    void shouldLoadAllThreeRealResourcesExactlyOnceInOrder() throws IOException {
        List<String> contents = new ArrayList<>();
        for (String path : PATHS) {
            var resource = new ClassPathResource(path);
            assertTrue(resource.exists(), "必需的规则资源尚未创建：" + path);
            String content = resource.getContentAsString(StandardCharsets.UTF_8).strip();
            assertFalse(content.isBlank(), "必需规则不能为空：" + path);
            contents.add(content);
        }

        assertEquals(String.join("\n\n", contents), AgentPromptTestSupport.load().systemPrompt());
    }

    @ParameterizedTest
    @MethodSource("unavailableResources")
    void shouldFailStartupWhenAnyRequiredResourceIsMissingOrBlank(String path, boolean missing) {
        String location = "classpath:/" + path;
        var runner = new ApplicationContextRunner().withUserConfiguration(AgentPromptProvider.class)
                .withInitializer(context -> {
                    var loader = new DefaultResourceLoader(context.getClassLoader()) {
                        @Override
                        public Resource getResource(String resourceLocation) {
                            if (!location.equals(resourceLocation)) {
                                return super.getResource(resourceLocation);
                            }
                            if (!missing) {
                                return new ByteArrayResource(" \n\t".getBytes(StandardCharsets.UTF_8), location);
                            }
                            return new AbstractResource() {
                                @Override
                                public String getDescription() {
                                    return location;
                                }

                                @Override
                                public InputStream getInputStream() throws IOException {
                                    throw new FileNotFoundException(location);
                                }
                            };
                        }
                    };
                    ((GenericApplicationContext) context).setResourceLoader(loader);
                });

        runner.run(context -> {
            assertThat(context).hasFailed();
            Throwable failure = context.getStartupFailure();
            assertNotNull(failure);
            assertTrue(hasIllegalStateCause(failure), "资源缺失/空白必须触发受控启动失败");
            assertThat(failure).hasStackTraceContaining(path);
        });
    }

    static Stream<Arguments> unavailableResources() {
        return PATHS.stream().flatMap(path ->
                Stream.of(Arguments.of(path, true), Arguments.of(path, false)));
    }

    private static boolean hasIllegalStateCause(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof IllegalStateException) {
                return true;
            }
        }
        return false;
    }
}
