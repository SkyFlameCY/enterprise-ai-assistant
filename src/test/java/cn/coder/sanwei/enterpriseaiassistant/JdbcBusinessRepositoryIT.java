package cn.coder.sanwei.enterpriseaiassistant;

import cn.coder.sanwei.enterpriseaiassistant.dto.ProjectSummary;
import cn.coder.sanwei.enterpriseaiassistant.dto.TicketDetail;
import cn.coder.sanwei.enterpriseaiassistant.dto.TicketSummary;
import cn.coder.sanwei.enterpriseaiassistant.repository.JdbcBusinessRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 在临时 PostgreSQL 中验证真实 Repository SQL、映射和租户隔离。
 * 数据只写入测试容器，并在每个测试完成后回滚，不连接开发数据库。
 */
@JdbcTest(properties = {
        "spring.liquibase.enabled=true",
        "spring.liquibase.drop-first=false",
        "spring.liquibase.change-log=classpath:/db/changelog/db.changelog-master.xml",
        "spring.ai.deepseek.api-key=test-key"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("business-repository-test")
@Import(JdbcBusinessRepository.class)
@Testcontainers
class JdbcBusinessRepositoryIT {

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:0.8.6-pg17-bookworm")
                    .asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("business_repository_test")
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
    private JdbcBusinessRepository repository;

    @BeforeEach
    void prepareProjects() {
        jdbcTemplate.update("""
                INSERT INTO business_project (tenant_id, project_code, name, status)
                VALUES (?, ?, ?, ?), (?, ?, ?, ?), (?, ?, ?, ?)
                """,
                1L, "EA-2026", "企业知识与流程协同 Agent", "ACTIVE",
                2L, "EA-2026", "租户B知识库项目", "PAUSED",
                2L, "OA-2026", "租户B审批系统", "ACTIVE");
        insertTicket(1L, "EA-2026", "TK-001", "租户A未处理工单", "OPEN", "HIGH", "租户A的详细说明");
        insertTicket(1L, "EA-2026", "TK-002", "租户A处理中工单", "IN_PROGRESS", "MEDIUM", "租户A正在处理");
        insertTicket(2L, "OA-2026", "TK-003", "租户B审批工单", "OPEN", "LOW", "租户B审批详细说明");
        insertTicket(2L, "EA-2026", "TK-004", "租户B未处理工单", "OPEN", "HIGH", "租户B的详细说明");
        insertTicket(2L, "EA-2026", "TK-001", "租户B同编号工单", "RESOLVED", "LOW", "租户B同编号的详细说明");
    }

    @ParameterizedTest
    @CsvSource({
            "1, EA-2026, 企业知识与流程协同 Agent, ACTIVE",
            "2, EA-2026, 租户B知识库项目, PAUSED",
            "2, OA-2026, 租户B审批系统, ACTIVE"
    })
    void shouldReadOnlyCurrentTenantProjectIncludingPausedStatus(
            long tenantId, String projectCode, String name, String status) {
        ProjectSummary result = repository.findProject(tenantId, projectCode);

        assertEquals(new ProjectSummary(projectCode, name, status), result);
    }

    @ParameterizedTest
    @CsvSource({
            "1, OA-2026",
            "1, UNKNOWN",
            "2, UNKNOWN",
            "3, EA-2026",
            "3, OA-2026"
    })
    void shouldReturnNullForInaccessibleOrMissingProject(long tenantId, String projectCode) {
        ProjectSummary result = assertDoesNotThrow(() -> repository.findProject(tenantId, projectCode));

        assertNull(result);
    }

    @ParameterizedTest
    @ValueSource(strings = {"EA-2026' OR '1'='1", "EA-%"})
    void shouldTreatProjectCodeAsBoundLiteralInsteadOfSqlOrWildcard(String projectCode) {
        assertNull(repository.findProject(1L, projectCode));
    }

    @ParameterizedTest
    @CsvSource({
            "1, EA-2026, OPEN, TK-001, 租户A未处理工单, HIGH",
            "1, EA-2026, IN_PROGRESS, TK-002, 租户A处理中工单, MEDIUM",
            "2, OA-2026, OPEN, TK-003, 租户B审批工单, LOW",
            "2, EA-2026, OPEN, TK-004, 租户B未处理工单, HIGH",
            "2, EA-2026, RESOLVED, TK-001, 租户B同编号工单, LOW"
    })
    void shouldFilterTicketListByTenantProjectAndStatus(long tenantId, String projectCode,
                                                       String status, String ticketNo,
                                                       String title, String priority) {
        assertEquals(List.of(new TicketSummary(ticketNo, projectCode, title, status, priority)),
                repository.findTickets(tenantId, projectCode, status));
    }

    @ParameterizedTest
    @CsvSource({
            "1, OA-2026, OPEN",
            "1, EA-2026, RESOLVED",
            "2, EA-2026, IN_PROGRESS",
            "3, EA-2026, OPEN",
            "1, UNKNOWN, OPEN"
    })
    void shouldReturnEmptyListForNoAccessibleMatchingTicket(long tenantId, String projectCode,
                                                           String status) {
        List<TicketSummary> result = assertDoesNotThrow(
                () -> repository.findTickets(tenantId, projectCode, status));

        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    @Test
    void shouldLimitListToTenTicketsInStableIdOrder() {
        jdbcTemplate.update("""
                INSERT INTO business_project (tenant_id, project_code, name)
                VALUES (?, ?, ?)
                """, 1L, "LIMIT-2026", "列表上限测试项目");

        List<String> insertionOrder = new ArrayList<>();
        // 编号按降序插入，确保按 id 排序不是碰巧按工单编号排序。
        for (int number = 12; number >= 1; number--) {
            String ticketNo = "LIMIT-" + number;
            insertionOrder.add(ticketNo);
            insertTicket(1L, "LIMIT-2026", ticketNo, "上限测试工单", "OPEN", "LOW", "上限测试说明");
        }

        List<TicketSummary> result = repository.findTickets(1L, "LIMIT-2026", "OPEN");

        assertEquals(insertionOrder.subList(0, 10), result.stream().map(TicketSummary::ticketNo).toList());
    }

    @ParameterizedTest
    @ValueSource(strings = {"EA-2026' OR '1'='1", "EA-%"})
    void shouldBindTicketListProjectCodeAsLiteral(String projectCode) {
        assertEquals(List.of(), repository.findTickets(1L, projectCode, "OPEN"));
    }

    @ParameterizedTest
    @CsvSource({
            "1, TK-001, 租户A未处理工单, OPEN, HIGH, 租户A的详细说明",
            "2, TK-001, 租户B同编号工单, RESOLVED, LOW, 租户B同编号的详细说明",
            "1, TK-002, 租户A处理中工单, IN_PROGRESS, MEDIUM, 租户A正在处理"
    })
    void shouldReadTicketDetailWithinCurrentTenant(long tenantId, String ticketNo, String title,
                                                  String status, String priority, String description) {
        assertEquals(new TicketDetail(ticketNo, "EA-2026", title, status, priority, description),
                repository.findTicketDetail(tenantId, ticketNo));
    }

    @ParameterizedTest
    @CsvSource({"1, TK-003", "2, TK-002", "3, TK-001", "1, UNKNOWN"})
    void shouldReturnNullForMissingOrInaccessibleTicketDetail(long tenantId, String ticketNo) {
        assertNull(assertDoesNotThrow(() -> repository.findTicketDetail(tenantId, ticketNo)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"TK-001' OR '1'='1", "TK-%"})
    void shouldBindDetailTicketNumberAsLiteral(String ticketNo) {
        assertNull(repository.findTicketDetail(1L, ticketNo));
    }

    private void insertTicket(long tenantId, String projectCode, String ticketNo, String title,
                              String status, String priority, String description) {
        jdbcTemplate.update("""
                INSERT INTO business_ticket
                    (tenant_id, project_code, ticket_no, title, status, priority, description)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, tenantId, projectCode, ticketNo, title, status, priority, description);
    }
}
