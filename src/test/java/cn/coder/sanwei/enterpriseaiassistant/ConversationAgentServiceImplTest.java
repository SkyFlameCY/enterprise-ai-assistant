package cn.coder.sanwei.enterpriseaiassistant;

import cn.coder.sanwei.enterpriseaiassistant.config.AgentMemoryProperties;
import cn.coder.sanwei.enterpriseaiassistant.context.AgentExecutionContext;
import cn.coder.sanwei.enterpriseaiassistant.dto.AgentChatResponse;
import cn.coder.sanwei.enterpriseaiassistant.entity.AgentConversation;
import cn.coder.sanwei.enterpriseaiassistant.entity.AgentMessage;
import cn.coder.sanwei.enterpriseaiassistant.exception.AiServiceException;
import cn.coder.sanwei.enterpriseaiassistant.exception.ConversationConflictException;
import cn.coder.sanwei.enterpriseaiassistant.exception.ConversationNotAccessibleException;
import cn.coder.sanwei.enterpriseaiassistant.exception.ConversationStateException;
import cn.coder.sanwei.enterpriseaiassistant.memory.ConversationHistoryBuilder;
import cn.coder.sanwei.enterpriseaiassistant.repository.JdbcMessageRepository;
import cn.coder.sanwei.enterpriseaiassistant.service.AgentService;
import cn.coder.sanwei.enterpriseaiassistant.service.ConversationService;
import cn.coder.sanwei.enterpriseaiassistant.service.impl.ConversationAgentServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.dao.DataAccessResourceFailureException;

import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * 准备各边界返回值—执行真实会话编排—核验顺序、服务器身份、版本及失败不保存。
 * 本类 mock 外部边界，不冒充真实模型多轮、数据库事务或重启验收。
 */
@ExtendWith(MockitoExtension.class)
class ConversationAgentServiceImplTest {

    private static final long ID = 77L;
    private static final String QUESTION = "它有哪些未处理工单？";
    private static final String ANSWER = "本次工具查询返回 1 条工单";
    private static final AgentExecutionContext OWNER = context(101, 1);
    private static final List<AgentMessage> HISTORY = List.of(new AgentMessage());
    private static final List<Message> PROMPT_MESSAGES = List.of(new UserMessage(QUESTION));

    @Mock
    private ConversationService conversations;
    @Mock
    private JdbcMessageRepository messages;
    @Mock
    private ConversationHistoryBuilder builder;
    @Mock
    private AgentService modelService;

    private ConversationAgentServiceImpl service;
    private AgentMemoryProperties properties;

    @BeforeEach
    void setUp() {
        properties = new AgentMemoryProperties();
        properties.setTurnLimit(5);
        properties.setMaxContextChars(16000);
        properties.setMaxAssistantChars(8000);
        service = new ConversationAgentServiceImpl(conversations, messages, builder, modelService, properties);
    }

    @ParameterizedTest
    @CsvSource({"101, 1", "104, 1", "101, 2"})
    void shouldCheckOwnerReadHistoryCallModelThenSaveSnapshotVersion(long userId, long tenantId) {
        var context = context(userId, tenantId);
        stubPrepared(context, 7L);
        when(modelService.chatWithMessage(PROMPT_MESSAGES, context)).thenReturn(ANSWER);
        when(conversations.saveCompletedTurn(ID, 7L, QUESTION, ANSWER, context)).thenReturn(10L);

        assertEquals(new AgentChatResponse(ID, 10L, ANSWER), service.chat(ID, QUESTION, context));

        var order = inOrder(conversations, messages, builder, modelService);
        order.verify(conversations).requireActiveOwnedConversation(ID, context);
        order.verify(messages).findRecentOwnedMessages(ID, tenantId, userId, 5);
        order.verify(builder).build(HISTORY, QUESTION);
        order.verify(modelService).chatWithMessage(PROMPT_MESSAGES, context);
        order.verify(conversations).saveCompletedTurn(ID, 7L, QUESTION, ANSWER, context);
        verifyNoMoreInteractions(conversations, messages, builder, modelService);
    }

