package cn.coder.sanwei.enterpriseaiassistant;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
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
import java.time.OffsetDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 使用临时 PostgreSQL 执行真实 master 迁移，检查消息表的约束和存储。
 * 不连接开发库，不调用模型；每个测试结束后回滚测试数据。
 * 此处不证明应用层授权、整轮事务写入、请求幂等或并发控制已实现。
 */
@JdbcTest(properties = {
        "spring.config.import=",
        "spring.liquibase.enabled=true",
        "spring.liquibase.drop-first=false",
        "spring.liquibase.change-log=classpath:/db/changelog/db.changelog-master.xml",
        "spring.ai.deepseek.api-key=test-key"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("message-schema-test")
@Testcontainers
class AgentMessageSchemaIT {

    private record StoredMessage(long id, long conversationId, long turnNo,
                                 String role, String content, OffsetDateTime createdAt) {
    }

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:0.8.6-pg17-bookworm")
                    .asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("message_schema_test")
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
    void shouldExecuteMessageMigrationFromMasterChangelog() {
        // 启动测试时执行 master，验证 005 没有漏注册。
        assertEquals(1L, jdbcTemplate.queryForObject("""
                SELECT count(*) FROM databasechangelog
                WHERE id = ? AND author = ? AND exectype = 'EXECUTED'
                """, Long.class, "005-create-agent-message", "sanwei"));
        assertEquals(1L, jdbcTemplate.queryForObject("""
                SELECT count(*) FROM information_schema.tables
                WHERE table_schema = current_schema() AND table_name = 'agent_message'
                """, Long.class));
    }

    @Test
    void shouldAllowUserAndAssistantInSameTurnAndGenerateIdsAndTimestamps() {
        // 准备：创建真实会话，同轮两种角色共用 turn_no。
        Long conversationId = insertConversation(1L, 101L);
        Long userId = insertMessage(conversationId, 1L, "USER", "查询 DB-CHECK-2026 项目");
        Long assistantId = insertMessage(conversationId, 1L, "ASSISTANT", "项目状态为 ACTIVE");

        // 执行：直接读取表，不借此声称消息 Repository 或整轮事务已实现。
        List<StoredMessage> messages = readMessages(conversationId);

        // 验证：角色、正文、归属、轮次以及数据库生成字段正确。
        assertNotNull(userId);
        assertNotNull(assistantId);
        assertTrue(userId > 0);
        assertTrue(assistantId > 0);
        assertNotEquals(userId, assistantId);
        assertEquals(2, messages.size());
        assertAll(
                () -> assertEquals(userId.longValue(), messages.get(0).id()),
                () -> assertEquals(assistantId.longValue(), messages.get(1).id()),
                () -> assertEquals(conversationId.longValue(), messages.get(0).conversationId()),
                () -> assertEquals(conversationId.longValue(), messages.get(1).conversationId()),
                () -> assertEquals(1L, messages.get(0).turnNo()),
                () -> assertEquals(1L, messages.get(1).turnNo()),
                () -> assertEquals("USER", messages.get(0).role()),
                () -> assertEquals("ASSISTANT", messages.get(1).role()),
                () -> assertEquals("查询 DB-CHECK-2026 项目", messages.get(0).content()),
                () -> assertEquals("项目状态为 ACTIVE", messages.get(1).content()),
                () -> assertNotNull(messages.get(0).createdAt()),
                () -> assertNotNull(messages.get(1).createdAt())
        );
    }

    @Test
    void shouldAllowSameRoleInDifferentTurnsAndDifferentConversations() {
        Long first = insertConversation(1L, 101L);
        Long second = insertConversation(2L, 102L);
        insertMessage(first, 1L, "USER", "第一轮问题");
        insertMessage(first, 2L, "USER", "第二轮问题");
        insertMessage(second, 1L, "USER", "另一个会话的第一轮问题");

        assertEquals(2, readMessages(first).size());
        assertEquals(1, readMessages(second).size());
        assertEquals(List.of(1L, 2L), readMessages(first).stream().map(StoredMessage::turnNo).toList());
    }

    @ParameterizedTest
    @ValueSource(strings = {"SYSTEM", "TOOL", "user", "assistant", "UNKNOWN", ""})
    void shouldRejectUnsupportedMessageRole(String role) {
        Long conversationId = insertConversation(1L, 101L);

        assertSqlState("23514", () -> insertMessage(conversationId, 1L, role, "测试正文"));
    }

    @ParameterizedTest
    @ValueSource(longs = {0L, -1L})
    void shouldRejectNonPositiveTurnNumber(long turnNo) {
        Long conversationId = insertConversation(1L, 101L);

        assertSqlState("23514", () -> insertMessage(conversationId, turnNo, "USER", "测试正文"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   "})
    void shouldRejectEmptyOrSpaceOnlyContent(String content) {
        Long conversationId = insertConversation(1L, 101L);

        assertSqlState("23514", () -> insertMessage(conversationId, 1L, "USER", content));
    }

    @ParameterizedTest
    @ValueSource(strings = {"conversation_id", "turn_no", "role", "content", "created_at"})
    void shouldRejectExplicitNullRequiredField(String missingField) {
        Long conversationId = insertConversation(1L, 101L);
        OffsetDateTime createdAt = OffsetDateTime.parse("2026-10-08T14:30:25+08:00");

        assertSqlState("23502", () -> jdbcTemplate.update("""
                INSERT INTO agent_message (conversation_id, turn_no, role, content, created_at)
                VALUES (?, ?, ?, ?, ?)
                """,
                missingField.equals("conversation_id") ? null : conversationId,
                missingField.equals("turn_no") ? null : 1L,
                missingField.equals("role") ? null : "USER",
                missingField.equals("content") ? null : "测试正文",
                missingField.equals("created_at") ? null : createdAt));
    }

    @ParameterizedTest
    @ValueSource(longs = {0L, Long.MAX_VALUE})
    void shouldRejectMessageReferencingMissingConversation(long missingConversationId) {
        assertSqlState("23503", () -> insertMessage(missingConversationId, 1L, "USER", "测试正文"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"USER", "ASSISTANT"})
    void shouldRejectDuplicateRoleWithinSameConversationAndTurn(String role) {
        Long conversationId = insertConversation(1L, 101L);
        insertMessage(conversationId, 1L, role, "原有正文");

        assertSqlState("23505", () -> insertMessage(conversationId, 1L, role, "重复正文"));
    }

    @Test
    void shouldRestrictDeletionOfConversationContainingMessages() {
        Long conversationId = insertConversation(1L, 101L);
        insertMessage(conversationId, 1L, "USER", "需要保留的测试消息");

        // 仅尝试删除临时测试库中的这一条会话，不能级联删除已有消息。
        assertSqlState("23503", () -> jdbcTemplate.update(
                "DELETE FROM agent_conversation WHERE id = ?", conversationId));
    }

    @Test
    void shouldPreserveChineseQuotesAndNewlinesInContent() {
        Long conversationId = insertConversation(1L, 101L);
        String content = "查询 'DB-CHECK-2026' 项目。\n再查看它的未处理工单。";
        insertMessage(conversationId, 1L, "USER", content);

        assertEquals(content, readMessages(conversationId).getFirst().content());
    }

    private Long insertConversation(long tenantId, long userId) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO agent_conversation (tenant_id, user_id)
                VALUES (?, ?) RETURNING id
                """, Long.class, tenantId, userId);
    }

    private Long insertMessage(long conversationId, long turnNo, String role, String content) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO agent_message (conversation_id, turn_no, role, content)
                VALUES (?, ?, ?, ?) RETURNING id
                """, Long.class, conversationId, turnNo, role, content);
    }

    private List<StoredMessage> readMessages(long conversationId) {
        return jdbcTemplate.query("""
                SELECT id, conversation_id, turn_no, role, content, created_at
                FROM agent_message
                WHERE conversation_id = ?
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
