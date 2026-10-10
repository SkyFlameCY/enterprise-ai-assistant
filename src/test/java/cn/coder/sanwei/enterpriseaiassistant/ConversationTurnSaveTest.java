package cn.coder.sanwei.enterpriseaiassistant;

import cn.coder.sanwei.enterpriseaiassistant.context.AgentExecutionContext;
import cn.coder.sanwei.enterpriseaiassistant.entity.AgentConversation;
import cn.coder.sanwei.enterpriseaiassistant.exception.ConversationConflictException;
import cn.coder.sanwei.enterpriseaiassistant.exception.ConversationNotAccessibleException;
import cn.coder.sanwei.enterpriseaiassistant.exception.ConversationStateException;
import cn.coder.sanwei.enterpriseaiassistant.repository.JdbcConversationRepository;
import cn.coder.sanwei.enterpriseaiassistant.repository.JdbcMessageRepository;
import cn.coder.sanwei.enterpriseaiassistant.service.ConversationService;
import cn.coder.sanwei.enterpriseaiassistant.service.impl.ConversationServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;

import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * 准备 Repository 返回值—执行真实 Service—验证调用顺序/参数及失败行为。
 * 本类离线，不调用模型；mock 不能证明数据库事务回滚，回滚由 ConversationServiceIT 验证。
 */
@ExtendWith(MockitoExtension.class)
class ConversationTurnSaveTest {

    private static final long ID = 77L;
    private static final String QUESTION = "查询项目";
    private static final String ANSWER = "项目状态 ACTIVE";
    private static final AgentExecutionContext OWNER = context(101L, 1L);

    @Mock
    private JdbcConversationRepository conversations;
    @Mock
    private JdbcMessageRepository messages;

    private ConversationService service;

    @BeforeEach
    void setUp() {
        service = new ConversationServiceImpl(conversations, messages);
    }

    @ParameterizedTest
    @CsvSource({"101, 1", "104, 1", "101, 2"})
    void shouldSaveInGuardUpdateMaxInsertOrderUsingTrustedIdentity(long userId, long tenantId) {
        AgentExecutionContext context = context(userId, tenantId);
        stubActive(context, 0L);
        when(conversations.advanceVersionIfActiveOwned(ID, tenantId, userId, 0L)).thenReturn(1);
        when(messages.findMaxOwnedTurnNo(ID, tenantId, userId)).thenReturn(0L);
        when(messages.insertTurn(ID, 1L, QUESTION, ANSWER)).thenReturn(2);

        assertEquals(1L, service.saveCompletedTurn(ID, 0L, QUESTION, ANSWER, context));

        var order = inOrder(conversations, messages);
        order.verify(conversations).findOwnedConversation(ID, tenantId, userId);
        order.verify(conversations).advanceVersionIfActiveOwned(ID, tenantId, userId, 0L);
        order.verify(messages).findMaxOwnedTurnNo(ID, tenantId, userId);
        order.verify(messages).insertTurn(ID, 1L, QUESTION, ANSWER);
        verifyNoMoreInteractions(conversations, messages);
    }

    @ParameterizedTest
    @ValueSource(longs = {0L, 7L, 9L, 2147483648L, Long.MAX_VALUE - 1})
    void shouldAllocateAfterActualMaximumRatherThanConversationVersionOrMessageCount(long maxTurnNo) {
        stubActive(OWNER, 0L);
        when(conversations.advanceVersionIfActiveOwned(ID, 1L, 101L, 0L)).thenReturn(1);
        when(messages.findMaxOwnedTurnNo(ID, 1L, 101L)).thenReturn(maxTurnNo);
        when(messages.insertTurn(ID, maxTurnNo + 1, QUESTION, ANSWER)).thenReturn(2);

        assertEquals(maxTurnNo + 1, service.saveCompletedTurn(ID, 0L, QUESTION, ANSWER, OWNER));

        verify(messages).insertTurn(ID, maxTurnNo + 1, QUESTION, ANSWER);
    }

    @ParameterizedTest
    @ValueSource(longs = {0L, 7L, 2147483648L, Long.MAX_VALUE - 1})
    void shouldKeepSuppliedSnapshotVersionEvenWhenSaveTimeLookupHasDifferentVersion(long expectedVersion) {
        // 保存时查询版本已改变，也不能把调用者的旧快照版本偷偷替换为最新版本。
        stubActive(OWNER, expectedVersion + 1);
        when(conversations.advanceVersionIfActiveOwned(ID, 1L, 101L, expectedVersion)).thenReturn(0);

        var failure = assertThrowsExactly(ConversationConflictException.class,
                () -> service.saveCompletedTurn(ID, expectedVersion, QUESTION, ANSWER, OWNER));

        assertEquals("会话已发生变化，请重新发起对话", failure.getMessage());
        verify(conversations).advanceVersionIfActiveOwned(ID, 1L, 101L, expectedVersion);
        verifyNoInteractions(messages);
    }

