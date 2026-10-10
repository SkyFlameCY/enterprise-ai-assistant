package cn.coder.sanwei.enterpriseaiassistant;

import cn.coder.sanwei.enterpriseaiassistant.entity.AgentConversation;
import cn.coder.sanwei.enterpriseaiassistant.repository.JdbcConversationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 离线检查真实 Repository 的参数绑定、行映射和异常传播。
 * JdbcTemplate 由 Mockito 模拟，不作为真实数据库归属隔离的证据。
 */
@ExtendWith(MockitoExtension.class)
class JdbcConversationRepositoryTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    private JdbcConversationRepository repository;

    @BeforeEach
    void setUp() {
        repository = new JdbcConversationRepository(jdbcTemplate);
    }

    @ParameterizedTest
    @ValueSource(strings = {"项目查询", "标题'); DELETE FROM agent_conversation; --"})
    void shouldBindOwnerAndTitleAndReturnGeneratedId(String title) {
        // 准备：数据库生成会话编号，标题即使包含 SQL 字符也只是参数。
        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class), eq(1L), eq(101L), eq(title)))
                .thenReturn(77L);

        // 执行：调用真实 Repository 方法。
        Long id = repository.createConversation(1L, 101L, title);

        // 验证：编号直接返回，身份与标题不拼接进 SQL。
        assertEquals(77L, id);
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).queryForObject(sql.capture(), eq(Long.class), eq(1L), eq(101L), eq(title));
        assertEquals(normalize("""
                INSERT INTO agent_conversation (tenant_id, user_id, title)
                VALUES (?, ?, ?) RETURNING id
                """), normalize(sql.getValue()));
    }

    @ParameterizedTest
    @ValueSource(longs = {0L, 1L, 7L, 2147483648L, Long.MAX_VALUE})
    void shouldMapAllConversationFieldsIncludingVersionAndOffsetTimestamps(long version) throws Exception {
        // 准备：提供 SQL 实际选择的八列，版本包含 0、非零和超过 int 范围的值。
        OffsetDateTime createdAt = OffsetDateTime.parse("2026-10-08T14:30:25+08:00");
        OffsetDateTime updatedAt = OffsetDateTime.parse("2026-10-08T15:40:25+08:00");
        ResultSet row = mock(ResultSet.class);
        when(row.getLong("id")).thenReturn(77L);
        when(row.getLong("tenant_id")).thenReturn(1L);
        when(row.getLong("user_id")).thenReturn(101L);
        when(row.getString("title")).thenReturn("项目查询");
        when(row.getString("status")).thenReturn("ACTIVE");
        when(row.getLong("version")).thenReturn(version);
        when(row.getObject("created_at", OffsetDateTime.class)).thenReturn(createdAt);
        when(row.getObject("updated_at", OffsetDateTime.class)).thenReturn(updatedAt);
        when(jdbcTemplate.query(anyString(),
                org.mockito.ArgumentMatchers.<RowMapper<AgentConversation>>any(),
                eq(77L), eq(1L), eq(101L)))
                .thenAnswer(invocation -> {
                    RowMapper<AgentConversation> mapper = invocation.getArgument(1);
                    return List.of(mapper.mapRow(row, 0));
                });

        // 执行：运行 Repository 自己的 RowMapper，而不是直接返回预造实体。
        AgentConversation result = repository.findOwnedConversation(77L, 1L, 101L);

        // 验证：列名、类型、归属信息和时间都正确。
        assertNotNull(result);
        assertAll(
                () -> assertEquals(77L, result.getId()),
                () -> assertEquals(1L, result.getTenantId()),
                () -> assertEquals(101L, result.getUserId()),
                () -> assertEquals("项目查询", result.getTitle()),
                () -> assertEquals("ACTIVE", result.getStatus()),
                () -> assertEquals(version, result.getVersion()),
                () -> assertEquals(createdAt, result.getCreatedAt()),
                () -> assertEquals(updatedAt, result.getUpdatedAt())
        );
        assertOwnedQuerySql(77L, 1L, 101L);
    }

    @Test
    void shouldReturnNullInsteadOfThrowingWhenQueryHasNoOwnedRow() {
        when(jdbcTemplate.query(anyString(),
                org.mockito.ArgumentMatchers.<RowMapper<AgentConversation>>any(),
                eq(77L), eq(1L), eq(104L)))
                .thenReturn(List.of());

        assertNull(assertDoesNotThrow(() -> repository.findOwnedConversation(77L, 1L, 104L)));
        assertOwnedQuerySql(77L, 1L, 104L);
    }

    @Test
    void shouldNotHideDatabaseFailureWhenCreatingConversation() {
        var failure = new DataAccessResourceFailureException("test database unavailable");
        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class), eq(1L), eq(101L), eq("项目查询")))
                .thenThrow(failure);

        assertSame(failure, assertThrows(DataAccessResourceFailureException.class,
                () -> repository.createConversation(1L, 101L, "项目查询")));
    }

    @Test
    void shouldNotTreatDatabaseFailureAsMissingConversation() {
        var failure = new DataAccessResourceFailureException("test database unavailable");
        when(jdbcTemplate.query(anyString(),
                org.mockito.ArgumentMatchers.<RowMapper<AgentConversation>>any(),
                eq(77L), eq(1L), eq(101L)))
                .thenThrow(failure);

        assertSame(failure, assertThrows(DataAccessResourceFailureException.class,
                () -> repository.findOwnedConversation(77L, 1L, 101L)));
    }

    @ParameterizedTest
    @CsvSource({
            "0, 0", "0, 1", "7, 1", "2147483648, 1", "9223372036854775806, 1"
    })
    void shouldBindOwnerAndExpectedVersionAndReturnUpdateCount(long expectedVersion, int affectedRows) {
        // 准备：这里只模拟 JDBC 的影响行数，不模拟真实数据库的条件判断。
        when(jdbcTemplate.update(anyString(), eq(77L), eq(1L), eq(101L), eq(expectedVersion)))
                .thenReturn(affectedRows);

        // 执行：真实 Repository 必须只做一次带条件 UPDATE。
        int result = repository.advanceVersionIfActiveOwned(77L, 1L, 101L, expectedVersion);

        // 验证：不额外查库，不把 0 转成异常，也不漏掉状态/归属/旧版本条件。
        assertEquals(affectedRows, result);
        assertVersionUpdateSql(expectedVersion);
        verifyNoMoreInteractions(jdbcTemplate);
    }

    @ParameterizedTest
    @MethodSource("invalidVersionUpdateArguments")
    void shouldRejectInvalidVersionUpdateArgumentsBeforeJdbc(
            long conversationId, long tenantId, long userId, long expectedVersion) {
        assertThrowsExactly(IllegalArgumentException.class,
                () -> repository.advanceVersionIfActiveOwned(
                        conversationId, tenantId, userId, expectedVersion));

        verifyNoInteractions(jdbcTemplate);
    }

    static Stream<Arguments> invalidVersionUpdateArguments() {
        return Stream.of(
                Arguments.of(0L, 1L, 101L, 0L),
                Arguments.of(-1L, 1L, 101L, 0L),
                Arguments.of(Long.MIN_VALUE, 1L, 101L, 0L),
                Arguments.of(77L, 0L, 101L, 0L),
                Arguments.of(77L, -1L, 101L, 0L),
                Arguments.of(77L, Long.MIN_VALUE, 101L, 0L),
                Arguments.of(77L, 1L, 0L, 0L),
                Arguments.of(77L, 1L, -1L, 0L),
                Arguments.of(77L, 1L, Long.MIN_VALUE, 0L),
                Arguments.of(77L, 1L, 101L, -1L),
                Arguments.of(77L, 1L, 101L, Long.MIN_VALUE),
                Arguments.of(77L, 1L, 101L, Long.MAX_VALUE)
        );
    }

    @Test
    void shouldPropagateVersionUpdateDatabaseFailureInsteadOfReturningZero() {
        var failure = new DataAccessResourceFailureException("test database unavailable");
        when(jdbcTemplate.update(anyString(), eq(77L), eq(1L), eq(101L), eq(0L)))
                .thenThrow(failure);

        assertSame(failure, assertThrowsExactly(DataAccessResourceFailureException.class,
                () -> repository.advanceVersionIfActiveOwned(77L, 1L, 101L, 0L)));

        assertVersionUpdateSql(0L);
        verifyNoMoreInteractions(jdbcTemplate);
    }

    private void assertVersionUpdateSql(long expectedVersion) {
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).update(sql.capture(), eq(77L), eq(1L), eq(101L), eq(expectedVersion));
        assertEquals(normalize("""
                UPDATE agent_conversation
                SET version = version + 1, updated_at = CURRENT_TIMESTAMP
                WHERE id = ? AND tenant_id = ? AND user_id = ?
                  AND status = 'ACTIVE' AND version = ?
                """), normalize(sql.getValue()));
    }

    private void assertOwnedQuerySql(long conversationId, long tenantId, long userId) {
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).query(sql.capture(),
                org.mockito.ArgumentMatchers.<RowMapper<AgentConversation>>any(),
                eq(conversationId), eq(tenantId), eq(userId));
        assertEquals(normalize("""
                SELECT id, tenant_id, user_id, title, status, version, created_at, updated_at
                FROM agent_conversation
                WHERE id = ? AND tenant_id = ? AND user_id = ?
                """), normalize(sql.getValue()));
    }

    private static String normalize(String sql) {
        return sql.replaceAll("\\s+", " ").trim()
                .replaceAll("\\s*([(),])\\s*", "$1")
                .toLowerCase(Locale.ROOT);
    }
}
