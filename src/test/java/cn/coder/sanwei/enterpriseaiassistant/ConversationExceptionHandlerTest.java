package cn.coder.sanwei.enterpriseaiassistant;

import cn.coder.sanwei.enterpriseaiassistant.exception.AiServiceException;
import cn.coder.sanwei.enterpriseaiassistant.exception.ConversationConflictException;
import cn.coder.sanwei.enterpriseaiassistant.exception.ConversationNotAccessibleException;
import cn.coder.sanwei.enterpriseaiassistant.exception.ConversationStateException;
import cn.coder.sanwei.enterpriseaiassistant.exception.GlobalExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.stream.Stream;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 真实 MVC 异常解析 + 真实 Advice，测试专用 Controller 仅用于触发异常。
 * 不代表实际会话 API 已接入，也不替代身份认证/数据库集成验收。
 */
class ConversationExceptionHandlerTest {

    private MockMvc mvc;
    private ThrowingController controller;

    @BeforeEach
    void setUp() {
        controller = new ThrowingController();
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    @ParameterizedTest
    @MethodSource("businessErrors")
    void shouldMapConversationBusinessErrorToItsSpecificStatusAndCode(
            RuntimeException failure, int httpStatus, String errorCode, String message) throws Exception {
        controller.failure = failure;

        mvc.perform(get("/test-only/conversation-error"))
                .andExpect(status().is(httpStatus))
                .andExpect(jsonPath("$.errorCode").value(errorCode))
                .andExpect(jsonPath("$.message").value(message))
                .andExpect(jsonPath("$.answer").doesNotExist());
    }

    static Stream<Arguments> businessErrors() {
        return Stream.of(
                Arguments.of(new ConversationNotAccessibleException("未找到可访问的会话"),
                        404, "CONVERSATION_NOT_ACCESSIBLE", "未找到可访问的会话"),
                Arguments.of(new ConversationStateException("会话当前状态不允许继续对话"),
                        409, "CONVERSATION_STATE_ERROR", "会话当前状态不允许继续对话"),
                Arguments.of(new ConversationStateException("会话轮次已达上限，无法继续保存"),
                        409, "CONVERSATION_STATE_ERROR", "会话轮次已达上限，无法继续保存"),
                Arguments.of(new ConversationConflictException("会话已发生变化，请重新发起对话"),
                        409, "CONVERSATION_CONFLICT", "会话已发生变化，请重新发起对话")
        );
    }

    @ParameterizedTest
    @MethodSource("internalErrors")
    void shouldKeepExistingInternalErrorMappingWithoutExposingDetails(RuntimeException failure) throws Exception {
        controller.failure = failure;

        mvc.perform(get("/test-only/conversation-error"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.errorCode").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.message").value("服务器内部错误"));
    }

    static Stream<Arguments> internalErrors() {
        return Stream.of(
                Arguments.of(new DataAccessResourceFailureException("private SQL / database detail")),
                Arguments.of(new IllegalStateException("整轮消息写入数量异常")),
                // 此阶段不把所有内部 IllegalArgumentException 泛化成客户端 400。
                Arguments.of(new IllegalArgumentException("private internal argument detail"))
        );
    }

    @ParameterizedTest
    @MethodSource("modelErrors")
    void shouldRetainExistingSafeModelFailureMapping(RuntimeException failure) throws Exception {
        controller.failure = failure;

        mvc.perform(get("/test-only/conversation-error"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.errorCode").value("AI_SERVICE_ERROR"))
                .andExpect(jsonPath("$.message").value("AI 服务暂时不可用"));
    }

    static Stream<Arguments> modelErrors() {
        return Stream.of(Arguments.of(new AiServiceException("private model error detail")));
    }

    @RestController
    static class ThrowingController {
        private RuntimeException failure;

        @GetMapping("/test-only/conversation-error")
        public void trigger() {
            throw failure;
        }
    }
}
