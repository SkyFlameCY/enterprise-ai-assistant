package cn.coder.sanwei.enterpriseaiassistant;

import cn.coder.sanwei.enterpriseaiassistant.dto.TicketDetail;
import cn.coder.sanwei.enterpriseaiassistant.dto.TicketSummary;
import cn.coder.sanwei.enterpriseaiassistant.repository.JdbcBusinessRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Answers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 工单仓储的离线映射、参数绑定和返回契约测试。
 * 模拟查询结果只含 SELECT 中的列；真实 SQL 过滤、排序和上限由 IT 验证。
 */
@ExtendWith(MockitoExtension.class)
class JdbcTicketRepositoryTest {

    private record TicketRow(long tenantId, TicketDetail detail) {
    }

    private static final List<TicketRow> ROWS = List.of(
            new TicketRow(1L, new TicketDetail("TK-001", "EA-2026", "租户A未处理工单",
                    "OPEN", "HIGH", "租户A的详细说明")),
            new TicketRow(1L, new TicketDetail("TK-002", "EA-2026", "租户A处理中工单",
                    "IN_PROGRESS", "MEDIUM", "租户A正在处理")),
            new TicketRow(2L, new TicketDetail("TK-003", "OA-2026", "租户B审批工单",
                    "OPEN", "LOW", "租户B审批详细说明")),
            new TicketRow(2L, new TicketDetail("TK-004", "EA-2026", "租户B未处理工单",
                    "OPEN", "HIGH", "租户B的详细说明")),
            new TicketRow(2L, new TicketDetail("TK-001", "EA-2026", "租户B同编号工单",
                    "RESOLVED", "LOW", "租户B同编号的详细说明"))
    );

    @Mock
    private JdbcTemplate jdbcTemplate;

    private JdbcBusinessRepository repository;

    @BeforeEach
    void setUp() {
        repository = new JdbcBusinessRepository(jdbcTemplate);

        // 列表和详情分别使用三项、两项绑定参数，不同测试只使用其中一种查询。
        lenient().when(jdbcTemplate.query(anyString(),
                        org.mockito.ArgumentMatchers.<RowMapper<Object>>any(),
                        anyLong(), anyString(), anyString()))
                .thenAnswer(invocation -> {
                    String sql = invocation.getArgument(0);
                    RowMapper<?> mapper = invocation.getArgument(1);
                    long tenantId = invocation.getArgument(2);
                    String projectCode = invocation.getArgument(3);
                    String status = invocation.getArgument(4);
                    String normalized = normalize(sql);
                    assertTrue(normalized.contains(
                            "select ticket_no, project_code, title, status, priority from business_ticket"));
                    assertTrue(normalized.contains("where tenant_id = ? and project_code = ? and status = ?"));
                    assertTrue(normalized.contains("order by id asc limit 10"));

                    List<Object> result = new ArrayList<>();
                    for (TicketRow row : ROWS) {
                        if (row.tenantId() == tenantId
                                && row.detail().projectCode().equals(projectCode)
                                && row.detail().status().equals(status)) {
                            result.add(mapRow(sql, mapper, Map.of(
                                    "ticket_no", row.detail().ticketNo(),
                                    "project_code", row.detail().projectCode(),
                                    "title", row.detail().title(),
                                    "status", row.detail().status(),
                                    "priority", row.detail().priority()), result.size()));
                        }
                    }
                    return result;
                });

        lenient().when(jdbcTemplate.query(anyString(),
                        org.mockito.ArgumentMatchers.<RowMapper<Object>>any(), anyLong(), anyString()))
                .thenAnswer(invocation -> {
                    String sql = invocation.getArgument(0);
                    RowMapper<?> mapper = invocation.getArgument(1);
                    long tenantId = invocation.getArgument(2);
                    String ticketNo = invocation.getArgument(3);
                    String normalized = normalize(sql);
                    assertTrue(normalized.contains(
                            "select ticket_no, project_code, title, status, priority, description from business_ticket"));
                    assertTrue(normalized.contains("where tenant_id = ? and ticket_no = ?"));

                    List<Object> result = new ArrayList<>();
                    for (TicketRow row : ROWS) {
                        if (row.tenantId() == tenantId && row.detail().ticketNo().equals(ticketNo)) {
                            result.add(mapRow(sql, mapper, Map.of(
                                    "ticket_no", row.detail().ticketNo(),
                                    "project_code", row.detail().projectCode(),
                                    "title", row.detail().title(),
                                    "status", row.detail().status(),
                                    "priority", row.detail().priority(),
                                    "description", row.detail().description()), result.size()));
                        }
                    }
                    return result;
                });
    }

