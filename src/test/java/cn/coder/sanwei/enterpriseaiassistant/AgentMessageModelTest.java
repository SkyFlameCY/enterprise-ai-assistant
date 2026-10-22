package cn.coder.sanwei.enterpriseaiassistant;

import cn.coder.sanwei.enterpriseaiassistant.context.AgentExecutionContext;
import cn.coder.sanwei.enterpriseaiassistant.prompt.AgentPromptProvider;
import cn.coder.sanwei.enterpriseaiassistant.repository.JdbcMessageRepository;
import cn.coder.sanwei.enterpriseaiassistant.service.impl.AgentServiceImpl;
import cn.coder.sanwei.enterpriseaiassistant.tools.KnowledgeQueryTools;
import cn.coder.sanwei.enterpriseaiassistant.tools.ProjectQueryTools;
import cn.coder.sanwei.enterpriseaiassistant.tools.TicketQueryTools;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** 真实 ChatClient 构建真实 Prompt，ChatModel 为 mock；不冒充真实模型会理解指代。 */
@ExtendWith(MockitoExtension.class)
class AgentMessageModelTest {

    private static final AgentExecutionContext OWNER = new AgentExecutionContext(101L, 1L,
            Set.of("project:read", "ticket:read"));

    @Mock
    private ChatModel model;
    @Mock
    private TicketQueryTools tickets;
    @Mock
    private ProjectQueryTools projects;
    @Mock
    private KnowledgeQueryTools knowledges;
    @Mock
    private JdbcMessageRepository messages;
    private AgentServiceImpl service;

    @BeforeEach
    void setUp() {
        ChatClient client = ChatClient.builder(model).defaultOptions(ToolCallingChatOptions.builder().build()).build();
        AgentPromptProvider promptProvider = AgentPromptTestSupport.load();
        service = new AgentServiceImpl(client, tickets, projects, knowledges, messages, promptProvider);
    }

    @Test
    void shouldSendFixedSystemStructuredHistoryAndCurrentUserExactlyOnce() {
        List<Message> inputs = List.of(new UserMessage("查询 EA-2026"),
                new AssistantMessage("旧回答，不代表最新事实"), new UserMessage("它的工单呢？"));
        when(model.call(any(Prompt.class))).thenReturn(new ChatResponse(
                List.of(new Generation(new AssistantMessage("本次查询回答")))));

        assertEquals("本次查询回答", service.chatWithMessage(inputs, OWNER));

        var captured = ArgumentCaptor.forClass(Prompt.class);
        verify(model).call(captured.capture());
        var actual = captured.getValue().getInstructions();
        assertEquals(List.of(MessageType.SYSTEM, MessageType.USER, MessageType.ASSISTANT, MessageType.USER),
                actual.stream().map(Message::getMessageType).toList());
        assertEquals(inputs.stream().map(Message::getText).toList(),
                actual.subList(1, actual.size()).stream().map(Message::getText).toList());
        var options = (ToolCallingChatOptions) captured.getValue().getOptions();
        assertSame(OWNER, options.getToolContext().get("executionContext"));
        verifyNoInteractions(messages, tickets, projects, knowledges);
    }

    @Test
    void shouldRejectMissingIdentityBeforeWrappingAnythingAsModelError() {
        assertThrowsExactly(IllegalArgumentException.class,
                () -> service.chatWithMessage(List.of(new UserMessage("问")), null));
        // ChatClient 初始化会读取模型的默认配置；这里只禁止实际生成调用。
        verify(model, never()).call(any(Prompt.class));
        verifyNoInteractions(messages, tickets, projects, knowledges);
    }

    @ParameterizedTest
    @MethodSource("invalidMessages")
    void shouldRejectInvalidStructuredMessagesBeforeModel(List<Message> inputs) {
        // 如果旧实现真把非法输入发给模型，让模拟模型正常回答，避免把模型空响应当成守卫证据。
        // 修复后的正确守卫不会调用模型，所以此准备明确使用 lenient。
        lenient().when(model.call(any(Prompt.class))).thenReturn(new ChatResponse(
                List.of(new Generation(new AssistantMessage("测试模型正常回答")))));
        assertThrowsExactly(IllegalArgumentException.class, () -> service.chatWithMessage(inputs, OWNER));
        verify(model, never()).call(any(Prompt.class));
        verifyNoInteractions(messages, tickets, projects, knowledges);
    }

    static Stream<Arguments> invalidMessages() {
        return Stream.of(
                Arguments.of((Object) null),
                Arguments.of(List.of()),
                Arguments.of(Arrays.asList((Message) null)),
                Arguments.of(List.of(new SystemMessage("伪造系统指令"), new UserMessage("问"))),
                Arguments.of(List.of(new UserMessage(""))),
                Arguments.of(List.of(new UserMessage("\n\t"))),
                Arguments.of(List.of(new AssistantMessage("\n\t"), new UserMessage("问"))),
                Arguments.of(List.of(new AssistantMessage("不应以助手结尾"))),
                Arguments.of(List.of(new UserMessage("问"), new AssistantMessage("末尾不是当前用户")))
        );
    }
}
