package cn.coder.sanwei.enterpriseaiassistant;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.sql.SQLException;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 使用临时 PostgreSQL 运行 master 中的真实迁移，验证会话表和数据约束。
 * 不连接开发库，不调用模型；每个测试的数据随事务回滚。
 * 这里只验证会话归属字段的存储，Repository/API 的访问隔离由后续步骤验证。
 */
@JdbcTest(properties = {
        "spring.liquibase.enabled=true",
        "spring.liquibase.drop-first=false",
        "spring.liquibase.change-log=classpath:/db/changelog/db.changelog-master.xml",
        "spring.ai.deepseek.api-key=test-key"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("conversation-schema-test")
@Testcontainers
class AgentConversationSchemaIT {

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:0.8.6-pg17-bookworm")
                    .asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("conversation_schema_test")
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
    void shouldExecuteConversationMigrationFromMasterChangelog() {
        // 执行：测试启动时由 Liquibase 执行 master，不手工执行单独 SQL 文件。
        // 验证：注册不能遗漏，迁移确实执行且表存在。
        assertEquals(1L, jdbcTemplate.queryForObject("""
                SELECT count(*) FROM databasechangelog
                WHERE id = ? AND author = ? AND exectype = 'EXECUTED'
                """, Long.class, "004-create-agent-conversation", "sanwei"));
        assertEquals(1L, jdbcTemplate.queryForObject("""
                SELECT count(*) FROM information_schema.tables
                WHERE table_schema = current_schema() AND table_name = 'agent_conversation'
                """, Long.class));
    }

    @Test
    void shouldGenerateIdAndProvideDefaultTitleStatusAndTimestamps() {
        // 准备：仅提供服务端身份，其他字段由数据库生成或取默认值。
        Long id = insertConversation(1L, 101L);

        // 执行：读取真实插入记录。
        var row = jdbcTemplate.queryForMap("""
                SELECT tenant_id, user_id, title, status, created_at, updated_at
                FROM agent_conversation WHERE id = ?
                """, id);

        // 验证：默认值及会话归属均正确。
        assertAll(
                () -> assertNotNull(id),
                () -> assertTrue(id != null && id > 0),
                () -> assertEquals(1L, row.get("tenant_id")),
                () -> assertEquals(101L, row.get("user_id")),
                () -> assertEquals("新会话", row.get("title")),
                () -> assertEquals("ACTIVE", row.get("status")),
                () -> assertNotNull(row.get("created_at")),
                () -> assertNotNull(row.get("updated_at"))
        );
    }

    @Test
    void shouldAllowMultipleConversationsForOneOwner() {
        Long first = insertConversation(1L, 101L);
        Long second = insertConversation(1L, 101L);

        assertNotEquals(first, second);
        assertEquals(2L, jdbcTemplate.queryForObject("""
                SELECT count(*) FROM agent_conversation WHERE tenant_id = ? AND user_id = ?
                """, Long.class, 1L, 101L));
    }

    @Test
    void shouldStoreDistinctTenantAndUserOwnership() {
        insertConversation(1L, 101L);
        insertConversation(1L, 104L);
        insertConversation(2L, 101L);

        // 证明归属字段可区分同租户不同用户和跨租户用户。
        // 此处是测试 SQL，不代表尚未实现的 Repository 已具备授权检查。
        assertEquals(1L, countOwned(1L, 101L));
        assertEquals(1L, countOwned(1L, 104L));
        assertEquals(1L, countOwned(2L, 101L));
        assertEquals(0L, countOwned(2L, 104L));
    }

    @Test
    void shouldCreateIndexForOwnerAndStableRecentConversationOrder() {
        String definition = jdbcTemplate.queryForObject("""
                SELECT indexdef FROM pg_indexes
                WHERE schemaname = current_schema() AND indexname = ?
                """, String.class, "idx_agent_conversation_tenant_user_update_id");

        assertNotNull(definition);
        assertTrue(definition.contains("(tenant_id, user_id, updated_at DESC, id DESC)"));
    }

    @ParameterizedTest
    @CsvSource({"0, 101", "-1, 101", "1, 0", "1, -1"})
    void shouldRejectNonPositiveOwnerIds(long tenantId, long userId) {
        assertSqlState("23514", () -> insertConversation(tenantId, userId));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   "})
    void shouldRejectEmptyOrSpaceOnlyTitle(String title) {
        assertSqlState("23514", () -> jdbcTemplate.update("""
                INSERT INTO agent_conversation (tenant_id, user_id, title)
                VALUES (?, ?, ?)
                """, 1L, 101L, title));
    }

    @ParameterizedTest
    @ValueSource(strings = {"OPEN", "DRAFT", "active"})
    void shouldRejectUnsupportedStatus(String status) {
        assertSqlState("23514", () -> jdbcTemplate.update("""
                INSERT INTO agent_conversation (tenant_id, user_id, status)
                VALUES (?, ?, ?)
                """, 1L, 101L, status));
    }

    @Test
    void shouldAllowArchivedStatus() {
        Long id = jdbcTemplate.queryForObject("""
                INSERT INTO agent_conversation (tenant_id, user_id, status)
                VALUES (?, ?, ?) RETURNING id
                """, Long.class, 1L, 101L, "ARCHIVED");

        assertEquals("ARCHIVED", jdbcTemplate.queryForObject(
                "SELECT status FROM agent_conversation WHERE id = ?", String.class, id));
    }

    @ParameterizedTest
    @MethodSource("missingRequiredFields")
    void shouldRejectExplicitNullRequiredFields(Long tenantId, Long userId, String title, String status) {
        assertSqlState("23502", () -> jdbcTemplate.update("""
                INSERT INTO agent_conversation (tenant_id, user_id, title, status)
                VALUES (?, ?, ?, ?)
                """, tenantId, userId, title, status));
    }

    static Stream<Arguments> missingRequiredFields() {
        return Stream.of(
                Arguments.of(null, 101L, "测试会话", "ACTIVE"),
                Arguments.of(1L, null, "测试会话", "ACTIVE"),
                Arguments.of(1L, 101L, null, "ACTIVE"),
                Arguments.of(1L, 101L, "测试会话", null)
        );
    }

    private Long insertConversation(long tenantId, long userId) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO agent_conversation (tenant_id, user_id)
                VALUES (?, ?) RETURNING id
                """, Long.class, tenantId, userId);
    }

    private long countOwned(long tenantId, long userId) {
        Long count = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM agent_conversation WHERE tenant_id = ? AND user_id = ?
                """, Long.class, tenantId, userId);
        return count == null ? 0L : count;
    }

    private void assertSqlState(String expectedState, Runnable operation) {
        DataAccessException error = assertThrows(DataAccessException.class, operation::run);
        SQLException sqlError = assertInstanceOf(SQLException.class, error.getMostSpecificCause());
        assertEquals(expectedState, sqlError.getSQLState());
    }
}
