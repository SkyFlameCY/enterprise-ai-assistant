package cn.coder.sanwei.enterpriseaiassistant;

import cn.coder.sanwei.enterpriseaiassistant.context.AgentExecutionContext;
import cn.coder.sanwei.enterpriseaiassistant.entity.AgentConversation;
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
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;

import java.time.OffsetDateTime;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * 离线运行真实 Service 守卫，Repository 使用 mock。
 * 检查身份参数传递和业务拒绝；不作为真实数据库隔离或 API 授权的证据。
 */
@ExtendWith(MockitoExtension.class)
class ConversationServiceImplTest {

    private static final long CONVERSATION_ID = 77L;

    @Mock
    private JdbcConversationRepository repository;

    @Mock
    private JdbcMessageRepository messageRepository;

    private ConversationService service;

    @BeforeEach
    void setUp() {
        service = new ConversationServiceImpl(repository, messageRepository);
    }

    @ParameterizedTest
    @CsvSource({"101, 1", "104, 1", "101, 2"})
    void shouldReturnActiveConversationUsingCurrentContext(long userId, long tenantId) {
        // 准备：查询条件匹配当前用户和租户，Repository 返回本人 ACTIVE 会话。
        AgentExecutionContext context = context(userId, tenantId);
        AgentConversation conversation = conversation(userId, tenantId, "ACTIVE");
        when(repository.findOwnedConversation(CONVERSATION_ID, tenantId, userId))
                .thenReturn(conversation);

        // 执行：调用真实 Service，而不是把 Service 本身 mock 掉。
        AgentConversation result = service.requireActiveOwnedConversation(CONVERSATION_ID, context);

        // 验证：直接返回该实体，且查询没有把 userId 和 tenantId 传反。
        assertSame(conversation, result);
        verifyOwnedLookup(tenantId, userId);
    }

    @ParameterizedTest
    @ValueSource(longs = {0L, -1L, Long.MIN_VALUE})
    void shouldRejectNonPositiveConversationIdWithoutDatabaseLookup(long conversationId) {
        AgentExecutionContext context = context(101L, 1L);

        assertThrowsExactly(IllegalArgumentException.class,
                () -> service.requireActiveOwnedConversation(conversationId, context));

        verifyNoInteractions(repository);
    }

    @Test
    void shouldRejectNullContextWithoutDatabaseLookup() {
        assertThrowsExactly(IllegalArgumentException.class,
                () -> service.requireActiveOwnedConversation(CONVERSATION_ID, null));

        verifyNoInteractions(repository);
    }

    @ParameterizedTest
    @CsvSource({"101, 1", "104, 1", "101, 2"})
    void shouldUseSameBusinessDenialWhenOwnedLookupReturnsNoConversation(long userId, long tenantId) {
        // Repository 已把不存在和不属于本人统一为 null，Service 不再探查真实归属。
        AgentExecutionContext context = context(userId, tenantId);
        when(repository.findOwnedConversation(CONVERSATION_ID, tenantId, userId))
                .thenReturn(null);

        var failure = assertThrowsExactly(ConversationNotAccessibleException.class,
                () -> service.requireActiveOwnedConversation(CONVERSATION_ID, context));

        assertEquals("未找到可访问的会话", failure.getMessage());
        verifyOwnedLookup(tenantId, userId);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"ARCHIVED", "active", "PAUSED", "ACTIVE "})
    void shouldRejectAnyStatusOtherThanExactActive(String status) {
        // 除 ARCHIVED 外的异常状态用 mock 构造，只用于验证守卫默认拒绝。
        AgentExecutionContext context = context(101L, 1L);
        when(repository.findOwnedConversation(CONVERSATION_ID, 1L, 101L))
                .thenReturn(conversation(101L, 1L, status));

        var failure = assertThrowsExactly(ConversationStateException.class,
                () -> service.requireActiveOwnedConversation(CONVERSATION_ID, context));

        assertEquals("会话当前状态不允许继续对话", failure.getMessage());
        verifyOwnedLookup(1L, 101L);
    }

    @Test
    void shouldPropagateDatabaseFailureInsteadOfReportingMissingConversation() {
        AgentExecutionContext context = context(101L, 1L);
        var databaseFailure = new DataAccessResourceFailureException("test database unavailable");
        when(repository.findOwnedConversation(CONVERSATION_ID, 1L, 101L))
                .thenThrow(databaseFailure);

        var actual = assertThrowsExactly(DataAccessResourceFailureException.class,
                () -> service.requireActiveOwnedConversation(CONVERSATION_ID, context));

        assertSame(databaseFailure, actual);
        verifyOwnedLookup(1L, 101L);
    }

    private void verifyOwnedLookup(long tenantId, long userId) {
        verify(repository).findOwnedConversation(CONVERSATION_ID, tenantId, userId);
        verifyNoMoreInteractions(repository);
    }

    @ParameterizedTest
    @CsvSource({"101, 1", "104, 1", "101, 2"})
    void shouldCreateConversationForTrustedOwnerWithFixedTitle(long userId, long tenantId) {
        when(repository.createConversation(tenantId, userId, "新会话")).thenReturn(CONVERSATION_ID);

        assertEquals(CONVERSATION_ID, service.createConversation(context(userId, tenantId)));

        verify(repository).createConversation(tenantId, userId, "新会话");
        verifyNoMoreInteractions(repository);
        verifyNoInteractions(messageRepository);
    }

    @Test
    void shouldRejectMissingCreationIdentityBeforeDatabase() {
        assertThrowsExactly(IllegalArgumentException.class, () -> service.createConversation(null));
        verifyNoInteractions(repository, messageRepository);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(longs = {0L, -1L})
    void shouldRejectInvalidGeneratedIdAsInternalError(Long generatedId) {
        when(repository.createConversation(1L, 101L, "新会话")).thenReturn(generatedId);

        assertThrowsExactly(IllegalStateException.class, () -> service.createConversation(context(101L, 1L)));
        verifyNoInteractions(messageRepository);
    }

    @Test
    void shouldPropagateCreationDatabaseFailureWithoutWritingMessages() {
        var failure = new DataAccessResourceFailureException("test creation database failure");
        when(repository.createConversation(1L, 101L, "新会话")).thenThrow(failure);

        assertSame(failure, assertThrowsExactly(DataAccessResourceFailureException.class,
                () -> service.createConversation(context(101L, 1L))));
        verifyNoInteractions(messageRepository);
    }

    private static AgentExecutionContext context(long userId, long tenantId) {
        return new AgentExecutionContext(userId, tenantId, Set.of("project:read"));
    }

    private static AgentConversation conversation(long userId, long tenantId, String status) {
        AgentConversation conversation = new AgentConversation();
        conversation.setId(CONVERSATION_ID);
        conversation.setTenantId(tenantId);
        conversation.setUserId(userId);
        conversation.setTitle("项目查询");
        conversation.setStatus(status);
        conversation.setCreatedAt(OffsetDateTime.parse("2026-10-09T10:00:00+08:00"));
        conversation.setUpdatedAt(OffsetDateTime.parse("2026-10-09T10:05:00+08:00"));
        return conversation;
    }
}
