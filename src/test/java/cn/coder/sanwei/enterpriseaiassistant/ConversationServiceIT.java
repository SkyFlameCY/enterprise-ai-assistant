package cn.coder.sanwei.enterpriseaiassistant;

import cn.coder.sanwei.enterpriseaiassistant.context.AgentExecutionContext;
import cn.coder.sanwei.enterpriseaiassistant.exception.ConversationConflictException;
import cn.coder.sanwei.enterpriseaiassistant.exception.ConversationNotAccessibleException;
import cn.coder.sanwei.enterpriseaiassistant.exception.ConversationStateException;
import cn.coder.sanwei.enterpriseaiassistant.repository.JdbcConversationRepository;
import cn.coder.sanwei.enterpriseaiassistant.repository.JdbcMessageRepository;
import cn.coder.sanwei.enterpriseaiassistant.service.ConversationService;
import cn.coder.sanwei.enterpriseaiassistant.service.impl.ConversationServiceImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
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
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 专用临时 PostgreSQL + 真实 Spring Service 代理 + 真实 Repository SQL。
 * 无外层测试事务，失败后立即从数据库核验版本、更新时间和消息均回滚。
 * 不连接开发库、不调用模型；不代表会话 API、多轮记忆或请求幂等已完成。
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
@ActiveProfiles("conversation-service-test")
@Import({ConversationServiceImpl.class, JdbcConversationRepository.class, JdbcMessageRepository.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Testcontainers
class ConversationServiceIT {

    private static final OffsetDateTime OLD_TIME = OffsetDateTime.parse("2000-01-01T00:00:00Z");
    private static final AgentExecutionContext OWNER = context(101L, 1L);

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:0.8.6-pg17-bookworm")
                    .asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("conversation_service_test")
            .withUsername("test_user")
            .withPassword("test_password");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    private ConversationService service;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private JdbcMessageRepository messages;

    private final List<Long> createdConversations = new ArrayList<>();

    private record ConversationRow(long id, long tenantId, long userId, String title,
                                   String status, long version,
                                   OffsetDateTime createdAt, OffsetDateTime updatedAt) {
    }

    private record MessageRow(long id, long conversationId, long turnNo,
                              String role, String content, OffsetDateTime createdAt) {
    }

    private record Snapshot(ConversationRow conversation, List<MessageRow> messages) {
    }

    private record SaveOutcome(Long turnNo, RuntimeException failure) {
    }

    @BeforeEach
    void requireRealProxyWithoutOuterTestTransaction() {
        assertTrue(AopUtils.isAopProxy(service), "必须从 Spring 注入的事务代理调用 Service");
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive(),
                "外层测试事务会掩盖保存失败后的实际数据库状态");
        assertEquals(Boolean.TRUE, jdbcTemplate.execute(
                (ConnectionCallback<Boolean>) connection -> connection.getAutoCommit()));
        assertEquals(5, jdbcTemplate.getQueryTimeout(), "并发测试的 SQL 等待必须有界");
    }

    @AfterEach
    void cleanOnlyThisTestsFixtures() {
        for (long id : createdConversations) {
            jdbcTemplate.update("DELETE FROM agent_message WHERE conversation_id = ?", id);
            jdbcTemplate.update("DELETE FROM agent_conversation WHERE id = ?", id);
        }
    }

    @ParameterizedTest
    @CsvSource({"101, 1", "104, 1", "101, 2"})
    void shouldCommitFirstCompleteTurnVersionAndTimestampForExactOwner(long userId, long tenantId) {
        AgentExecutionContext context = context(userId, tenantId);
        long id = createConversation(context);
        Snapshot before = snapshot(id);

        long turn = service.saveCompletedTurn(id, 0L, "查询项目", "项目 ACTIVE", context);
        Snapshot after = snapshot(id);

        assertEquals(1L, turn);
        assertSuccessfulSave(before, after, 1L, 1L);
        assertTurn(after.messages(), id, 1L, "查询项目", "项目 ACTIVE");
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
    }

    @Test
    void shouldSaveSequentialTurnsUsingEachNewVersion() {
        long id = createConversation(OWNER);
        assertEquals(1L, service.saveCompletedTurn(id, 0L, "第一轮问题", "第一轮回答", OWNER));
        assertEquals(2L, service.saveCompletedTurn(id, 1L, "第二轮问题", "第二轮回答", OWNER));

        Snapshot saved = snapshot(id);
        assertEquals(2L, saved.conversation().version());
        assertEquals(List.of(1L, 1L, 2L, 2L), saved.messages().stream().map(MessageRow::turnNo).toList());
        assertEquals(List.of("USER", "ASSISTANT", "USER", "ASSISTANT"),
                saved.messages().stream().map(MessageRow::role).toList());
        assertEquals(List.of("第一轮问题", "第一轮回答", "第二轮问题", "第二轮回答"),
                saved.messages().stream().map(MessageRow::content).toList());
    }

    @ParameterizedTest
    @CsvSource({"7, true, 8", "9, false, 10", "2147483648, true, 2147483649",
            "9223372036854775806, false, 9223372036854775807"})
    void shouldContinueAfterMaximumLegacyTurnIncludingPartialTurn(
            long legacyTurn, boolean complete, long nextTurn) {
        long id = createConversation(OWNER);
        if (complete) {
            assertEquals(2, messages.insertTurn(id, legacyTurn, "旧问题", "旧回答"));
        } else {
            insertSingleMessage(id, legacyTurn, "旧半轮消息");
        }
        Snapshot before = snapshot(id);
        assertEquals(0L, before.conversation().version(), "旧消息轮次与新加 version 独立");

        assertEquals(nextTurn, service.saveCompletedTurn(id, 0L, "新问题", "新回答", OWNER));
        Snapshot after = snapshot(id);

        assertSuccessfulSave(before, after, 1L, nextTurn);
        assertEquals(before.messages(), after.messages().subList(0, before.messages().size()));
        assertTurn(after.messages().subList(before.messages().size(), after.messages().size()),
                id, nextTurn, "新问题", "新回答");
    }

    @Test
    void shouldStoreNonBlankBodiesExactlyAsSupplied() {
        long id = createConversation(OWNER);
        String question = "  问题'); DELETE FROM agent_message; --\n继续查询  ";
        String answer = "\t回答包含 '引号'\n中文正文  ";

        assertEquals(1L, service.saveCompletedTurn(id, 0L, question, answer, OWNER));

        assertTurn(snapshot(id).messages(), id, 1L, question, answer);
    }

    @ParameterizedTest
    @CsvSource({"104, 1", "101, 2", "102, 2"})
    void shouldRejectOtherOwnerWithoutChangingAnyStoredField(long userId, long tenantId) {
        long id = createConversation(OWNER);
        messages.insertTurn(id, 3L, "原问题", "原回答");
        Snapshot before = snapshot(id);

        var failure = assertThrowsExactly(ConversationNotAccessibleException.class,
                () -> service.saveCompletedTurn(id, 0L, "新问题", "新回答", context(userId, tenantId)));

        assertEquals("未找到可访问的会话", failure.getMessage());
        assertEquals(before, snapshot(id));
    }

    @Test
    void shouldUseSameDenialForMissingConversationAndLeaveOtherHistoryUnchanged() {
        long own = createConversation(OWNER);
        messages.insertTurn(own, 1L, "原问题", "原回答");
        Snapshot before = snapshot(own);
        assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM agent_conversation WHERE id = ?", Integer.class, Long.MAX_VALUE));

        var failure = assertThrowsExactly(ConversationNotAccessibleException.class,
                () -> service.saveCompletedTurn(Long.MAX_VALUE, 0L, "新问题", "新回答", OWNER));

        assertEquals("未找到可访问的会话", failure.getMessage());
        assertEquals(before, snapshot(own));
    }

    @Test
    void shouldRejectArchivedConversationWithoutChangingHistoryVersionOrTimestamp() {
        long id = createConversation(OWNER);
        messages.insertTurn(id, 1L, "原问题", "原回答");
        jdbcTemplate.update("UPDATE agent_conversation SET status = 'ARCHIVED' WHERE id = ?", id);
        Snapshot before = snapshot(id);

        assertThrowsExactly(ConversationStateException.class,
                () -> service.saveCompletedTurn(id, 0L, "新问题", "新回答", OWNER));

        assertEquals(before, snapshot(id));
    }

    @Test
    void shouldRejectStaleSnapshotEvenThoughLookupReturnsNewerVersion() {
        long id = createConversation(OWNER);
        messages.insertTurn(id, 2L, "原问题", "原回答");
        jdbcTemplate.update("UPDATE agent_conversation SET version = 4 WHERE id = ?", id);
        Snapshot before = snapshot(id);

        var failure = assertThrowsExactly(ConversationConflictException.class,
                () -> service.saveCompletedTurn(id, 3L, "旧历史生成的问题", "旧历史生成的回答", OWNER));

        assertEquals("会话已发生变化，请重新发起对话", failure.getMessage());
        assertEquals(before, snapshot(id));
    }

    @ParameterizedTest
    @MethodSource("invalidContent")
    void shouldRejectInvalidBodiesWithoutChangingExistingState(String question, String answer) {
        long id = createConversation(OWNER);
        messages.insertTurn(id, 1L, "原问题", "原回答");
        Snapshot before = snapshot(id);

        assertThrowsExactly(IllegalArgumentException.class,
                () -> service.saveCompletedTurn(id, 0L, question, answer, OWNER));

        assertEquals(before, snapshot(id));
    }

    static Stream<Arguments> invalidContent() {
        return Stream.of(null, "", " ", "\n\t")
                .flatMap(blank -> Stream.of(Arguments.of(blank, "回答"), Arguments.of("问题", blank)));
    }

    @ParameterizedTest
    @ValueSource(longs = {-1L, Long.MAX_VALUE})
    void shouldRejectInvalidVersionWithoutChangingAnyStoredField(long invalidVersion) {
        long id = createConversation(OWNER);
        Snapshot before = snapshot(id);

        assertThrowsExactly(IllegalArgumentException.class,
                () -> service.saveCompletedTurn(id, invalidVersion, "问题", "回答", OWNER));

        assertEquals(before, snapshot(id));
    }

    @Test
    void shouldRollbackAlreadyAdvancedVersionAndTimestampWhenNextTurnOverflows() {
        long id = createConversation(OWNER);
        insertSingleMessage(id, Long.MAX_VALUE, "最大轮次的旧半轮消息");
        Snapshot before = snapshot(id);

        var failure = assertThrowsExactly(ConversationStateException.class,
                () -> service.saveCompletedTurn(id, 0L, "新问题", "新回答", OWNER));

        assertEquals("会话轮次已达上限，无法继续保存", failure.getMessage());
        // 未启用 @Transactional 时，这里会看到 version=1 和新 updated_at，明确失败。
        assertEquals(before, snapshot(id));
    }

    @Test
    void shouldRollbackVersionTimestampAndWholeInsertWhenAssistantRowReallyFails() {
        long id = createConversation(OWNER);
        insertSingleMessage(id, 3L, "原有半轮，必须保留");
        Snapshot before = snapshot(id);
        // 仅在本类专用临时容器里，用服务端生成的正数 id 命名及限定测试约束。
        // 第一条 USER 合法，第二条 ASSISTANT 真正触发 SQL 约束失败；不 mock Service/Repository。
        String constraint = "test_save_reject_assistant_" + id;
        jdbcTemplate.execute("ALTER TABLE agent_message ADD CONSTRAINT " + constraint
                + " CHECK (conversation_id <> " + id + " OR role <> 'ASSISTANT')");
        try {
            assertThrows(DataIntegrityViolationException.class,
                    () -> service.saveCompletedTurn(id, 0L, "新问题", "新回答", OWNER));

            assertEquals(before, snapshot(id), "已有半轮应保留，新消息、version 和 updated_at 必须一起回滚");
        } finally {
            jdbcTemplate.execute("ALTER TABLE agent_message DROP CONSTRAINT " + constraint);
        }
    }

    @Test
    void shouldCommitOnlyOneWholeTurnForTwoConcurrentSavesWithSameSnapshotVersion() throws Exception {
        long id = createConversation(OWNER);
        Snapshot before = snapshot(id);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            Future<SaveOutcome> first = executor.submit(
                    () -> concurrentSave(id, "A", ready, start));
            Future<SaveOutcome> second = executor.submit(
                    () -> concurrentSave(id, "B", ready, start));
            assertTrue(ready.await(5, TimeUnit.SECONDS), "两个工作线程应已就绪");
            start.countDown();
            List<SaveOutcome> outcomes = List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));

            assertEquals(1L, outcomes.stream().filter(result -> result.failure() == null).count());
            SaveOutcome success = outcomes.stream().filter(result -> result.failure() == null).findFirst().orElseThrow();
            SaveOutcome rejected = outcomes.stream().filter(result -> result.failure() != null).findFirst().orElseThrow();
            assertEquals(1L, success.turnNo().longValue());
            assertInstanceOf(ConversationConflictException.class, rejected.failure());
            assertNull(rejected.turnNo());
            assertEquals("会话已发生变化，请重新发起对话", rejected.failure().getMessage());

            Snapshot after = snapshot(id);
            assertSuccessfulSave(before, after, 1L, 1L);
            String winner = after.messages().getFirst().content().substring(0, 1);
            assertTrue(Set.of("A", "B").contains(winner));
            assertTurn(after.messages(), id, 1L, winner + "问题", winner + "回答");
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS), "并发保存线程必须退出后再清理夹具");
        }
    }

    private SaveOutcome concurrentSave(long id, String label, CountDownLatch ready, CountDownLatch start)
            throws InterruptedException {
        // 同一 Spring 代理在两个线程中启动独立事务，不用手工实例化或外层事务替代注解。
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
        ready.countDown();
        assertTrue(start.await(5, TimeUnit.SECONDS));
        try {
            long turn = service.saveCompletedTurn(id, 0L, label + "问题", label + "回答", OWNER);
            return new SaveOutcome(turn, null);
        } catch (RuntimeException failure) {
            return new SaveOutcome(null, failure);
        }
    }

    private long createConversation(AgentExecutionContext context) {
        Long id = jdbcTemplate.queryForObject("""
                INSERT INTO agent_conversation (tenant_id, user_id, title, updated_at)
                VALUES (?, ?, '事务测试会话', ?) RETURNING id
                """, Long.class, context.tenantId(), context.userId(), OLD_TIME);
        assertNotNull(id);
        assertTrue(id > 0);
        createdConversations.add(id);
        return id;
    }

    private void insertSingleMessage(long id, long turn, String content) {
        jdbcTemplate.update("""
                INSERT INTO agent_message (conversation_id, turn_no, role, content)
                VALUES (?, ?, 'USER', ?)
                """, id, turn, content);
    }

    private Snapshot snapshot(long id) {
        ConversationRow conversation = jdbcTemplate.queryForObject("""
                SELECT id, tenant_id, user_id, title, status, version, created_at, updated_at
                FROM agent_conversation WHERE id = ?
                """, (rs, rowNum) -> new ConversationRow(rs.getLong("id"), rs.getLong("tenant_id"),
                rs.getLong("user_id"), rs.getString("title"), rs.getString("status"), rs.getLong("version"),
                rs.getObject("created_at", OffsetDateTime.class), rs.getObject("updated_at", OffsetDateTime.class)), id);
        List<MessageRow> rows = jdbcTemplate.query("""
                SELECT id, conversation_id, turn_no, role, content, created_at FROM agent_message
                WHERE conversation_id = ?
                ORDER BY turn_no, CASE role WHEN 'USER' THEN 0 ELSE 1 END
                """, (rs, rowNum) -> new MessageRow(rs.getLong("id"), rs.getLong("conversation_id"),
                rs.getLong("turn_no"), rs.getString("role"), rs.getString("content"),
                rs.getObject("created_at", OffsetDateTime.class)), id);
        assertNotNull(conversation);
        return new Snapshot(conversation, rows);
    }

    private static void assertSuccessfulSave(Snapshot before, Snapshot after, long version, long turn) {
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
        List<MessageRow> appended = after.messages().subList(before.messages().size(), after.messages().size());
        assertEquals(List.of(turn, turn), appended.stream().map(MessageRow::turnNo).toList());
        assertEquals(List.of("USER", "ASSISTANT"), appended.stream().map(MessageRow::role).toList());
        // CURRENT_TIMESTAMP 在同一保存事务内相同；两条消息与条件更新应使用同一事务时间。
        appended.forEach(row -> assertEquals(saved.updatedAt().toInstant(), row.createdAt().toInstant()));
    }

    private static void assertTurn(List<MessageRow> rows, long id, long turn, String question, String answer) {
        assertEquals(2, rows.size());
        assertEquals(List.of("USER", "ASSISTANT"), rows.stream().map(MessageRow::role).toList());
        assertEquals(List.of(question, answer), rows.stream().map(MessageRow::content).toList());
        assertNotEquals(rows.get(0).id(), rows.get(1).id());
        for (MessageRow row : rows) {
            assertTrue(row.id() > 0);
            assertEquals(id, row.conversationId());
            assertEquals(turn, row.turnNo());
            assertNotNull(row.createdAt());
        }
    }

    private static AgentExecutionContext context(long userId, long tenantId) {
        return new AgentExecutionContext(userId, tenantId, Set.of("project:read"));
    }
}
