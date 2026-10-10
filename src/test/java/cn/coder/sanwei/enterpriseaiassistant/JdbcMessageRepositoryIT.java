package cn.coder.sanwei.enterpriseaiassistant;

import cn.coder.sanwei.enterpriseaiassistant.entity.AgentMessage;
import cn.coder.sanwei.enterpriseaiassistant.repository.JdbcMessageRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
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

import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.LongStream;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 临时 PostgreSQL 执行真实 Repository SQL，验证整轮插入、归属隔离、有限历史和最大轮次。
 * 禁用外层测试事务：不能用测试结束时的整事务回滚掩盖半轮已经落库的问题。
 * 每个测试仅清理自己创建的会话和消息，容器为本类专用；不连接开发库或模型。
 * 不验证尚未实现的 Service 授权、并发控制或更新时间事务。
 */
@JdbcTest(properties = {
        "spring.config.import=",
        "spring.liquibase.enabled=true",
        "spring.liquibase.drop-first=false",
        "spring.liquibase.change-log=classpath:/db/changelog/db.changelog-master.xml",
        "spring.ai.deepseek.api-key=test-key"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("message-repository-test")
@Import(JdbcMessageRepository.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Testcontainers
class JdbcMessageRepositoryIT {

    private record StoredMessage(long id, long conversationId, long turnNo,
                                 String role, String content, OffsetDateTime createdAt) {
    }

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:0.8.6-pg17-bookworm")
                    .asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("message_repository_test")
            .withUsername("test_user")
            .withPassword("test_password");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private JdbcMessageRepository repository;

    private final List<Long> createdConversations = new ArrayList<>();

    @BeforeEach
    void requireNoOuterTestTransaction() {
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive(),
                "本类验证语句原子性，不应套用测试事务");
        Boolean autoCommit = jdbcTemplate.execute(
                (org.springframework.jdbc.core.ConnectionCallback<Boolean>) connection -> connection.getAutoCommit());
        assertEquals(Boolean.TRUE, autoCommit);
    }

    @AfterEach
    void cleanOnlyThisTestsFixtures() {
        for (Long id : createdConversations) {
            jdbcTemplate.update("DELETE FROM agent_message WHERE conversation_id = ?", id);
            jdbcTemplate.update("DELETE FROM agent_conversation WHERE id = ?", id);
        }
    }

    @Test
    void shouldInsertExactlyTwoMessagesWithSameConversationAndTurn() {
        Long conversationId = createConversation();

        int affectedRows = repository.insertTurn(conversationId, 1L, "查询项目", "项目状态 ACTIVE");
        List<StoredMessage> rows = readMessages(conversationId);

        assertEquals(2, affectedRows);
        assertEquals(2, rows.size());
        assertEquals(List.of("USER", "ASSISTANT"), rows.stream().map(StoredMessage::role).toList());
        assertEquals(List.of("查询项目", "项目状态 ACTIVE"), rows.stream().map(StoredMessage::content).toList());
        assertNotEquals(rows.get(0).id(), rows.get(1).id());
        for (StoredMessage row : rows) {
            assertTrue(row.id() > 0);
            assertEquals(conversationId.longValue(), row.conversationId());
            assertEquals(1L, row.turnNo());
            assertNotNull(row.createdAt());
        }
    }

    @Test
    void shouldKeepDifferentTurnsAndDifferentConversationsSeparate() {
        Long first = createConversation();
        Long second = createConversation();
        assertEquals(2, repository.insertTurn(first, 1L, "第一轮问题", "第一轮回答"));
        assertEquals(2, repository.insertTurn(first, 2L, "第二轮问题", "第二轮回答"));
        assertEquals(2, repository.insertTurn(second, 1L, "另一会话问题", "另一会话回答"));

        assertEquals(List.of(1L, 1L, 2L, 2L), readMessages(first).stream().map(StoredMessage::turnNo).toList());
        assertEquals(List.of("另一会话问题", "另一会话回答"),
                readMessages(second).stream().map(StoredMessage::content).toList());
    }

    @Test
    void shouldStoreSqlLikeAndMultilineContentAsLiteralText() {
        Long conversationId = createConversation();
        String question = "问题'); DELETE FROM agent_message; --\n继续查询";
        String answer = "回答包含 '引号'\n和中文正文";

        assertEquals(2, repository.insertTurn(conversationId, 1L, question, answer));

        assertEquals(List.of(question, answer), readMessages(conversationId).stream()
                .map(StoredMessage::content).toList());
    }

    @ParameterizedTest
    @MethodSource("invalidBodies")
    void shouldLeaveNoHalfTurnWhenEitherBodyIsInvalid(String question, String answer, String sqlState) {
        Long conversationId = createConversation();

        assertSqlState(sqlState, () -> repository.insertTurn(conversationId, 1L, question, answer));

        // 无外层事务；第二条失败后立刻查询，第一条也必须没有留下。
        assertTrue(readMessages(conversationId).isEmpty());
    }

    static Stream<Arguments> invalidBodies() {
        return Stream.of(
                Arguments.of("合法问题", "", "23514"),
                Arguments.of("合法问题", "   ", "23514"),
                Arguments.of("合法问题", null, "23502"),
                Arguments.of("", "合法回答", "23514"),
                Arguments.of("   ", "合法回答", "23514"),
                Arguments.of(null, "合法回答", "23502")
        );
    }

    @ParameterizedTest
    @ValueSource(strings = {"USER", "ASSISTANT"})
    void shouldNotAppendOrChangeRowsWhenEitherRoleAlreadyExists(String conflictingRole) {
        Long conversationId = createConversation();
        // 仅用于冲突测试的半轮夹具；生产 Service 不允许这样单条写入。
        jdbcTemplate.update("""
                INSERT INTO agent_message (conversation_id, turn_no, role, content)
                VALUES (?, ?, ?, ?)
                """, conversationId, 1L, conflictingRole, "原有正文");
        List<StoredMessage> before = readMessages(conversationId);

        assertSqlState("23505", () -> repository.insertTurn(conversationId, 1L, "新问题", "新回答"));

        // 即使第二个元组冲突，也不能留下第一个元组，更不能覆盖原记录。
        assertEquals(before, readMessages(conversationId));
    }

    @ParameterizedTest
    @ValueSource(longs = {0L, -1L})
    void shouldRejectInvalidTurnWithoutWritingAnyMessage(long turnNo) {
        Long conversationId = createConversation();

        assertSqlState("23514", () -> repository.insertTurn(conversationId, turnNo, "问题", "回答"));

        assertTrue(readMessages(conversationId).isEmpty());
    }

    @Test
    void shouldRejectMissingConversationWithoutWritingAnyMessage() {
        assertSqlState("23503", () -> repository.insertTurn(Long.MAX_VALUE, 1L, "问题", "回答"));

        assertTrue(readMessages(Long.MAX_VALUE).isEmpty());
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3, 20})
    void shouldReturnLatestCompleteTurnsInChronologicalRoleOrder(int turnLimit) {
        Long target = createConversation();
        // 故意不按轮次写入：读取顺序不能依赖自增 id 或保存时间。
        insertHistoryTurn(target, 3L);
        insertHistoryTurn(target, 1L);
        insertHistoryTurn(target, 2L);
        Long other = createConversation();
        insertHistoryTurn(other, 3L);
        insertHistoryTurn(other, 99L);

        List<AgentMessage> messages = repository.findRecentOwnedMessages(target, 1L, 101L, turnLimit);

        long firstTurn = Math.max(1, 4 - turnLimit);
        assertHistory(target, messages, LongStream.rangeClosed(firstTurn, 3).toArray());
    }

    @ParameterizedTest
    @CsvSource({"1, 101", "1, 104", "2, 101"})
    void shouldReadHistoryForExactOwnerWithoutMixingOtherConversations(long tenantId, long userId) {
        Long target = createConversation(tenantId, userId);
        Long sameTenantOtherUser = createConversation(tenantId, userId + 1000);
        Long sameUserOtherTenant = createConversation(tenantId + 1000, userId);
        insertHistoryTurn(target, 1L);
        insertHistoryTurn(sameTenantOtherUser, 1L);
        insertHistoryTurn(sameUserOtherTenant, 1L);

        assertHistory(target, repository.findRecentOwnedMessages(target, tenantId, userId, 5), 1L);
    }

    @ParameterizedTest
    @CsvSource({"1, 104", "2, 101", "2, 102"})
    void shouldReturnNoHistoryForOtherUserOrTenantEvenWhenTheyHaveTheirOwnHistory(long tenantId, long userId) {
        Long target = createConversation(1L, 101L);
        insertHistoryTurn(target, 1L);
        insertHistoryTurn(createConversation(1L, 104L), 1L);
        insertHistoryTurn(createConversation(2L, 101L), 1L);
        insertHistoryTurn(createConversation(2L, 102L), 1L);

        assertEquals(List.of(), assertDoesNotThrow(
                () -> repository.findRecentOwnedMessages(target, tenantId, userId, 5)));
    }

    @ParameterizedTest
    @ValueSource(longs = {0L, -1L, Long.MAX_VALUE})
    void shouldReturnEmptyHistoryForMissingConversationRatherThanOtherOwnedHistory(long missingId) {
        insertHistoryTurn(createConversation(), 1L);

        assertEquals(List.of(), assertDoesNotThrow(
                () -> repository.findRecentOwnedMessages(missingId, 1L, 101L, 5)));
    }

    @Test
    void shouldReturnEmptyHistoryForOwnedConversationWithoutMessages() {
        Long target = createConversation();

        assertEquals(List.of(), repository.findRecentOwnedMessages(target, 1L, 101L, 5));
    }

    @ParameterizedTest
    @ValueSource(strings = {"USER", "ASSISTANT"})
    void shouldExcludeHistoryContainingOnlyOneRole(String role) {
        Long target = createConversation();
        insertSingleMessage(target, 1L, role);

        assertEquals(List.of(), repository.findRecentOwnedMessages(target, 1L, 101L, 5));
    }

    @Test
    void shouldSkipPartialTurnsBeforeApplyingRecentTurnLimit() {
        Long target = createConversation();
        insertHistoryTurn(target, 1L);
        insertSingleMessage(target, 2L, "USER");
        insertHistoryTurn(target, 3L);
        insertSingleMessage(target, 4L, "ASSISTANT");
        insertSingleMessage(target, 5L, "USER");

        assertHistory(target, repository.findRecentOwnedMessages(target, 1L, 101L, 2), 1L, 3L);
        assertHistory(target, repository.findRecentOwnedMessages(target, 1L, 101L, 1), 3L);
    }

    @Test
    void shouldMapHistoryTimestampWithoutChangingItsInstant() {
        Long target = createConversation();
        insertHistoryTurn(target, 1L);
        OffsetDateTime createdAt = OffsetDateTime.parse("2026-10-09T09:00:00.123456+08:00");
        jdbcTemplate.update("UPDATE agent_message SET created_at = ? WHERE conversation_id = ?",
                createdAt, target);

        List<AgentMessage> messages = repository.findRecentOwnedMessages(target, 1L, 101L, 5);

        assertHistory(target, messages, 1L);
        for (AgentMessage message : messages) {
            assertEquals(createdAt.toInstant(), message.getCreatedAt().toInstant());
        }
    }

    @Test
    void shouldAllowOwnerToReadArchivedHistoryWithoutGrantingOthersAccess() {
        Long target = createConversation();
        insertHistoryTurn(target, 1L);
        jdbcTemplate.update("UPDATE agent_conversation SET status = ? WHERE id = ?", "ARCHIVED", target);

        // 历史只读；不代表归档会话可以继续调用模型或追加消息。
        assertHistory(target, repository.findRecentOwnedMessages(target, 1L, 101L, 5), 1L);
        assertEquals(List.of(), repository.findRecentOwnedMessages(target, 1L, 104L, 5));
    }

    @Test
    void shouldLimitTwentyTurnsToFortyMessagesAndExcludeTheOldestTurn() {
        Long target = createConversation();
        for (long turn = 1; turn <= 21; turn++) {
            insertHistoryTurn(target, turn);
        }

        List<AgentMessage> messages = repository.findRecentOwnedMessages(target, 1L, 101L, 20);

        assertHistory(target, messages, LongStream.rangeClosed(2, 21).toArray());
    }

    @Test
    void shouldReturnZeroMaximumForOwnedConversationWithoutMessages() {
        Long target = createConversation();
        // 同一用户的其他会话有历史，目标空会话仍应返回 0。
        insertHistoryTurn(createConversation(), 7L);

        assertEquals(0L, repository.findMaxOwnedTurnNo(target, 1L, 101L));
        assertTrue(readMessages(target).isEmpty());
    }

    @Test
    void shouldReadMaximumRatherThanRowCountInsertionOrderOrConversationVersion() {
        Long target = createConversation();
        insertHistoryTurn(target, 7L);
        insertHistoryTurn(target, 1L);
        insertHistoryTurn(target, 3L);
        insertHistoryTurn(createConversation(), 99L);
        List<StoredMessage> before = readMessages(target);
        assertEquals(0L, jdbcTemplate.queryForObject(
                "SELECT version FROM agent_conversation WHERE id = ?", Long.class, target));

        assertEquals(7L, repository.findMaxOwnedTurnNo(target, 1L, 101L));

        assertEquals(6, before.size());
        assertEquals(before, readMessages(target));
        assertEquals(0L, jdbcTemplate.queryForObject(
                "SELECT version FROM agent_conversation WHERE id = ?", Long.class, target));
    }

    @ParameterizedTest
    @ValueSource(strings = {"USER", "ASSISTANT"})
    void shouldIncludeHighestPartialTurnEvenThoughItIsExcludedFromModelHistory(String role) {
        Long target = createConversation();
        insertHistoryTurn(target, 7L);
        insertSingleMessage(target, 9L, role);
        List<StoredMessage> before = readMessages(target);

        assertEquals(9L, repository.findMaxOwnedTurnNo(target, 1L, 101L));

        assertEquals(before, readMessages(target));
        assertHistory(target, repository.findRecentOwnedMessages(target, 1L, 101L, 5), 7L);
    }

    @ParameterizedTest
    @CsvSource({"1, 101", "1, 104", "2, 101"})
    void shouldReadMaximumOnlyForExactOwnerAndConversation(long tenantId, long userId) {
        Long target = createConversation(tenantId, userId);
        insertHistoryTurn(target, 7L);
        insertHistoryTurn(createConversation(tenantId, userId), 99L);
        insertHistoryTurn(createConversation(tenantId, userId + 1000), 99L);
        insertHistoryTurn(createConversation(tenantId + 1000, userId), 99L);

        assertEquals(7L, repository.findMaxOwnedTurnNo(target, tenantId, userId));
    }

    @ParameterizedTest
    @CsvSource({"1, 104", "2, 101", "2, 102"})
    void shouldReturnZeroMaximumForAnotherUsersOrTenantsConversation(long tenantId, long userId) {
        Long target = createConversation(1L, 101L);
        Long own = createConversation(tenantId, userId);
        insertHistoryTurn(target, 77L);
        insertHistoryTurn(own, 9L);
        List<StoredMessage> before = readMessages(target);

        assertEquals(0L, repository.findMaxOwnedTurnNo(target, tenantId, userId));

        assertEquals(9L, repository.findMaxOwnedTurnNo(own, tenantId, userId));
        assertEquals(before, readMessages(target));
    }

    @ParameterizedTest
    @ValueSource(longs = {Long.MAX_VALUE, Long.MAX_VALUE - 1})
    void shouldReturnZeroMaximumForMissingConversationRatherThanOtherOwnedHistory(long missingId) {
        insertHistoryTurn(createConversation(), 7L);

        assertEquals(0L, repository.findMaxOwnedTurnNo(missingId, 1L, 101L));
    }

    @ParameterizedTest
    @ValueSource(longs = {2147483648L, Long.MAX_VALUE})
    void shouldReadLargeMaximumTurnWithoutAddingOneOrTruncating(long maximumTurn) {
        Long target = createConversation();
        insertHistoryTurn(target, 1L);
        insertSingleMessage(target, maximumTurn, "USER");

        assertEquals(maximumTurn, repository.findMaxOwnedTurnNo(target, 1L, 101L));
    }

    @Test
    void shouldReadArchivedMaximumOnlyForOwnerWithoutGrantingContinuation() {
        Long target = createConversation();
        insertHistoryTurn(target, 7L);
        jdbcTemplate.update("UPDATE agent_conversation SET status = ? WHERE id = ?", "ARCHIVED", target);

        assertEquals(7L, repository.findMaxOwnedTurnNo(target, 1L, 101L));
        assertEquals(0L, repository.findMaxOwnedTurnNo(target, 1L, 104L));
        assertEquals("ARCHIVED", jdbcTemplate.queryForObject(
                "SELECT status FROM agent_conversation WHERE id = ?", String.class, target));
    }

    private Long createConversation() {
        return createConversation(1L, 101L);
    }

    private Long createConversation(long tenantId, long userId) {
        Long id = jdbcTemplate.queryForObject("""
                INSERT INTO agent_conversation (tenant_id, user_id)
                VALUES (?, ?) RETURNING id
                """, Long.class, tenantId, userId);
        assertNotNull(id);
        createdConversations.add(id);
        return id;
    }

    private void insertHistoryTurn(long conversationId, long turnNo) {
        assertEquals(2, repository.insertTurn(conversationId, turnNo,
                "问题-" + conversationId + "-" + turnNo,
                "回答-" + conversationId + "-" + turnNo));
    }

    private void insertSingleMessage(long conversationId, long turnNo, String role) {
        // 仅用于半轮相关测试的夹具，不是生产写入路径。
        jdbcTemplate.update("""
                INSERT INTO agent_message (conversation_id, turn_no, role, content)
                VALUES (?, ?, ?, ?)
                """, conversationId, turnNo, role, "半轮正文");
    }

    private void assertHistory(long conversationId, List<AgentMessage> messages, long... expectedTurns) {
        assertNotNull(messages);
        assertEquals(expectedTurns.length * 2, messages.size());
        for (int index = 0; index < expectedTurns.length; index++) {
            long turnNo = expectedTurns[index];
            AgentMessage user = messages.get(index * 2);
            AgentMessage assistant = messages.get(index * 2 + 1);
            assertNotEquals(user.getId(), assistant.getId());
            assertAll(
                    () -> assertEquals("USER", user.getRole()),
                    () -> assertEquals("ASSISTANT", assistant.getRole()),
                    () -> assertEquals("问题-" + conversationId + "-" + turnNo, user.getContent()),
                    () -> assertEquals("回答-" + conversationId + "-" + turnNo, assistant.getContent())
            );
            for (AgentMessage message : List.of(user, assistant)) {
                assertNotNull(message.getId());
                assertTrue(message.getId() > 0);
                assertEquals(conversationId, message.getConversationId());
                assertEquals(turnNo, message.getTurnNo());
                assertNotNull(message.getCreatedAt());
            }
        }
    }

    private List<StoredMessage> readMessages(long conversationId) {
        return jdbcTemplate.query("""
                SELECT id, conversation_id, turn_no, role, content, created_at
                FROM agent_message WHERE conversation_id = ?
                ORDER BY turn_no, CASE role WHEN 'USER' THEN 0 ELSE 1 END
                """, (rs, rowNum) -> new StoredMessage(
                rs.getLong("id"),
                rs.getLong("conversation_id"),
                rs.getLong("turn_no"),
                rs.getString("role"),
                rs.getString("content"),
                rs.getObject("created_at", OffsetDateTime.class)
        ), conversationId);
    }

    private void assertSqlState(String expectedState, Runnable operation) {
        DataAccessException error = assertThrows(DataAccessException.class, operation::run);
        SQLException sqlError = assertInstanceOf(SQLException.class, error.getMostSpecificCause());
        assertEquals(expectedState, sqlError.getSQLState());
    }
}