    @Test
    void shouldPreserveNonBlankContentWithoutTrimmingOrChangingQuotesAndNewlines() {
        String question = "  问题'); DELETE FROM agent_message; --\n继续查询  ";
        String answer = "\t回答包含 '引号'\n中文正文  ";
        stubActive(OWNER, 0L);
        when(conversations.advanceVersionIfActiveOwned(ID, 1L, 101L, 0L)).thenReturn(1);
        when(messages.findMaxOwnedTurnNo(ID, 1L, 101L)).thenReturn(0L);
        when(messages.insertTurn(ID, 1L, question, answer)).thenReturn(2);

        assertEquals(1L, service.saveCompletedTurn(ID, 0L, question, answer, OWNER));

        verify(messages).insertTurn(ID, 1L, question, answer);
    }

    @ParameterizedTest
    @ValueSource(longs = {0L, -1L, Long.MIN_VALUE})
    void shouldRejectInvalidConversationIdBeforeAnyRepositoryCall(long conversationId) {
        assertThrowsExactly(IllegalArgumentException.class,
                () -> service.saveCompletedTurn(conversationId, 0L, QUESTION, ANSWER, OWNER));

        verifyNoInteractions(conversations, messages);
    }

    @ParameterizedTest
    @ValueSource(longs = {-1L, Long.MIN_VALUE, Long.MAX_VALUE})
    void shouldRejectInvalidVersionBeforeAnyRepositoryCall(long expectedVersion) {
        assertThrowsExactly(IllegalArgumentException.class,
                () -> service.saveCompletedTurn(ID, expectedVersion, QUESTION, ANSWER, OWNER));

        verifyNoInteractions(conversations, messages);
    }

    @Test
    void shouldRejectMissingIdentityBeforeAnyRepositoryCall() {
        assertThrowsExactly(IllegalArgumentException.class,
                () -> service.saveCompletedTurn(ID, 0L, QUESTION, ANSWER, null));

        verifyNoInteractions(conversations, messages);
    }

    @ParameterizedTest
    @MethodSource("invalidContent")
    void shouldRejectEitherBlankBodyBeforeAnyRepositoryCall(String question, String answer) {
        assertThrowsExactly(IllegalArgumentException.class,
                () -> service.saveCompletedTurn(ID, 0L, question, answer, OWNER));

        verifyNoInteractions(conversations, messages);
    }

    static Stream<Arguments> invalidContent() {
        return Stream.of(null, "", " ", "\n\t\r", "\u3000")
                .flatMap(blank -> Stream.of(Arguments.of(blank, ANSWER), Arguments.of(QUESTION, blank)));
    }

