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

import static org.junit.jupiter.api.Assertions.*;

/**
 * 在临时 PostgreSQL 容器中运行真实 Liquibase 迁移并验证业务表约束。
 * 不使用开发数据库，不调用模型；每个测试的数据通过事务回滚隔离。
 * 这是数据库集成测试，不是纯单元测试；运行此类需要 Docker。
 */
@JdbcTest(properties = {
        "spring.liquibase.enabled=true",
        "spring.liquibase.drop-first=false",
        "spring.liquibase.change-log=classpath:/db/changelog/db.changelog-master.xml",
        "spring.ai.deepseek.api-key=test-key"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("business-schema-test")
@Testcontainers
class BusinessSchemaIT {

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:0.8.6-pg17-bookworm")
                    .asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("business_schema_test")
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
    void shouldExecuteBusinessMigrationFromMasterChangelog() {
        assertEquals("EXECUTED", jdbcTemplate.queryForObject("""
                SELECT exectype FROM databasechangelog
                WHERE id = ? AND author = ?
                """, String.class, "003-create-business-tables", "sanwei"));
        assertEquals(2L, jdbcTemplate.queryForObject("""
                SELECT count(*) FROM information_schema.tables
                WHERE table_schema = current_schema()
                  AND table_name IN ('business_project', 'business_ticket')
                """, Long.class));
    }

    @Test
    void shouldAllowSameProjectAndTicketCodesInDifferentTenants() {
        // 准备：两个租户各自拥有同编号的项目。
        Long projectA = insertProject(1L, "EA-2026");
        Long projectB = insertProject(2L, "EA-2026");

        // 执行：两个租户各自创建同编号的工单。
        Long ticketA = insertTicket(1L, "EA-2026", "TK-001");
        Long ticketB = insertTicket(2L, "EA-2026", "TK-001");

        // 验证：业务编号不必全局唯一，但内部主键不同。
        assertNotEquals(projectA, projectB);
        assertNotEquals(ticketA, ticketB);
        assertEquals(2L, jdbcTemplate.queryForObject("""
                SELECT count(*) FROM business_ticket WHERE ticket_no = ?
                """, Long.class, "TK-001"));
    }

    @Test
    void shouldRejectDuplicateProjectCodeWithinSameTenant() {
        insertProject(1L, "EA-2026");

        assertSqlState("23505", () -> insertProject(1L, "EA-2026"));
    }

    @Test
    void shouldRejectDuplicateTicketNumberWithinSameTenant() {
        insertProject(1L, "EA-2026");
        insertTicket(1L, "EA-2026", "TK-001");

        assertSqlState("23505", () -> insertTicket(1L, "EA-2026", "TK-001"));
    }

    @Test
    void shouldRejectTicketReferencingAnotherTenantsProject() {
        // 项目编号存在，但只属于租户 B。
        insertProject(2L, "OA-2026");

        assertSqlState("23503", () -> insertTicket(1L, "OA-2026", "TK-001"));
    }

    @Test
    void shouldRejectTicketReferencingMissingProject() {
        assertSqlState("23503", () -> insertTicket(1L, "MISSING", "TK-001"));
    }

    @Test
    void shouldRestrictDeletingProjectThatStillHasTickets() {
        Long projectId = insertProject(1L, "EA-2026");
        insertTicket(1L, "EA-2026", "TK-001");

        assertSqlState("23503", () -> jdbcTemplate.update(
                "DELETE FROM business_project WHERE id = ?", projectId));
    }

    @Test
    void shouldProvideDefaultStatusesPriorityAndTimestamps() {
        Long projectId = insertProject(1L, "EA-2026");
        Long ticketId = insertTicket(1L, "EA-2026", "TK-001");

        var project = jdbcTemplate.queryForMap("""
                SELECT status, created_at, updated_at FROM business_project WHERE id = ?
                """, projectId);
        var ticket = jdbcTemplate.queryForMap("""
                SELECT status, priority, created_at, updated_at FROM business_ticket WHERE id = ?
                """, ticketId);

        assertAll(
                () -> assertEquals("ACTIVE", project.get("status")),
                () -> assertEquals("OPEN", ticket.get("status")),
                () -> assertEquals("MEDIUM", ticket.get("priority")),
                () -> assertNotNull(project.get("created_at")),
                () -> assertNotNull(project.get("updated_at")),
                () -> assertNotNull(ticket.get("created_at")),
                () -> assertNotNull(ticket.get("updated_at"))
        );
    }

    @Test
    void shouldCreateIndexMatchingTenantProjectStatusAndStableOrdering() {
        String definition = jdbcTemplate.queryForObject("""
                SELECT indexdef FROM pg_indexes
                WHERE schemaname = current_schema() AND indexname = ?
                """, String.class, "idx_business_ticket_tenant_project_status_id");

        assertNotNull(definition);
        assertTrue(definition.contains("(tenant_id, project_code, status, id)"));
    }

    @ParameterizedTest
    @ValueSource(longs = {0L, -1L})
    void shouldRejectNonPositiveProjectTenant(long tenantId) {
        assertSqlState("23514", () -> insertProject(tenantId, "EA-2026"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"OPEN", "DRAFT", "active"})
    void shouldRejectInvalidProjectStatus(String status) {
        assertSqlState("23514", () -> jdbcTemplate.update("""
                INSERT INTO business_project (tenant_id, project_code, name, status)
                VALUES (?, ?, ?, ?)
                """, 1L, "EA-2026", "测试项目", status));
    }

    @ParameterizedTest
    @ValueSource(strings = {"ACTIVE", "PAUSED", "open"})
    void shouldRejectInvalidTicketStatus(String status) {
        insertProject(1L, "EA-2026");
        Long ticketId = insertTicket(1L, "EA-2026", "TK-001");

        assertSqlState("23514", () -> jdbcTemplate.update(
                "UPDATE business_ticket SET status = ? WHERE id = ?", status, ticketId));
    }

    @ParameterizedTest
    @ValueSource(strings = {"URGENT", "high"})
    void shouldRejectInvalidTicketPriority(String priority) {
        insertProject(1L, "EA-2026");
        Long ticketId = insertTicket(1L, "EA-2026", "TK-001");

        assertSqlState("23514", () -> jdbcTemplate.update(
                "UPDATE business_ticket SET priority = ? WHERE id = ?", priority, ticketId));
    }

    private Long insertProject(long tenantId, String projectCode) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO business_project (tenant_id, project_code, name)
                VALUES (?, ?, ?) RETURNING id
                """, Long.class, tenantId, projectCode, "测试项目");
    }

    private Long insertTicket(long tenantId, String projectCode, String ticketNo) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO business_ticket (tenant_id, project_code, ticket_no, title, description)
                VALUES (?, ?, ?, ?, ?) RETURNING id
                """, Long.class, tenantId, projectCode, ticketNo, "测试工单", "测试详细说明");
    }

    private void assertSqlState(String expectedState, Runnable operation) {
        DataAccessException error = assertThrows(DataAccessException.class, operation::run);
        SQLException sqlError = assertInstanceOf(SQLException.class, error.getMostSpecificCause());
        assertEquals(expectedState, sqlError.getSQLState());
    }
}
