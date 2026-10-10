package cn.coder.sanwei.enterpriseaiassistant;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 临时 PostgreSQL 验证 006 的注册、默认值、版本约束和旧数据兼容性。
 * 普通用例由 Liquibase 执行完整 master；升级用例在事务内的独立 schema
 * 先执行真实 004/005、插入旧数据，再执行真实 006，结束后全部回滚。
 * 不连接开发库，不调用模型，不作为应用并发控制已经实现的证据。
 */
@JdbcTest(properties = {
        "spring.config.import=",
        "spring.liquibase.enabled=true",
        "spring.liquibase.drop-first=false",
        "spring.liquibase.change-log=classpath:/db/changelog/db.changelog-master.xml",
        "spring.ai.deepseek.api-key=test-key"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("conversation-version-schema-test")
@Testcontainers
class AgentConversationVersionSchemaIT {

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:0.8.6-pg17-bookworm")
                    .asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("conversation_version_schema_test")
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

    @Test
    void shouldExecuteVersionMigrationAfterMessageMigrationFromMaster() {
        assertEquals(1L, jdbcTemplate.queryForObject("""
                SELECT count(*) FROM databasechangelog
                WHERE id = ? AND author = ? AND exectype = 'EXECUTED'
                """, Long.class, "006-add-conversation-version", "sanwei"));
        assertEquals(Boolean.TRUE, jdbcTemplate.queryForObject("""
                SELECT v.orderexecuted > m.orderexecuted
                FROM databasechangelog v
                JOIN databasechangelog m ON m.id = ? AND m.author = ?
                WHERE v.id = ? AND v.author = ?
                """, Boolean.class, "005-create-agent-message", "sanwei",
                "006-add-conversation-version", "sanwei"));
    }

    @Test
    void shouldDeclareBigintNotNullAndNamedNonNegativeCheck() {
        var column = jdbcTemplate.queryForMap("""
                SELECT data_type, is_nullable
                FROM information_schema.columns
                WHERE table_schema = current_schema()
                  AND table_name = 'agent_conversation' AND column_name = 'version'
                """);
        assertEquals("bigint", column.get("data_type"));
        assertEquals("NO", column.get("is_nullable"));
        assertEquals(1L, jdbcTemplate.queryForObject("""
                SELECT count(*)
                FROM pg_constraint c
                JOIN pg_class t ON t.oid = c.conrelid
                JOIN pg_namespace n ON n.oid = t.relnamespace
                WHERE n.nspname = current_schema() AND t.relname = 'agent_conversation'
                  AND c.conname = ? AND c.contype = 'c' AND c.convalidated
                """, Long.class, "ck_agent_conversation_version_non_negative"));
    }

    @Test
    void shouldDefaultNewConversationVersionToZeroWithoutChangingOtherDefaults() {
        Long conversationId = insertConversation();

        var row = jdbcTemplate.queryForMap("""
                SELECT tenant_id, user_id, title, status, version, created_at, updated_at
                FROM agent_conversation WHERE id = ?
                """, conversationId);

        assertAll(
                () -> assertEquals(1L, row.get("tenant_id")),
                () -> assertEquals(101L, row.get("user_id")),
                () -> assertEquals("新会话", row.get("title")),
                () -> assertEquals("ACTIVE", row.get("status")),
                () -> assertEquals(0L, row.get("version")),
                () -> assertNotNull(row.get("created_at")),
                () -> assertNotNull(row.get("updated_at"))
        );
    }

    @ParameterizedTest
    @ValueSource(longs = {0L, 1L, Long.MAX_VALUE})
    void shouldStoreExplicitNonNegativeVersion(long version) {
        Long conversationId = jdbcTemplate.queryForObject("""
                INSERT INTO agent_conversation (tenant_id, user_id, version)
                VALUES (?, ?, ?) RETURNING id
                """, Long.class, 1L, 101L, version);

        assertEquals(version, readVersion(conversationId));
    }

    @ParameterizedTest
    @ValueSource(longs = {-1L, Long.MIN_VALUE})
    void shouldRejectNegativeVersionOnInsert(long version) {
        assertSqlState("23514", () -> jdbcTemplate.update("""
                INSERT INTO agent_conversation (tenant_id, user_id, version)
                VALUES (?, ?, ?)
                """, 1L, 101L, version));
    }

    @Test
    void shouldRejectExplicitNullVersionOnInsert() {
        assertSqlState("23502", () -> jdbcTemplate.update("""
                INSERT INTO agent_conversation (tenant_id, user_id, version)
                VALUES (?, ?, ?)
                """, 1L, 101L, null));
    }

    @ParameterizedTest
    @ValueSource(longs = {-1L, Long.MIN_VALUE})
    void shouldRejectNegativeVersionOnUpdate(long version) {
        Long conversationId = insertConversation();

        assertSqlState("23514", () -> jdbcTemplate.update(
                "UPDATE agent_conversation SET version = ? WHERE id = ?", version, conversationId));
    }

    @Test
    void shouldRejectExplicitNullVersionOnUpdate() {
        Long conversationId = insertConversation();

        assertSqlState("23502", () -> jdbcTemplate.update(
                "UPDATE agent_conversation SET version = NULL WHERE id = ?", conversationId));
    }

    @Test
    void shouldAllowVersionUpdateWithoutAutomaticallyUpdatingConversationTime() {
        // 用明确的旧时间，避免同一事务内 current_timestamp 相同掩盖时间被修改。
        OffsetDateTime oldTime = OffsetDateTime.parse("2026-10-08T10:00:00+08:00");
        Long conversationId = jdbcTemplate.queryForObject("""
                INSERT INTO agent_conversation (tenant_id, user_id, created_at, updated_at)
                VALUES (?, ?, ?, ?) RETURNING id
                """, Long.class, 1L, 101L, oldTime, oldTime);
        var before = jdbcTemplate.queryForMap("""
                SELECT tenant_id, user_id, title, status, created_at, updated_at
                FROM agent_conversation WHERE id = ?
                """, conversationId);

        assertEquals(1, jdbcTemplate.update(
                "UPDATE agent_conversation SET version = ? WHERE id = ?", 2L, conversationId));

        var after = jdbcTemplate.queryForMap("""
                SELECT tenant_id, user_id, title, status, created_at, updated_at
                FROM agent_conversation WHERE id = ?
                """, conversationId);
        assertEquals(2L, readVersion(conversationId));
        assertEquals(before, after, "更新时间仍需由后续保存事务显式设置");
    }

    @Test
    void shouldKeepVersionIndependentFromMessageTurnNumber() {
        Long conversationId = insertConversation();
        assertEquals(2, jdbcTemplate.update("""
                INSERT INTO agent_message (conversation_id, turn_no, role, content)
                VALUES (?, ?, 'USER', ?), (?, ?, 'ASSISTANT', ?)
                """, conversationId, 7L, "原问题", conversationId, 7L, "原回答"));

        assertEquals(0L, readVersion(conversationId));
        assertEquals(7L, jdbcTemplate.queryForObject(
                "SELECT max(turn_no) FROM agent_message WHERE conversation_id = ?",
                Long.class, conversationId));
    }

    @ParameterizedTest
    @ValueSource(strings = {"ACTIVE", "ARCHIVED"})
    void shouldUpgradeExistingConversationsWithoutChangingOwnershipOrHistory(String status) {
        jdbcTemplate.execute((ConnectionCallback<Void>) connection -> {
            // 在外层测试事务内创建独立 schema，DDL 和旧数据随测试回滚。
            assertFalse(connection.getAutoCommit(), "升级验证必须位于可回滚的测试事务内");
            String schema = "conversation_version_upgrade_" + UUID.randomUUID().toString().replace("-", "");
            try (var statement = connection.createStatement()) {
                statement.execute("CREATE SCHEMA " + schema);
                statement.execute("SET LOCAL search_path TO " + schema + ", public");
            }
            assertEquals(schema, jdbcTemplate.queryForObject("SELECT current_schema()", String.class));
            ScriptUtils.executeSqlScript(connection,
                    new ClassPathResource("db/changelog/changes/004-create-agent-conversation.sql"));
            ScriptUtils.executeSqlScript(connection,
                    new ClassPathResource("db/changelog/changes/005-create-agent-message.sql"));
            assertEquals(0L, jdbcTemplate.queryForObject("""
                    SELECT count(*) FROM information_schema.columns
                    WHERE table_schema = current_schema()
                      AND table_name = 'agent_conversation' AND column_name = 'version'
                    """, Long.class));

            OffsetDateTime createdAt = OffsetDateTime.parse("2026-10-08T10:00:00+08:00");
            OffsetDateTime updatedAt = OffsetDateTime.parse("2026-10-08T10:30:00+08:00");
            Long conversationId = jdbcTemplate.queryForObject("""
                    INSERT INTO agent_conversation
                        (tenant_id, user_id, title, status, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?) RETURNING id
                    """, Long.class, 2L, 102L, "迁移前的会话", status, createdAt, updatedAt);
            Long emptyConversationId = insertConversation();
            // 包含完整旧轮次和单条旧消息，迁移都不应重编号或删除。
            assertEquals(3, jdbcTemplate.update("""
                    INSERT INTO agent_message (conversation_id, turn_no, role, content, created_at)
                    VALUES (?, 7, 'USER', ?, ?), (?, 7, 'ASSISTANT', ?, ?), (?, 9, 'USER', ?, ?)
                    """, conversationId, "旧问题", createdAt, conversationId, "旧回答", updatedAt,
                    conversationId, "旧的单条消息", updatedAt));
            var conversationBefore = jdbcTemplate.queryForMap("""
                    SELECT id, tenant_id, user_id, title, status, created_at, updated_at
                    FROM agent_conversation WHERE id = ?
                    """, conversationId);
            var messagesBefore = readMessages(conversationId);

            // 执行真实 006 SQL，而不是在测试里另写一份 ALTER TABLE。
            ScriptUtils.executeSqlScript(connection,
                    new ClassPathResource("db/changelog/changes/006-add-conversation-version.sql"));

            assertEquals(0L, readVersion(conversationId));
            assertEquals(0L, readVersion(emptyConversationId));
            assertEquals(conversationBefore, jdbcTemplate.queryForMap("""
                    SELECT id, tenant_id, user_id, title, status, created_at, updated_at
                    FROM agent_conversation WHERE id = ?
                    """, conversationId));
            assertEquals(messagesBefore, readMessages(conversationId));
            assertEquals(3, messagesBefore.size());
            assertEquals(1L, jdbcTemplate.queryForObject("""
                    SELECT count(*) FROM pg_indexes
                    WHERE schemaname = current_schema() AND indexname = ?
                    """, Long.class, "idx_agent_conversation_tenant_user_update_id"));
            assertEquals(0L, jdbcTemplate.queryForObject("""
                    SELECT count(*) FROM agent_conversation WHERE version IS NULL OR version < 0
                    """, Long.class));
            return null;
        });
    }

    private Long insertConversation() {
        return jdbcTemplate.queryForObject("""
                INSERT INTO agent_conversation (tenant_id, user_id)
                VALUES (?, ?) RETURNING id
                """, Long.class, 1L, 101L);
    }

    private long readVersion(Long conversationId) {
        Long version = jdbcTemplate.queryForObject(
                "SELECT version FROM agent_conversation WHERE id = ?", Long.class, conversationId);
        assertNotNull(version);
        return version;
    }

    private List<java.util.Map<String, Object>> readMessages(Long conversationId) {
        return jdbcTemplate.queryForList("""
                SELECT id, conversation_id, turn_no, role, content, created_at
                FROM agent_message WHERE conversation_id = ?
                ORDER BY turn_no, CASE role WHEN 'USER' THEN 0 ELSE 1 END
                """, conversationId);
    }

    private void assertSqlState(String expectedState, Runnable operation) {
        DataAccessException error = assertThrows(DataAccessException.class, operation::run);
        SQLException sqlError = assertInstanceOf(SQLException.class, error.getMostSpecificCause());
        assertEquals(expectedState, sqlError.getSQLState());
    }
}