    @ParameterizedTest
    @CsvSource({"101, 1", "104, 1", "101, 2"})
    void shouldDenyUnavailableConversationWithoutVersionUpdateOrMessages(long userId, long tenantId) {
        AgentExecutionContext context = context(userId, tenantId);
        when(conversations.findOwnedConversation(ID, tenantId, userId)).thenReturn(null);

        var failure = assertThrowsExactly(ConversationNotAccessibleException.class,
                () -> service.saveCompletedTurn(ID, 0L, QUESTION, ANSWER, context));

        assertEquals("未找到可访问的会话", failure.getMessage());
        verify(conversations).findOwnedConversation(ID, tenantId, userId);
        verifyNoMoreInteractions(conversations);
        verifyNoInteractions(messages);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"ARCHIVED", "active", "PAUSED", "ACTIVE "})
    void shouldDenyNonActiveConversationWithoutVersionUpdateOrMessages(String status) {
        AgentConversation conversation = conversation(OWNER, 0L);
        conversation.setStatus(status);
        when(conversations.findOwnedConversation(ID, 1L, 101L)).thenReturn(conversation);

        assertThrowsExactly(ConversationStateException.class,
                () -> service.saveCompletedTurn(ID, 0L, QUESTION, ANSWER, OWNER));

        verify(conversations).findOwnedConversation(ID, 1L, 101L);
        verifyNoMoreInteractions(conversations);
        verifyNoInteractions(messages);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, 2})
    void shouldFailClosedUnlessExactlyOneConversationIsAdvanced(int affectedRows) {
        stubActive(OWNER, 0L);
        when(conversations.advanceVersionIfActiveOwned(ID, 1L, 101L, 0L)).thenReturn(affectedRows);

        assertThrowsExactly(ConversationConflictException.class,
                () -> service.saveCompletedTurn(ID, 0L, QUESTION, ANSWER, OWNER));

        verifyNoInteractions(messages);
    }

    @ParameterizedTest
    @ValueSource(strings = {"lookup", "update", "max", "insert"})
    void shouldPropagateDatabaseFailureAtEveryStageInsteadOfPretendingSaveSucceeded(String stage) {
        var databaseFailure = new DataAccessResourceFailureException("test database unavailable");
        if (stage.equals("lookup")) {
            when(conversations.findOwnedConversation(ID, 1L, 101L)).thenThrow(databaseFailure);
        } else {
            stubActive(OWNER, 0L);
            if (stage.equals("update")) {
                when(conversations.advanceVersionIfActiveOwned(ID, 1L, 101L, 0L)).thenThrow(databaseFailure);
            } else {
                when(conversations.advanceVersionIfActiveOwned(ID, 1L, 101L, 0L)).thenReturn(1);
                if (stage.equals("max")) {
                    when(messages.findMaxOwnedTurnNo(ID, 1L, 101L)).thenThrow(databaseFailure);
                } else {
                    when(messages.findMaxOwnedTurnNo(ID, 1L, 101L)).thenReturn(0L);
                    when(messages.insertTurn(ID, 1L, QUESTION, ANSWER)).thenThrow(databaseFailure);
                }
            }
        }

        var actual = assertThrowsExactly(DataAccessResourceFailureException.class,
                () -> service.saveCompletedTurn(ID, 0L, QUESTION, ANSWER, OWNER));

        assertSame(databaseFailure, actual);
        if (stage.equals("lookup") || stage.equals("update")) {
            verifyNoInteractions(messages);
        } else if (stage.equals("max")) {
            verify(messages).findMaxOwnedTurnNo(ID, 1L, 101L);
            verifyNoMoreInteractions(messages);
        }
    }

    @Test
    void shouldRejectTurnNumberOverflowWithoutAttemptingInsert() {
        stubActive(OWNER, 0L);
        when(conversations.advanceVersionIfActiveOwned(ID, 1L, 101L, 0L)).thenReturn(1);
        when(messages.findMaxOwnedTurnNo(ID, 1L, 101L)).thenReturn(Long.MAX_VALUE);

        var failure = assertThrowsExactly(ConversationStateException.class,
                () -> service.saveCompletedTurn(ID, 0L, QUESTION, ANSWER, OWNER));

        assertEquals("会话轮次已达上限，无法继续保存", failure.getMessage());
        verify(messages).findMaxOwnedTurnNo(ID, 1L, 101L);
        verifyNoMoreInteractions(messages);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 3})
    void shouldRejectUnexpectedInsertedRowCountRatherThanReturningSuccess(int insertedRows) {
        stubActive(OWNER, 0L);
        when(conversations.advanceVersionIfActiveOwned(ID, 1L, 101L, 0L)).thenReturn(1);
        when(messages.findMaxOwnedTurnNo(ID, 1L, 101L)).thenReturn(0L);
        when(messages.insertTurn(ID, 1L, QUESTION, ANSWER)).thenReturn(insertedRows);

        var failure = assertThrowsExactly(IllegalStateException.class,
                () -> service.saveCompletedTurn(ID, 0L, QUESTION, ANSWER, OWNER));

        assertEquals("整轮消息写入数量异常", failure.getMessage());
    }

    @Test
    void shouldNotMislabelInsertArithmeticFailureAsTurnNumberOverflow() {
        stubActive(OWNER, 0L);
        when(conversations.advanceVersionIfActiveOwned(ID, 1L, 101L, 0L)).thenReturn(1);
        when(messages.findMaxOwnedTurnNo(ID, 1L, 101L)).thenReturn(0L);
        var insertFailure = new ArithmeticException("test insert-stage failure, not turn overflow");
        when(messages.insertTurn(ID, 1L, QUESTION, ANSWER)).thenThrow(insertFailure);

        var actual = assertThrowsExactly(ArithmeticException.class,
                () -> service.saveCompletedTurn(ID, 0L, QUESTION, ANSWER, OWNER));

        assertSame(insertFailure, actual);
    }

    private void stubActive(AgentExecutionContext context, long version) {
        when(conversations.findOwnedConversation(ID, context.tenantId(), context.userId()))
                .thenReturn(conversation(context, version));
    }

    private static AgentConversation conversation(AgentExecutionContext context, long version) {
        AgentConversation conversation = new AgentConversation();
        conversation.setId(ID);
        conversation.setTenantId(context.tenantId());
        conversation.setUserId(context.userId());
        conversation.setStatus("ACTIVE");
        conversation.setVersion(version);
        return conversation;
    }

    private static AgentExecutionContext context(long userId, long tenantId) {
        return new AgentExecutionContext(userId, tenantId, Set.of("project:read"));
    }
}
