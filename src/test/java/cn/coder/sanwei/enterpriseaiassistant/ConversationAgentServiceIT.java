package cn.coder.sanwei.enterpriseaiassistant;

import cn.coder.sanwei.enterpriseaiassistant.config.AgentMemoryProperties;
import cn.coder.sanwei.enterpriseaiassistant.context.AgentExecutionContext;
import cn.coder.sanwei.enterpriseaiassistant.dto.AgentChatResponse;
import cn.coder.sanwei.enterpriseaiassistant.exception.AiServiceException;
import cn.coder.sanwei.enterpriseaiassistant.exception.ConversationConflictException;
import cn.coder.sanwei.enterpriseaiassistant.exception.ConversationNotAccessibleException;
import cn.coder.sanwei.enterpriseaiassistant.exception.ConversationStateException;
import cn.coder.sanwei.enterpriseaiassistant.memory.ConversationHistoryBuilder;
import cn.coder.sanwei.enterpriseaiassistant.repository.JdbcConversationRepository;
import cn.coder.sanwei.enterpriseaiassistant.repository.JdbcMessageRepository;
import cn.coder.sanwei.enterpriseaiassistant.service.AgentService;
import cn.coder.sanwei.enterpriseaiassistant.service.ConversationAgentService;
import cn.coder.sanwei.enterpriseaiassistant.service.ConversationService;
import cn.coder.sanwei.enterpriseaiassistant.service.impl.ConversationAgentServiceImpl;
import cn.coder.sanwei.enterpriseaiassistant.service.impl.ConversationServiceImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

/**
 * 专用临时 PostgreSQL + 真实会话编排、历史组装、Repository 和 Spring 短事务代理。
 * 只模拟 AgentService 的模型边界，不调用真实模型或工具、不连接开发库。
 * 无外层测试事务；不等同于 HTTP、真实模型指代理解或应用重启验收。
 */
