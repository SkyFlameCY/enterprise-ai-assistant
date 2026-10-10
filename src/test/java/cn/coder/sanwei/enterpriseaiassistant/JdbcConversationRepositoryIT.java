package cn.coder.sanwei.enterpriseaiassistant;

import cn.coder.sanwei.enterpriseaiassistant.entity.AgentConversation;
import cn.coder.sanwei.enterpriseaiassistant.repository.JdbcConversationRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
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

import javax.sql.DataSource;
import java.sql.Connection;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 临时 PostgreSQL + 真实 Liquibase 迁移，验证 Repository 的 SQL 和归属隔离。
 * 不连接开发数据库，不调用模型；每个用例的数据在事务结束后回滚。
 * 双连接竞争用例禁用外层事务，使用自动提交并仅清理自己的会话。
 * 不等同于尚未实现的 Service/API 会话授权验收。
 */
@JdbcTest(properties = {
        "spring.config.import=",
        "spring.liquibase.enabled=true",
        "spring.liquibase.drop-first=false",
        "spring.liquibase.change-log=classpath:/db/changelog/db.changelog-master.xml",
        "spring.ai.deepseek.api-key=test-key"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("conversation-repository-test")
@Import(JdbcConversationRepository.class)
@Testcontainers
class JdbcConversationRepositoryIT {

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:0.8.6-pg17-bookworm")
                    .asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("conversation_repository_test")
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
    private JdbcConversationRepository repository;

    @Autowired
    private DataSource dataSource;

    @Test
    void shouldReturnGeneratedIdAndReadBackOwnerTitleAndDefaults() {
        // 准备并执行：用真实 Repository 创建，再通过本人身份读取。
        Long id = repository.createConversation(1L, 101L, "项目查询");
        assertNotNull(id);
        assertTrue(id > 0);

        AgentConversation result = repository.findOwnedConversation(id, 1L, 101L);

        // 验证：不仅是插入成功，也验证真实 RowMapper 和数据库默认值。
        assertNotNull(result);
        assertAll(
                () -> assertEquals(id, result.getId()),
                () -> assertEquals(1L, result.getTenantId()),
                () -> assertEquals(101L, result.getUserId()),
                () -> assertEquals("项目查询", result.getTitle()),
                () -> assertEquals("ACTIVE", result.getStatus()),
                () -> assertEquals(0L, result.getVersion()),
                () -> assertNotNull(result.getCreatedAt()),
                () -> assertNotNull(result.getUpdatedAt())
        );
    }

    @ParameterizedTest
    @ValueSource(longs = {1L, 7L, 2147483648L, Long.MAX_VALUE})
    void shouldReadStoredVersionWithoutTruncationOrConfusingAnotherOwnedConversation(long version) {
        // 同一用户还有另一条不同版本的会话，不能漏掉 id 条件。
        Long other = repository.createConversation(1L, 101L, "其他会话");
        Long target = repository.createConversation(1L, 101L, "版本读取目标");
        assertEquals(1, jdbcTemplate.update(
                "UPDATE agent_conversation SET version = ? WHERE id = ?", 42L, other));
        assertEquals(1, jdbcTemplate.update(
                "UPDATE agent_conversation SET version = ? WHERE id = ?", version, target));

        AgentConversation result = repository.findOwnedConversation(target, 1L, 101L);

        assertNotNull(result);
        assertAll(
                () -> assertEquals(target, result.getId()),
                () -> assertEquals(1L, result.getTenantId()),
                () -> assertEquals(101L, result.getUserId()),
                () -> assertEquals("版本读取目标", result.getTitle()),
                () -> assertEquals(version, result.getVersion())
        );
        assertEquals(42L, repository.findOwnedConversation(other, 1L, 101L).getVersion());
    }

    @Test
    void shouldKeepMultipleConversationsOfOneOwnerSeparate() {
        Long first = repository.createConversation(1L, 101L, "第一段会话");
        Long second = repository.createConversation(1L, 101L, "第二段会话");

        assertNotEquals(first, second);
        assertEquals("第一段会话", repository.findOwnedConversation(first, 1L, 101L).getTitle());
        assertEquals("第二段会话", repository.findOwnedConversation(second, 1L, 101L).getTitle());
    }

    @ParameterizedTest
    @CsvSource({"1, 101", "1, 104", "2, 101"})
    void shouldReadOnlyExactConversationOfMatchingOwner(long tenantId, long userId) {
        // 同租户不同用户、不同租户相同 userId 都有真实会话。
        repository.createConversation(1L, 101L, "租户A用户101");
        repository.createConversation(1L, 104L, "租户A用户104");
        repository.createConversation(2L, 101L, "租户B用户101");
        Long id = repository.createConversation(tenantId, userId, "本人目标会话");

        AgentConversation result = repository.findOwnedConversation(id, tenantId, userId);

        assertNotNull(result);
        assertAll(
                () -> assertEquals(id, result.getId()),
                () -> assertEquals(tenantId, result.getTenantId()),
                () -> assertEquals(userId, result.getUserId()),
                () -> assertEquals("本人目标会话", result.getTitle()),
                () -> assertEquals(0L, result.getVersion())
        );
    }

    @ParameterizedTest
    @CsvSource({"1, 104", "2, 101", "2, 102"})
    void shouldRejectOtherUserOrOtherTenantEvenWithExistingConversationId(long tenantId, long userId) {
        // 准备：每个访问者自己也有会话，不能通过漏掉 id 条件误返回其他记录。
        Long target = repository.createConversation(1L, 101L, "用户101的私有会话");
        assertEquals(1, jdbcTemplate.update(
                "UPDATE agent_conversation SET version = ? WHERE id = ?", 7L, target));
        repository.createConversation(1L, 104L, "同租户其他用户");
        repository.createConversation(2L, 101L, "跨租户相同用户编号");
        repository.createConversation(2L, 102L, "跨租户其他用户");

        // 执行与验证：知道编号也不意味着有访问权限。
        assertNull(assertDoesNotThrow(() -> repository.findOwnedConversation(target, tenantId, userId)));
    }

    @ParameterizedTest
    @ValueSource(longs = {0L, -1L, Long.MAX_VALUE})
    void shouldReturnNullForMissingConversationWithoutReturningOtherOwnedRows(long missingId) {
        repository.createConversation(1L, 101L, "真实存在的本人会话");

        assertNull(assertDoesNotThrow(() -> repository.findOwnedConversation(missingId, 1L, 101L)));
    }

    @Test
    void shouldReadTimestamptzWithoutChangingItsInstant() {
        Long id = repository.createConversation(1L, 101L, "时间映射验收");
        OffsetDateTime createdAt = OffsetDateTime.parse("2026-10-08T14:30:25.123456+08:00");
        OffsetDateTime updatedAt = OffsetDateTime.parse("2026-10-08T15:40:25.654321+08:00");
        jdbcTemplate.update("""
                UPDATE agent_conversation SET created_at = ?, updated_at = ? WHERE id = ?
                """, createdAt, updatedAt, id);

        AgentConversation result = repository.findOwnedConversation(id, 1L, 101L);

        assertNotNull(result);
        assertNotNull(result.getCreatedAt());
        assertNotNull(result.getUpdatedAt());
        // PostgreSQL 保存时间点，不保证保留写入时的 +08:00 表示；比较 Instant。
        assertEquals(createdAt.toInstant(), result.getCreatedAt().toInstant());
        assertEquals(updatedAt.toInstant(), result.getUpdatedAt().toInstant());
    }

    @Test
    void shouldStoreSqlLikeTitleAsLiteralText() {
        String title = "标题'); DELETE FROM agent_conversation; --";
        Long original = repository.createConversation(1L, 101L, "原有会话");
        Long id = repository.createConversation(1L, 101L, title);

        assertEquals(title, repository.findOwnedConversation(id, 1L, 101L).getTitle());
        assertNotNull(repository.findOwnedConversation(original, 1L, 101L));
    }

    @Test
    void shouldReadArchivedMetadataOnlyForItsOwner() {
        Long id = repository.createConversation(1L, 101L, "归档会话");
        jdbcTemplate.update("UPDATE agent_conversation SET status = ?, version = ? WHERE id = ?",
                "ARCHIVED", 9L, id);

        // Repository 此步只负责归属读取，是否允许归档会话续聊由后续 Service 决定。
        AgentConversation result = repository.findOwnedConversation(id, 1L, 101L);
        assertNotNull(result);
        assertEquals("ARCHIVED", result.getStatus());
        assertEquals(9L, result.getVersion());
        assertNull(repository.findOwnedConversation(id, 1L, 104L));
    }

    @ParameterizedTest
    @CsvSource({"1, 101", "1, 104", "2, 101"})
    void shouldAdvanceOnlyMatchingActiveOwnedConversationAndRefreshUpdateTime(long tenantId, long userId) {
        Long target = createVersionFixture(tenantId, userId, 7L, "ACTIVE");
        Long other = createVersionFixture(tenantId, userId, 7L, "ACTIVE");
        AgentConversation before = repository.findOwnedConversation(target, tenantId, userId);
        AgentConversation otherBefore = repository.findOwnedConversation(other, tenantId, userId);
        OffsetDateTime transactionTime = jdbcTemplate.queryForObject("SELECT CURRENT_TIMESTAMP",
                (rs, rowNum) -> rs.getObject(1, OffsetDateTime.class));

        assertEquals(1, repository.advanceVersionIfActiveOwned(target, tenantId, userId, 7L));

        AgentConversation after = repository.findOwnedConversation(target, tenantId, userId);
        assertNotNull(before);
        assertNotNull(after);
        assertNotNull(transactionTime);
        assertAll(
                () -> assertEquals(8L, after.getVersion()),
                () -> assertEquals(before.getId(), after.getId()),
                () -> assertEquals(before.getTenantId(), after.getTenantId()),
                () -> assertEquals(before.getUserId(), after.getUserId()),
                () -> assertEquals(before.getTitle(), after.getTitle()),
                () -> assertEquals(before.getStatus(), after.getStatus()),
                () -> assertEquals(before.getCreatedAt(), after.getCreatedAt()),
                () -> assertEquals(transactionTime.toInstant(), after.getUpdatedAt().toInstant()),
                () -> assertTrue(after.getUpdatedAt().isAfter(before.getUpdatedAt()))
        );
        assertEquals(otherBefore, repository.findOwnedConversation(other, tenantId, userId));
    }

    @ParameterizedTest
    @ValueSource(longs = {0L, 7L, 2147483648L, Long.MAX_VALUE - 1})
    void shouldAdvanceMatchingVersionWithoutTruncationOrOverflow(long expectedVersion) {
        Long target = createVersionFixture(1L, 101L, expectedVersion, "ACTIVE");

        assertEquals(1, repository.advanceVersionIfActiveOwned(target, 1L, 101L, expectedVersion));
        assertEquals(expectedVersion + 1, repository.findOwnedConversation(target, 1L, 101L).getVersion());
    }

    @ParameterizedTest
    @CsvSource({"1, 104", "2, 101", "2, 102"})
    void shouldNotAdvanceAnotherUsersOrTenantsConversation(long tenantId, long userId) {
        Long target = createVersionFixture(1L, 101L, 7L, "ACTIVE");
        Long other = createVersionFixture(tenantId, userId, 7L, "ACTIVE");
        AgentConversation before = repository.findOwnedConversation(target, 1L, 101L);
        AgentConversation otherBefore = repository.findOwnedConversation(other, tenantId, userId);

        assertEquals(0, repository.advanceVersionIfActiveOwned(target, tenantId, userId, 7L));

        assertEquals(before, repository.findOwnedConversation(target, 1L, 101L));
        assertEquals(otherBefore, repository.findOwnedConversation(other, tenantId, userId));
    }

    @ParameterizedTest
    @ValueSource(longs = {Long.MAX_VALUE, Long.MAX_VALUE - 1})
    void shouldReturnZeroForMissingUpdateTargetWithoutTouchingExistingOwnedRows(long missingId) {
        Long other = createVersionFixture(1L, 101L, 7L, "ACTIVE");
        AgentConversation before = repository.findOwnedConversation(other, 1L, 101L);

        assertEquals(0, repository.advanceVersionIfActiveOwned(missingId, 1L, 101L, 7L));

        assertEquals(before, repository.findOwnedConversation(other, 1L, 101L));
    }

    @Test
    void shouldNotAdvanceArchivedConversationEvenWhenOwnerAndVersionMatch() {
        Long target = createVersionFixture(1L, 101L, 7L, "ARCHIVED");
        AgentConversation before = repository.findOwnedConversation(target, 1L, 101L);

        assertEquals(0, repository.advanceVersionIfActiveOwned(target, 1L, 101L, 7L));

        assertEquals(before, repository.findOwnedConversation(target, 1L, 101L));
    }

    @ParameterizedTest
    @ValueSource(longs = {0L, 6L, 8L, 2147483648L})
    void shouldNotAdvanceWhenExpectedVersionIsOlderOrNewerThanCurrent(long expectedVersion) {
        Long target = createVersionFixture(1L, 101L, 7L, "ACTIVE");
        AgentConversation before = repository.findOwnedConversation(target, 1L, 101L);

        assertEquals(0, repository.advanceVersionIfActiveOwned(target, 1L, 101L, expectedVersion));

        assertEquals(before, repository.findOwnedConversation(target, 1L, 101L));
    }

    @Test
    void shouldRejectReusedOldVersionAndAllowNextMatchingVersion() {
        Long target = createVersionFixture(1L, 101L, 0L, "ACTIVE");

        assertEquals(1, repository.advanceVersionIfActiveOwned(target, 1L, 101L, 0L));
        AgentConversation afterFirst = repository.findOwnedConversation(target, 1L, 101L);
        assertNotNull(afterFirst);
        assertEquals(1L, afterFirst.getVersion());
        assertEquals(0, repository.advanceVersionIfActiveOwned(target, 1L, 101L, 0L));
        assertEquals(afterFirst, repository.findOwnedConversation(target, 1L, 101L));
        assertEquals(1, repository.advanceVersionIfActiveOwned(target, 1L, 101L, 1L));
        assertEquals(2L, repository.findOwnedConversation(target, 1L, 101L).getVersion());
    }

    @Test
    void shouldOnlyAdvanceMetadataWithoutChangingExistingMessageTurns() {
        Long target = createVersionFixture(1L, 101L, 0L, "ACTIVE");
        jdbcTemplate.update("""
                INSERT INTO agent_message (conversation_id, turn_no, role, content)
                VALUES (?, 7, 'USER', ?), (?, 7, 'ASSISTANT', ?)
                """, target, "旧问题", target, "旧回答");
        var before = jdbcTemplate.queryForList(
                "SELECT * FROM agent_message WHERE conversation_id = ? ORDER BY id", target);

        assertEquals(1, repository.advanceVersionIfActiveOwned(target, 1L, 101L, 0L));

        assertEquals(1L, repository.findOwnedConversation(target, 1L, 101L).getVersion());
        assertEquals(2, before.size());
        assertEquals(before, jdbcTemplate.queryForList(
                "SELECT * FROM agent_message WHERE conversation_id = ? ORDER BY id", target));
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void shouldAllowExactlyOneOfTwoIndependentConnectionsToAdvanceSameOldVersion() throws Exception {
        // 只有这个用例不用外层事务，否则工作线程看不到未提交的准备数据。
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
        assertEquals(Boolean.TRUE, jdbcTemplate.execute(
                (ConnectionCallback<Boolean>) Connection::getAutoCommit));
        Long target = repository.createConversation(1L, 101L, "双连接版本竞争");
        assertNotNull(target);

        try (Connection first = dataSource.getConnection();
             Connection second = dataSource.getConnection();
             var executor = Executors.newFixedThreadPool(2)) {
            assertTrue(first.getAutoCommit());
            assertTrue(second.getAutoCommit());
            assertNotEquals(backendId(first), backendId(second), "必须使用两个真实 PostgreSQL 连接");
            JdbcTemplate firstTemplate = templateOnConnection(first);
            JdbcConversationRepository firstRepository = new JdbcConversationRepository(firstTemplate);
            JdbcConversationRepository secondRepository = new JdbcConversationRepository(templateOnConnection(second));
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch start = new CountDownLatch(1);
            var firstResult = executor.submit(() -> {
                ready.countDown();
                assertTrue(start.await(5, TimeUnit.SECONDS));
                return firstRepository.advanceVersionIfActiveOwned(target, 1L, 101L, 0L);
            });
            var secondResult = executor.submit(() -> {
                ready.countDown();
                assertTrue(start.await(5, TimeUnit.SECONDS));
                return secondRepository.advanceVersionIfActiveOwned(target, 1L, 101L, 0L);
            });
            try {
                assertTrue(ready.await(5, TimeUnit.SECONDS), "两个工作线程应准备就绪");
            } finally {
                start.countDown();
            }

            List<Integer> affectedRows = List.of(
                    firstResult.get(10, TimeUnit.SECONDS), secondResult.get(10, TimeUnit.SECONDS));
            assertEquals(List.of(0, 1), affectedRows.stream().sorted().toList());
            // 工作线程已结束，复用已持有的连接检查结果，不额外等待第三条池连接。
            assertEquals(1L, firstRepository.findOwnedConversation(target, 1L, 101L).getVersion());
            assertEquals(0L, firstTemplate.queryForObject(
                    "SELECT count(*) FROM agent_message WHERE conversation_id = ?", Long.class, target));
        } finally {
            // 只删除本用例生成的、没有消息的会话；其他用例仍使用事务回滚。
            jdbcTemplate.update("DELETE FROM agent_conversation WHERE id = ?", target);
        }
    }

    private Long createVersionFixture(long tenantId, long userId, long version, String status) {
        Long id = repository.createConversation(tenantId, userId, "条件更新验收");
        OffsetDateTime oldTime = OffsetDateTime.parse("2026-10-08T10:00:00+08:00");
        assertEquals(1, jdbcTemplate.update("""
                UPDATE agent_conversation
                SET version = ?, status = ?, created_at = ?, updated_at = ? WHERE id = ?
                """, version, status, oldTime, oldTime, id));
        return id;
    }

    private JdbcTemplate templateOnConnection(Connection connection) {
        JdbcTemplate template = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
        template.setQueryTimeout(5);
        return template;
    }

    private int backendId(Connection connection) throws Exception {
        try (var statement = connection.createStatement();
             var row = statement.executeQuery("SELECT pg_backend_pid()")) {
            assertTrue(row.next());
            return row.getInt(1);
        }
    }
}