    @ParameterizedTest
    @CsvSource({
            "1, EA-2026, OPEN, TK-001, 租户A未处理工单, HIGH",
            "1, EA-2026, IN_PROGRESS, TK-002, 租户A处理中工单, MEDIUM",
            "2, OA-2026, OPEN, TK-003, 租户B审批工单, LOW",
            "2, EA-2026, OPEN, TK-004, 租户B未处理工单, HIGH",
            "2, EA-2026, RESOLVED, TK-001, 租户B同编号工单, LOW"
    })
    void shouldMapExactlyFiveListFields(long tenantId, String projectCode, String status,
                                        String ticketNo, String title, String priority) {
        List<TicketSummary> result = repository.findTickets(tenantId, projectCode, status);

        assertEquals(List.of(new TicketSummary(ticketNo, projectCode, title, status, priority)), result);
    }

    @ParameterizedTest
    @CsvSource({
            "1, OA-2026, OPEN",
            "1, EA-2026, RESOLVED",
            "2, EA-2026, IN_PROGRESS",
            "3, EA-2026, OPEN",
            "1, UNKNOWN, OPEN"
    })
    void shouldReturnEmptyListInsteadOfNull(long tenantId, String projectCode, String status) {
        List<TicketSummary> result = assertDoesNotThrow(
                () -> repository.findTickets(tenantId, projectCode, status));

        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"EA-2026' OR '1'='1", "EA-%"})
    void shouldBindListProjectCodeAsLiteral(String projectCode) {
        assertEquals(List.of(), repository.findTickets(1L, projectCode, "OPEN"));
    }

    @ParameterizedTest
    @CsvSource({
            "1, TK-001, 租户A未处理工单, OPEN, HIGH, 租户A的详细说明",
            "2, TK-001, 租户B同编号工单, RESOLVED, LOW, 租户B同编号的详细说明",
            "1, TK-002, 租户A处理中工单, IN_PROGRESS, MEDIUM, 租户A正在处理"
    })
    void shouldMapExactlySixDetailFields(long tenantId, String ticketNo, String title,
                                        String status, String priority, String description) {
        TicketDetail result = repository.findTicketDetail(tenantId, ticketNo);

        assertEquals(new TicketDetail(ticketNo, "EA-2026", title, status, priority, description), result);
    }

    @ParameterizedTest
    @CsvSource({"1, TK-003", "2, TK-002", "3, TK-001", "1, UNKNOWN"})
    void shouldReturnNullForMissingOrInaccessibleDetail(long tenantId, String ticketNo) {
        TicketDetail result = assertDoesNotThrow(() -> repository.findTicketDetail(tenantId, ticketNo));

        assertNull(result);
    }

    @ParameterizedTest
    @ValueSource(strings = {"TK-001' OR '1'='1", "TK-%"})
    void shouldBindDetailTicketNumberAsLiteral(String ticketNo) {
        assertNull(repository.findTicketDetail(1L, ticketNo));
    }

    private Object mapRow(String sql, RowMapper<?> mapper, Map<String, String> columns,
                          int rowNumber) throws SQLException {
        ResultSet resultSet = mock(ResultSet.class, invocation -> {
            if (invocation.getMethod().getName().startsWith("get")
                    && invocation.getArguments().length > 0
                    && invocation.getArgument(0) instanceof String column) {
                if (!columns.containsKey(column)) {
                    throw new SQLException("查询结果中没有列: " + column, "42703");
                }
                return columns.get(column);
            }
            return Answers.RETURNS_DEFAULTS.answer(invocation);
        });
        try {
            return mapper.mapRow(resultSet, rowNumber);
        } catch (SQLException error) {
            throw new BadSqlGrammarException("测试工单行映射", sql, error);
        }
    }

    private String normalize(String sql) {
        return sql.replaceAll("\\s+", " ").trim().toLowerCase(Locale.ROOT);
    }
}