@JdbcTest(properties = {
        "spring.config.import=",
        "spring.liquibase.enabled=true",
        "spring.liquibase.drop-first=false",
        "spring.liquibase.change-log=classpath:/db/changelog/db.changelog-master.xml",
        "spring.ai.deepseek.api-key=test-key",
        "spring.jdbc.template.query-timeout=5s"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("conversation-agent-service-test")
@Import({ConversationAgentServiceImpl.class, ConversationServiceImpl.class,
        JdbcConversationRepository.class, JdbcMessageRepository.class, ConversationHistoryBuilder.class,
        ConversationAgentServiceIT.MemoryConfiguration.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Testcontainers
@Execution(ExecutionMode.SAME_THREAD)
class ConversationAgentServiceIT {

    private static final OffsetDateTime OLD_TIME = OffsetDateTime.parse("2000-01-01T00:00:00Z");
    private static final AgentExecutionContext OWNER = context(101L, 1L);

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:0.8.6-pg17-bookworm")
                    .asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("conversation_agent_service_test")
            .withUsername("test_user")
            .withPassword("test_password");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @TestConfiguration(proxyBeanMethods = false)
    @EnableConfigurationProperties(AgentMemoryProperties.class)
    static class MemoryConfiguration {
    }

    @Autowired
    private ConversationAgentService service;
    @Autowired
    private ConversationService conversationService;
    @Autowired
    private JdbcMessageRepository messages;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private AgentMemoryProperties properties;
    @MockitoBean
    private AgentService modelService;

    private final List<Long> createdConversations = new ArrayList<>();

    private record ConversationRow(long id, long tenantId, long userId, String title, String status,
                                   long version, OffsetDateTime createdAt, OffsetDateTime updatedAt) {
    }

    private record MessageRow(long id, long conversationId, long turnNo, String role,
                              String content, OffsetDateTime createdAt) {
    }

    private record Snapshot(ConversationRow conversation, List<MessageRow> messages) {
    }

    private record ChatOutcome(AgentChatResponse response, RuntimeException failure) {
    }

    @BeforeEach
    void requireRealShortTransactionAndResetBudgets() {
        assertTrue(AopUtils.isAopProxy(conversationService), "保存必须通过 Spring 的事务代理");
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive(), "不能用外层事务掩盖回滚");
        assertEquals(Boolean.TRUE, jdbcTemplate.execute(
                (ConnectionCallback<Boolean>) connection -> connection.getAutoCommit()));
        assertEquals(5, jdbcTemplate.getQueryTimeout(), "数据库锁等待必须有界");
        properties.setTurnLimit(5);
        properties.setMaxContextChars(16000);
        properties.setMaxAssistantChars(8000);
        reset(modelService);
    }

    @AfterEach
    void cleanOnlyThisTestsFixtures() {
        for (long id : createdConversations) {
            jdbcTemplate.update("DELETE FROM agent_message WHERE conversation_id = ?", id);
            jdbcTemplate.update("DELETE FROM agent_conversation WHERE id = ?", id);
        }
    }

    @Test
    void shouldCreateOwnConversationWithoutImportingAnotherConversationsHistory() {
        long previousId = createFixture();
        messages.insertTurn(previousId, 1L, "另一个会话的问题", "另一个会话的回答");
        Snapshot previous = snapshot(previousId);
        AtomicReference<List<Message>> sent = prepareAnswer("项目 ACTIVE");

        AgentChatResponse response = chatNew("查询 EA-2026");

        assertNotEquals(previousId, response.conversationId());
        assertEquals(1L, response.turnNo());
        assertEquals("项目 ACTIVE", response.answer());
        assertInputs(sent.get(), List.of(MessageType.USER), List.of("查询 EA-2026"));
        Snapshot saved = snapshot(response.conversationId());
        assertEquals(OWNER.tenantId(), saved.conversation().tenantId());
        assertEquals(OWNER.userId(), saved.conversation().userId());
        assertEquals("新会话", saved.conversation().title());
        assertEquals("ACTIVE", saved.conversation().status());
        assertEquals(1L, saved.conversation().version());
        assertTurn(saved.messages(), response.conversationId(), 1L, "查询 EA-2026", "项目 ACTIVE");
        assertEquals(previous, snapshot(previousId));
    }

    @Test
    void shouldReloadCommittedHistoryForNextTurnAndSaveOnlyCurrentPair() {
        long id = createFixture();
        Snapshot before = snapshot(id);
        prepareAnswer("EA-2026 项目 ACTIVE");
        assertEquals(new AgentChatResponse(id, 1L, "EA-2026 项目 ACTIVE"),
                service.chat(id, "查询 EA-2026", OWNER));
        Snapshot first = snapshot(id);
        assertAppended(before, first, 1L, 1L, "查询 EA-2026", "EA-2026 项目 ACTIVE");
        AtomicReference<List<Message>> sent = prepareAnswer("本次查询有 1 条 OPEN 工单");

        assertEquals(new AgentChatResponse(id, 2L, "本次查询有 1 条 OPEN 工单"),
                service.chat(id, "它有哪些未处理工单？", OWNER));

        assertInputs(sent.get(), List.of(MessageType.USER, MessageType.ASSISTANT, MessageType.USER),
                List.of("查询 EA-2026", "EA-2026 项目 ACTIVE", "它有哪些未处理工单？"));
        assertAppended(first, snapshot(id), 2L, 2L, "它有哪些未处理工单？", "本次查询有 1 条 OPEN 工单");
        verify(modelService, times(2)).chatWithMessage(anyList(), any(AgentExecutionContext.class));
    }

    @ParameterizedTest
    @CsvSource({"104, 1", "101, 2", "102, 2"})
    void shouldRejectForeignConversationBeforeModelAndLeaveAllFieldsUnchanged(long userId, long tenantId) {
        long id = createFixture();
        messages.insertTurn(id, 1L, "本人问题", "本人回答");
        Snapshot before = snapshot(id);

        var failure = assertThrowsExactly(ConversationNotAccessibleException.class,
                () -> service.chat(id, "继续查询", context(userId, tenantId)));

        assertEquals("未找到可访问的会话", failure.getMessage());
        assertEquals(before, snapshot(id));
        verifyNoInteractions(modelService);
    }

    @Test
    void shouldRejectArchivedConversationBeforeModelWithoutCreatingReplacement() {
        long id = createFixture();
        jdbcTemplate.update("UPDATE agent_conversation SET status = 'ARCHIVED' WHERE id = ?", id);
        Snapshot before = snapshot(id);
        long count = conversationCount();

        assertThrowsExactly(ConversationStateException.class, () -> service.chat(id, "继续查询", OWNER));

        assertEquals(before, snapshot(id));
        assertEquals(count, conversationCount());
        verifyNoInteractions(modelService);
    }

    @Test
    void shouldRejectMissingIdBeforeModelWithoutCreatingReplacement() {
        long id = createFixture();
        Snapshot before = snapshot(id);
        long count = conversationCount();
        assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM agent_conversation WHERE id = ?", Integer.class, Long.MAX_VALUE));

        var failure = assertThrowsExactly(ConversationNotAccessibleException.class,
                () -> service.chat(Long.MAX_VALUE, "继续查询", OWNER));

        assertEquals("未找到可访问的会话", failure.getMessage());
        assertEquals(count, conversationCount());
        assertEquals(before, snapshot(id));
        verifyNoInteractions(modelService);
    }

    @ParameterizedTest
    @ValueSource(strings = {"exception", "blank", "overlong"})
    void shouldLeaveExistingHistoryVersionAndTimestampUnchangedWhenModelFails(String mode) {
        long id = createFixture();
        messages.insertTurn(id, 1L, "旧问题", "旧回答");
        Snapshot before = snapshot(id);
        doAnswer(invocation -> {
            assertModelOutsideTransaction(invocation.getArgument(1));
            return switch (mode) {
                case "exception" -> throw new AiServiceException("模拟模型调用失败");
                case "blank" -> "\n\t";
                case "overlong" -> "答".repeat(properties.getMaxAssistantChars() + 1);
                default -> throw new AssertionError("未知测试场景");
            };
        }).when(modelService).chatWithMessage(anyList(), any(AgentExecutionContext.class));

        assertThrowsExactly(AiServiceException.class, () -> service.chat(id, "新问题", OWNER));

        assertEquals(before, snapshot(id));
        verify(modelService).chatWithMessage(anyList(), any(AgentExecutionContext.class));
    }

    @Test
    void shouldLeaveAnOwnedEmptyConversationWhenFirstModelCallFails() {
        long count = conversationCount();
        doAnswer(invocation -> {
            assertModelOutsideTransaction(invocation.getArgument(1));
            throw new AiServiceException("模拟首轮模型失败");
        }).when(modelService).chatWithMessage(anyList(), any(AgentExecutionContext.class));

        assertThrowsExactly(AiServiceException.class, () -> chatNew("首轮问题"));

        assertEquals(count + 1, conversationCount(), "首版策略允许留下空会话，不声称幂等");
        long id = createdConversations.getLast();
        Snapshot saved = snapshot(id);
        assertEquals(OWNER.tenantId(), saved.conversation().tenantId());
        assertEquals(OWNER.userId(), saved.conversation().userId());
        assertEquals("ACTIVE", saved.conversation().status());
        assertEquals(0L, saved.conversation().version());
        assertTrue(saved.messages().isEmpty());
        assertEquals(saved.conversation().createdAt(), saved.conversation().updatedAt());
    }

    @Test
    void shouldUseConfiguredTurnWindowWithoutDeletingOlderStoredMessages() {
        long id = createFixture();
        for (long turn = 1; turn <= 4; turn++) {
            messages.insertTurn(id, turn, "问题" + turn, "回答" + turn);
        }
        Snapshot before = snapshot(id);
        properties.setTurnLimit(2);
        AtomicReference<List<Message>> sent = prepareAnswer("回答5");

        assertEquals(new AgentChatResponse(id, 5L, "回答5"), service.chat(id, "问题5", OWNER));

        assertInputs(sent.get(), List.of(MessageType.USER, MessageType.ASSISTANT,
                        MessageType.USER, MessageType.ASSISTANT, MessageType.USER),
                List.of("问题3", "回答3", "问题4", "回答4", "问题5"));
        assertAppended(before, snapshot(id), 1L, 5L, "问题5", "回答5");
    }

    @Test
    void shouldApplyCharacterBudgetToWholeLatestPairAndCurrentInput() {
        long id = createFixture();
        messages.insertTurn(id, 1L, "旧".repeat(1500), "旧答".repeat(750));
        String latestQuestion = "近".repeat(1500);
        String latestAnswer = "答".repeat(1500);
        messages.insertTurn(id, 2L, latestQuestion, latestAnswer);
        Snapshot before = snapshot(id);
        properties.setMaxContextChars(4000);
        String current = "问".repeat(1000);
        AtomicReference<List<Message>> sent = prepareAnswer("本次回答");

        assertEquals(3L, service.chat(id, current, OWNER).turnNo());

        assertInputs(sent.get(), List.of(MessageType.USER, MessageType.ASSISTANT, MessageType.USER),
                List.of(latestQuestion, latestAnswer, current));
        assertEquals(4000L, sent.get().stream().mapToLong(item -> item.getText().length()).sum());
        assertAppended(before, snapshot(id), 1L, 3L, current, "本次回答");
    }

    @Test
    void shouldNotSkipOversizedLatestPairToPickAnOlderPairThatFits() {
        long id = createFixture();
        messages.insertTurn(id, 1L, "较早小问题", "较早小回答");
        messages.insertTurn(id, 2L, "近".repeat(2000), "答".repeat(2000));
        Snapshot before = snapshot(id);
        properties.setMaxContextChars(4000);
        AtomicReference<List<Message>> sent = prepareAnswer("请明确项目编号");

        assertEquals(3L, service.chat(id, "它的工单？", OWNER).turnNo());

        assertInputs(sent.get(), List.of(MessageType.USER), List.of("它的工单？"));
        assertAppended(before, snapshot(id), 1L, 3L, "它的工单？", "请明确项目编号");
    }

    @Test
    void shouldPropagateRealSaveFailureAndRollbackVersionTimestampAndBothNewRows() {
        long id = createFixture();
        // 保留旧半轮，同时令本次 ASSISTANT 真正违反临时测试约束。
        jdbcTemplate.update("""
                INSERT INTO agent_message (conversation_id, turn_no, role, content)
                VALUES (?, 3, 'USER', '原有半轮')
                """, id);
        Snapshot before = snapshot(id);
        AtomicReference<List<Message>> sent = prepareAnswer("模型已成功回答");
        String constraint = "test_agent_reject_assistant_" + id;
        jdbcTemplate.execute("ALTER TABLE agent_message ADD CONSTRAINT " + constraint
                + " CHECK (conversation_id <> " + id + " OR role <> 'ASSISTANT')");
        try {
            assertThrows(DataIntegrityViolationException.class, () -> service.chat(id, "新问题", OWNER));

            assertInputs(sent.get(), List.of(MessageType.USER), List.of("新问题"));
            assertEquals(before, snapshot(id), "保存失败不能返回成功，也不能留下新版本、新更新时间或半轮");
            verify(modelService).chatWithMessage(anyList(), any(AgentExecutionContext.class));
        } finally {
            jdbcTemplate.execute("ALTER TABLE agent_message DROP CONSTRAINT " + constraint);
        }
    }

    @Test
    void shouldRejectOneOfTwoAnswersGeneratedFromTheSamePreModelSnapshot() throws Exception {
        long id = createFixture();
        Snapshot before = snapshot(id);
        CountDownLatch bothInModel = new CountDownLatch(2);
        CountDownLatch releaseAnswers = new CountDownLatch(1);
        doAnswer(invocation -> {
            assertModelOutsideTransaction(invocation.getArgument(1));
            List<Message> input = invocation.getArgument(0);
            assertEquals(1, input.size(), "两次调用都应在空历史和 version=0 下进入模型");
            assertEquals(MessageType.USER, input.getFirst().getMessageType());
            bothInModel.countDown();
            assertTrue(releaseAnswers.await(5, TimeUnit.SECONDS), "模拟模型等待必须有界");
            return input.getFirst().getText().substring(0, 1) + "回答";
        }).when(modelService).chatWithMessage(anyList(), any(AgentExecutionContext.class));
        var executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> concurrentChat(id, "A问题"));
            var second = executor.submit(() -> concurrentChat(id, "B问题"));
            assertTrue(bothInModel.await(5, TimeUnit.SECONDS), "两次调用先读取同一版本，再一起释放模型回答");
            releaseAnswers.countDown();
            List<ChatOutcome> outcomes = List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));

            assertEquals(1L, outcomes.stream().filter(item -> item.failure() == null).count());
            ChatOutcome success = outcomes.stream().filter(item -> item.failure() == null).findFirst().orElseThrow();
            ChatOutcome denied = outcomes.stream().filter(item -> item.failure() != null).findFirst().orElseThrow();
            assertInstanceOf(ConversationConflictException.class, denied.failure());
            assertNull(denied.response(), "冲突的旧历史回答不得作为成功返回");
            assertEquals(id, success.response().conversationId());
            assertEquals(1L, success.response().turnNo());
            String winner = success.response().answer().substring(0, 1);
            assertTrue(Set.of("A", "B").contains(winner));
            assertAppended(before, snapshot(id), 1L, 1L, winner + "问题", winner + "回答");
            verify(modelService, times(2)).chatWithMessage(anyList(), any(AgentExecutionContext.class));
        } finally {
            releaseAnswers.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS), "线程退出后才可清理数据库夹具");
        }
    }

    private AtomicReference<List<Message>> prepareAnswer(String answer) {
        AtomicReference<List<Message>> captured = new AtomicReference<>();
        doAnswer(invocation -> {
            assertModelOutsideTransaction(invocation.getArgument(1));
            List<Message> input = invocation.getArgument(0);
            captured.set(List.copyOf(input));
            return answer;
        }).when(modelService).chatWithMessage(anyList(), any(AgentExecutionContext.class));
        return captured;
    }

    private static void assertModelOutsideTransaction(AgentExecutionContext actual) {
        assertSame(OWNER, actual, "模型仍使用本次服务端身份，不从历史恢复身份");
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive(), "等待模型不能占用保存事务");
    }

    private ChatOutcome concurrentChat(long id, String question) {
        try {
            return new ChatOutcome(service.chat(id, question, OWNER), null);
        } catch (RuntimeException failure) {
            return new ChatOutcome(null, failure);
        }
    }

    private long createFixture() {
        Long id = jdbcTemplate.queryForObject("""
                INSERT INTO agent_conversation (tenant_id, user_id, title, updated_at)
                VALUES (?, ?, '编排测试会话', ?) RETURNING id
                """, Long.class, OWNER.tenantId(), OWNER.userId(), OLD_TIME);
        assertNotNull(id);
        assertTrue(id > 0);
        createdConversations.add(id);
        return id;
    }

    private AgentChatResponse chatNew(String question) {
        Long previousMax = jdbcTemplate.queryForObject(
                "SELECT COALESCE(MAX(id), 0) FROM agent_conversation", Long.class);
        assertNotNull(previousMax);
        try {
            return service.chat(null, question, OWNER);
        } finally {
            // 专用容器、串行用例：失败首轮也只登记本次生成的本人会话供清理。
            createdConversations.addAll(jdbcTemplate.queryForList("""
                    SELECT id FROM agent_conversation
                    WHERE id > ? AND tenant_id = ? AND user_id = ? ORDER BY id
                    """, Long.class, previousMax, OWNER.tenantId(), OWNER.userId()));
        }
    }

    private long conversationCount() {
        Long count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM agent_conversation", Long.class);
        assertNotNull(count);
        return count;
    }

    private Snapshot snapshot(long id) {
        ConversationRow conversation = jdbcTemplate.queryForObject("""
                SELECT id, tenant_id, user_id, title, status, version, created_at, updated_at
                FROM agent_conversation WHERE id = ?
                """, (rs, rowNum) -> new ConversationRow(rs.getLong("id"), rs.getLong("tenant_id"),
                rs.getLong("user_id"), rs.getString("title"), rs.getString("status"), rs.getLong("version"),
                rs.getObject("created_at", OffsetDateTime.class), rs.getObject("updated_at", OffsetDateTime.class)), id);
        List<MessageRow> rows = jdbcTemplate.query("""
                SELECT id, conversation_id, turn_no, role, content, created_at
                FROM agent_message WHERE conversation_id = ?
                ORDER BY turn_no, CASE role WHEN 'USER' THEN 0 ELSE 1 END
                """, (rs, rowNum) -> new MessageRow(rs.getLong("id"), rs.getLong("conversation_id"),
                rs.getLong("turn_no"), rs.getString("role"), rs.getString("content"),
                rs.getObject("created_at", OffsetDateTime.class)), id);
        assertNotNull(conversation);
        return new Snapshot(conversation, rows);
    }

    private static void assertAppended(Snapshot before, Snapshot after, long version, long turn,
                                       String question, String answer) {
        ConversationRow old = before.conversation();
        ConversationRow saved = after.conversation();
        assertEquals(old.id(), saved.id());
        assertEquals(old.tenantId(), saved.tenantId());
        assertEquals(old.userId(), saved.userId());
        assertEquals(old.title(), saved.title());
        assertEquals(old.status(), saved.status());
        assertEquals(old.createdAt(), saved.createdAt());
        assertEquals(version, saved.version());
        assertTrue(saved.updatedAt().toInstant().isAfter(old.updatedAt().toInstant()));
        assertEquals(before.messages().size() + 2, after.messages().size());
        assertEquals(before.messages(), after.messages().subList(0, before.messages().size()));
        List<MessageRow> appended = after.messages().subList(before.messages().size(), after.messages().size());
        assertTurn(appended, old.id(), turn, question, answer);
        appended.forEach(row -> assertEquals(saved.updatedAt().toInstant(), row.createdAt().toInstant()));
    }

    private static void assertTurn(List<MessageRow> rows, long id, long turn, String question, String answer) {
        assertEquals(2, rows.size());
        assertEquals(List.of("USER", "ASSISTANT"), rows.stream().map(MessageRow::role).toList());
        assertEquals(List.of(question, answer), rows.stream().map(MessageRow::content).toList());
        assertNotEquals(rows.getFirst().id(), rows.getLast().id());
        rows.forEach(row -> {
            assertTrue(row.id() > 0);
            assertEquals(id, row.conversationId());
            assertEquals(turn, row.turnNo());
            assertNotNull(row.createdAt());
        });
    }

    private static void assertInputs(List<Message> actual, List<MessageType> roles, List<String> bodies) {
        assertNotNull(actual);
        assertEquals(roles, actual.stream().map(Message::getMessageType).toList());
        assertEquals(bodies, actual.stream().map(Message::getText).toList());
    }

    private static AgentExecutionContext context(long userId, long tenantId) {
        return new AgentExecutionContext(userId, tenantId, Set.of("project:read", "ticket:read"));
    }
}