    @Test
    void shouldCreateOnlyForMissingIdAndSaveActualFirstTurnAfterModelSuccess() {
        when(conversations.createConversation(OWNER)).thenReturn(ID);
        stubPrepared(OWNER, 0L);
        when(modelService.chatWithMessage(PROMPT_MESSAGES, OWNER)).thenReturn(ANSWER);
        when(conversations.saveCompletedTurn(ID, 0L, QUESTION, ANSWER, OWNER)).thenReturn(1L);

        assertEquals(new AgentChatResponse(ID, 1L, ANSWER), service.chat(null, QUESTION, OWNER));

        var order = inOrder(conversations, messages, builder, modelService);
        order.verify(conversations).createConversation(OWNER);
        order.verify(conversations).requireActiveOwnedConversation(ID, OWNER);
        order.verify(messages).findRecentOwnedMessages(ID, 1L, 101L, 5);
        order.verify(builder).build(HISTORY, QUESTION);
        order.verify(modelService).chatWithMessage(PROMPT_MESSAGES, OWNER);
        order.verify(conversations).saveCompletedTurn(ID, 0L, QUESTION, ANSWER, OWNER);
        verifyNoMoreInteractions(conversations, messages, builder, modelService);
    }

    @Test
    void shouldKeepVersionFromBeforeModelRatherThanUseChangedEntityAfterModel() {
        AgentConversation snapshot = stubPrepared(OWNER, 7L);
        when(modelService.chatWithMessage(PROMPT_MESSAGES, OWNER)).thenAnswer(invocation -> {
            snapshot.setVersion(8L);
            return ANSWER;
        });
        when(conversations.saveCompletedTurn(ID, 7L, QUESTION, ANSWER, OWNER)).thenReturn(10L);

        assertEquals(10L, service.chat(ID, QUESTION, OWNER).turnNo());

        verify(conversations).saveCompletedTurn(ID, 7L, QUESTION, ANSWER, OWNER);
        verify(conversations, never()).saveCompletedTurn(ID, 8L, QUESTION, ANSWER, OWNER);
    }

