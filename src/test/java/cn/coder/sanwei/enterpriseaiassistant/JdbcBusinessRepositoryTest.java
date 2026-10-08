package cn.coder.sanwei.enterpriseaiassistant;

import cn.coder.sanwei.enterpriseaiassistant.dto.ProjectSummary;
import cn.coder.sanwei.enterpriseaiassistant.repository.JdbcBusinessRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Answers;
import org.mockito.Mock;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.dao.EmptyResultDataAccessException;

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
 * 离线验证项目查询的字段映射、参数绑定和空结果契约。
 * 查询返回行由测试模拟；不等同于真实 PostgreSQL 租户隔离测试。
 */
@ExtendWith(MockitoExtension.class)
class JdbcBusinessRepositoryTest {

    private record ProjectRow(long tenantId, ProjectSummary summary) {
    }

    private static final List<ProjectRow> ROWS = List.of(
            new ProjectRow(1L, new ProjectSummary("EA-2026", "企业知识与流程协同 Agent", "ACTIVE")),
            new ProjectRow(2L, new ProjectSummary("EA-2026", "租户B知识库项目", "PAUSED")),
            new ProjectRow(2L, new ProjectSummary("OA-2026", "租户B审批系统", "ACTIVE"))
    );

    @Mock
    private JdbcTemplate jdbcTemplate;

    private JdbcBusinessRepository repository;

    @BeforeEach
    void setUp() {
        repository = new JdbcBusinessRepository(jdbcTemplate);

        // 两种 JDBC 查询 API 都按真实返回行数语义模拟，测试不限定具体 API。
        lenient().when(jdbcTemplate.query(anyString(),
                        org.mockito.ArgumentMatchers.<RowMapper<Object>>any(), anyLong(), anyString()))
                .thenAnswer(this::mapSelectedRows);
        lenient().when(jdbcTemplate.queryForObject(anyString(),
                        org.mockito.ArgumentMatchers.<RowMapper<Object>>any(), anyLong(), anyString()))
                .thenAnswer(invocation -> {
                    List<Object> rows = mapSelectedRows(invocation);
                    if (rows.isEmpty()) {
                        throw new EmptyResultDataAccessException(1);
                    }
                    return rows.get(0);
                });
    }

    @ParameterizedTest
    @CsvSource({
            "1, EA-2026, 企业知识与流程协同 Agent, ACTIVE",
            "2, EA-2026, 租户B知识库项目, PAUSED",
            "2, OA-2026, 租户B审批系统, ACTIVE"
    })
    void shouldMapOnlySelectedFieldsToProjectSummary(
            long tenantId, String projectCode, String name, String status) {
        // 准备：模拟查询只返回 project_code、name、status 三列。
        // 执行：使用 Repository 中真实的行映射逻辑。
        Object actual = repository.findProject(tenantId, projectCode);

        // 验证：返回白名单 DTO，而不是包含内部字段的实体。
        assertEquals(new ProjectSummary(projectCode, name, status), actual);
    }

    @ParameterizedTest
    @CsvSource({
            "1, OA-2026",
            "1, UNKNOWN",
            "2, UNKNOWN",
            "3, EA-2026",
            "3, OA-2026"
    })
    void shouldReturnNullForEmptyQueryResult(long tenantId, String projectCode) {
        Object result = assertDoesNotThrow(() -> repository.findProject(tenantId, projectCode));

        assertNull(result);
    }

    @Test
    void shouldDeclareProjectSummaryAsReadContract() throws Exception {
        assertEquals(ProjectSummary.class, JdbcBusinessRepository.class
                .getMethod("findProject", long.class, String.class).getReturnType());
    }

    private List<Object> mapSelectedRows(InvocationOnMock invocation) throws SQLException {
        String sql = invocation.getArgument(0);
        RowMapper<?> mapper = invocation.getArgument(1);
        Object tenantParameter = invocation.getArgument(2);
        Object projectParameter = invocation.getArgument(3);

        assertInstanceOf(Long.class, tenantParameter);
        assertInstanceOf(String.class, projectParameter);
        String normalizedSql = sql.replaceAll("\\s+", " ").trim().toLowerCase(Locale.ROOT);
        assertTrue(normalizedSql.contains("select project_code, name, status from business_project"));
        assertTrue(normalizedSql.contains("where tenant_id = ? and project_code = ?"));

        List<Object> results = new ArrayList<>();
        for (ProjectRow row : ROWS) {
            if (row.tenantId() == (Long) tenantParameter
                    && row.summary().projectCode().equals(projectParameter)) {
                Map<String, String> columns = Map.of(
                        "project_code", row.summary().projectCode(),
                        "name", row.summary().name(),
                        "status", row.summary().status());
                ResultSet resultSet = mock(ResultSet.class, call -> {
                    if (call.getMethod().getName().startsWith("get")
                            && call.getArguments().length > 0
                            && call.getArgument(0) instanceof String column) {
                        if (!columns.containsKey(column)) {
                            throw new SQLException("查询结果中没有列: " + column, "42703");
                        }
                        return columns.get(column);
                    }
                    return Answers.RETURNS_DEFAULTS.answer(call);
                });
                try {
                    results.add(mapper.mapRow(resultSet, results.size()));
                } catch (SQLException error) {
                    throw new BadSqlGrammarException("测试行映射", sql, error);
                }
            }
        }
        return results;
    }
}