    @Test
    void shouldForwardConfiguredHistoryWindow() {
        properties.setTurnLimit(7);
        stubPrepared(OWNER, 0L);
        when(modelService.chatWithMessage(PROMPT_MESSAGES, OWNER)).thenReturn(ANSWER);
        when(conversations.saveCompletedTurn(ID, 0L, QUESTION, ANSWER, OWNER)).thenReturn(1L);

        service.chat(ID, QUESTION, OWNER);

        verify(messages).findRecentOwnedMessages(ID, 1L, 101L, 7);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\n\t"})
    void shouldRejectBlankInputBeforeCreatingOrCallingAnything(String question) {
        assertThrowsExactly(IllegalArgumentException.class, () -> service.chat(null, question, OWNER));
        verifyNoInteractions(conversations, messages, builder, modelService);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(longs = {ID})
    void shouldRejectOverlongInputBeforeAnyCreationReadOrModel(Long conversationId) {
        assertThrowsExactly(IllegalArgumentException.class,
                () -> service.chat(conversationId, "问".repeat(4001), OWNER));
        verifyNoInteractions(conversations, messages, builder, modelService);
    }

    @Test
    void shouldRejectNullIdentityBeforeAnyCreationOrModel() {
        assertThrowsExactly(IllegalArgumentException.class, () -> service.chat(null, QUESTION, null));
        verifyNoInteractions(conversations, messages, builder, modelService);
    }

    @ParameterizedTest
    @ValueSource(longs = {0L, -1L})
    void shouldRejectProvidedInvalidIdRatherThanCreateNewConversation(long conversationId) {
        assertThrowsExactly(IllegalArgumentException.class, () -> service.chat(conversationId, QUESTION, OWNER));
        verifyNoInteractions(conversations, messages, builder, modelService);
    }

    @ParameterizedTest
    @MethodSource("guardFailures")
    void shouldRejectBeforeHistoryOrModelAndNeverFallBackToCreation(RuntimeException failure) {
        when(conversations.requireActiveOwnedConversation(ID, OWNER)).thenThrow(failure);

        assertSame(failure, assertThrows(RuntimeException.class, () -> service.chat(ID, QUESTION, OWNER)));

        verify(conversations).requireActiveOwnedConversation(ID, OWNER);
        verifyNoMoreInteractions(conversations);
        verifyNoInteractions(messages, builder, modelService);
    }

    static Stream<Arguments> guardFailures() {
        return Stream.of(Arguments.of(new ConversationNotAccessibleException("未找到可访问的会话")),
                Arguments.of(new ConversationStateException("会话当前状态不允许继续对话")),
                Arguments.of(new DataAccessResourceFailureException("test database lookup failure")));
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(longs = {-1L})
    void shouldTreatInvalidServerVersionAsInternalStateBeforeModel(Long version) {
        when(conversations.requireActiveOwnedConversation(ID, OWNER)).thenReturn(conversation(version));

        assertThrowsExactly(IllegalStateException.class, () -> service.chat(ID, QUESTION, OWNER));

        verifyNoInteractions(messages, builder, modelService);
    }

    @Test
    void shouldRejectMaximumServerVersionBeforeModel() {
        when(conversations.requireActiveOwnedConversation(ID, OWNER)).thenReturn(conversation(Long.MAX_VALUE));

        assertThrowsExactly(ConversationStateException.class, () -> service.chat(ID, QUESTION, OWNER));

        verifyNoInteractions(messages, builder, modelService);
    }

    @Test
    void shouldStopAfterHistoryDatabaseFailureWithoutModelOrSave() {
        when(conversations.requireActiveOwnedConversation(ID, OWNER)).thenReturn(conversation(0L));
        var failure = new DataAccessResourceFailureException("test history query failure");
        when(messages.findRecentOwnedMessages(ID, 1L, 101L, 5)).thenThrow(failure);

        assertSame(failure, assertThrowsExactly(DataAccessResourceFailureException.class,
                () -> service.chat(ID, QUESTION, OWNER)));

        verifyNoInteractions(builder, modelService);
        verify(conversations).requireActiveOwnedConversation(ID, OWNER);
        verifyNoMoreInteractions(conversations);
    }

    @Test
    void shouldStopAfterHistoryValidationFailureWithoutModelOrSave() {
        when(conversations.requireActiveOwnedConversation(ID, OWNER)).thenReturn(conversation(0L));
        when(messages.findRecentOwnedMessages(ID, 1L, 101L, 5)).thenReturn(HISTORY);
        var failure = new IllegalStateException("test invalid history roles");
        when(builder.build(HISTORY, QUESTION)).thenThrow(failure);

        assertSame(failure, assertThrowsExactly(IllegalStateException.class, () -> service.chat(ID, QUESTION, OWNER)));

        verifyNoInteractions(modelService);
        verify(conversations).requireActiveOwnedConversation(ID, OWNER);
        verifyNoMoreInteractions(conversations);
    }

    @Test
    void shouldLeaveCreatedEmptyConversationWhenFirstModelCallFailsButNeverSaveTurn() {
        when(conversations.createConversation(OWNER)).thenReturn(ID);
        stubPrepared(OWNER, 0L);
        var failure = new AiServiceException("test model failure");
        when(modelService.chatWithMessage(PROMPT_MESSAGES, OWNER)).thenThrow(failure);

        assertSame(failure, assertThrowsExactly(AiServiceException.class, () -> service.chat(null, QUESTION, OWNER)));

        verify(conversations).createConversation(OWNER);
        verify(conversations).requireActiveOwnedConversation(ID, OWNER);
        verifyNoMoreInteractions(conversations);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\n\t"})
    void shouldNeverSaveBlankModelAnswer(String answer) {
        stubPrepared(OWNER, 0L);
        when(modelService.chatWithMessage(PROMPT_MESSAGES, OWNER)).thenReturn(answer);

        assertThrowsExactly(AiServiceException.class, () -> service.chat(ID, QUESTION, OWNER));

        verify(conversations).requireActiveOwnedConversation(ID, OWNER);
        verifyNoMoreInteractions(conversations);
    }

    @Test
    void shouldNeverSaveOverlongModelAnswer() {
        stubPrepared(OWNER, 0L);
        when(modelService.chatWithMessage(PROMPT_MESSAGES, OWNER)).thenReturn("答".repeat(8001));

        assertThrowsExactly(AiServiceException.class, () -> service.chat(ID, QUESTION, OWNER));

        verify(conversations).requireActiveOwnedConversation(ID, OWNER);
        verifyNoMoreInteractions(conversations);
    }

    @Test
    void shouldAllowExactAnswerBoundaryAndPreserveBody() {
        String answer = "答".repeat(8000);
        stubPrepared(OWNER, 0L);
        when(modelService.chatWithMessage(PROMPT_MESSAGES, OWNER)).thenReturn(answer);
        when(conversations.saveCompletedTurn(ID, 0L, QUESTION, answer, OWNER)).thenReturn(1L);

        assertEquals(answer, service.chat(ID, QUESTION, OWNER).answer());
        verify(conversations).saveCompletedTurn(ID, 0L, QUESTION, answer, OWNER);
    }

    @ParameterizedTest
    @MethodSource("saveFailures")
    void shouldPropagateSaveFailureInsteadOfReturningSuccessOrRewritingSnapshot(RuntimeException failure) {
        stubPrepared(OWNER, 7L);
        when(modelService.chatWithMessage(PROMPT_MESSAGES, OWNER)).thenReturn(ANSWER);
        when(conversations.saveCompletedTurn(ID, 7L, QUESTION, ANSWER, OWNER)).thenThrow(failure);

        assertSame(failure, assertThrows(RuntimeException.class, () -> service.chat(ID, QUESTION, OWNER)));

        verify(conversations).requireActiveOwnedConversation(ID, OWNER);
        verify(conversations).saveCompletedTurn(ID, 7L, QUESTION, ANSWER, OWNER);
        verifyNoMoreInteractions(conversations);
        verify(modelService).chatWithMessage(PROMPT_MESSAGES, OWNER);
        verifyNoMoreInteractions(modelService);
    }

    static Stream<Arguments> saveFailures() {
        return Stream.of(Arguments.of(new ConversationConflictException("会话已发生变化，请重新发起对话")),
                Arguments.of(new ConversationStateException("会话当前状态不允许继续对话")),
                Arguments.of(new DataAccessResourceFailureException("test save database failure")));
    }

    private AgentConversation stubPrepared(AgentExecutionContext context, long version) {
        AgentConversation conversation = conversation(version);
        when(conversations.requireActiveOwnedConversation(ID, context)).thenReturn(conversation);
        when(messages.findRecentOwnedMessages(ID, context.tenantId(), context.userId(), properties.getTurnLimit()))
                .thenReturn(HISTORY);
        when(builder.build(HISTORY, QUESTION)).thenReturn(PROMPT_MESSAGES);
        return conversation;
    }

    private static AgentConversation conversation(Long version) {
        AgentConversation conversation = new AgentConversation();
        conversation.setId(ID);
        conversation.setStatus("ACTIVE");
        conversation.setVersion(version);
        return conversation;
    }

    private static AgentExecutionContext context(long userId, long tenantId) {
        return new AgentExecutionContext(userId, tenantId, Set.of("ticket:read", "project:read"));
    }
}
