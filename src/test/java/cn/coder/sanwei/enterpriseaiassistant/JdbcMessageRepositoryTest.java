package cn.coder.sanwei.enterpriseaiassistant;

import cn.coder.sanwei.enterpriseaiassistant.entity.AgentMessage;
import cn.coder.sanwei.enterpriseaiassistant.repository.JdbcMessageRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
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
 * 离线验证双行 INSERT、历史/最大轮次查询、行映射、参数边界和异常传播。
 * Mockito 不执行 SQL，实际数据库原子性、归属隔离及完整轮次由 IT 验证。
 */
@ExtendWith(MockitoExtension.class)
class JdbcMessageRepositoryTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    private JdbcMessageRepository repository;

    @BeforeEach
    void setUp() {
        repository = new JdbcMessageRepository(jdbcTemplate);
    }

    @ParameterizedTest
    @ValueSource(strings = {"查询项目的工单", "问题'); DELETE FROM agent_message; --\n请继续查询"})
    void shouldInsertBothRolesWithBoundParametersInOneStatement(String userContent) {
        // 准备：两段正文不同，以便发现参数错位或误用用户正文作为回答。
        String assistantContent = "查到 1 条 OPEN 工单";
        when(jdbcTemplate.update(anyString(), eq(77L), eq(3L), eq(userContent),
                eq(77L), eq(3L), eq(assistantContent))).thenReturn(2);

        // 执行：调用真实 Repository 方法。
        int affectedRows = repository.insertTurn(77L, 3L, userContent, assistantContent);

        // 验证：只有一次 JDBC 写入，两个角色固定在 SQL 中，正文均为绑定参数。
        assertEquals(2, affectedRows);
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).update(sql.capture(), eq(77L), eq(3L), eq(userContent),
                eq(77L), eq(3L), eq(assistantContent));
        assertEquals(normalize("""
                INSERT INTO agent_message (conversation_id, turn_no, role, content)
                VALUES (?, ?, 'USER', ?), (?, ?, 'ASSISTANT', ?)
                """), normalize(sql.getValue()));
        verifyNoMoreInteractions(jdbcTemplate);
    }

    @Test
    void shouldPropagateDatabaseFailureInsteadOfReturningSuccess() {
        var failure = new DataAccessResourceFailureException("test database unavailable");
        when(jdbcTemplate.update(anyString(), eq(77L), eq(3L), eq("问题"),
                eq(77L), eq(3L), eq("回答"))).thenThrow(failure);

        assertSame(failure, assertThrows(DataAccessResourceFailureException.class,
                () -> repository.insertTurn(77L, 3L, "问题", "回答")));
        verify(jdbcTemplate).update(anyString(), eq(77L), eq(3L), eq("问题"),
                eq(77L), eq(3L), eq("回答"));
        verifyNoMoreInteractions(jdbcTemplate);
    }

    @Test
    void shouldMapEveryHistoryFieldWithOffsetTimestamp() throws Exception {
        OffsetDateTime createdAt = OffsetDateTime.parse("2026-10-09T09:00:00+08:00");
        ResultSet row = mock(ResultSet.class);
        when(row.getLong("id")).thenReturn(11L);
        when(row.getLong("conversation_id")).thenReturn(77L);
        when(row.getLong("turn_no")).thenReturn(3L);
        when(row.getString("role")).thenReturn("USER");
        when(row.getString("content")).thenReturn("查询项目");
        when(row.getObject("created_at", OffsetDateTime.class)).thenReturn(createdAt);
        when(jdbcTemplate.query(anyString(),
                org.mockito.ArgumentMatchers.<RowMapper<AgentMessage>>any(),
                eq(77L), eq(1L), eq(101L), eq(5)))
                .thenAnswer(invocation -> {
                    RowMapper<AgentMessage> mapper = invocation.getArgument(1);
                    return List.of(mapper.mapRow(row, 0));
                });

        List<AgentMessage> messages = repository.findRecentOwnedMessages(77L, 1L, 101L, 5);

        assertEquals(1, messages.size());
        AgentMessage message = messages.getFirst();
        assertAll(
                () -> assertEquals(11L, message.getId()),
                () -> assertEquals(77L, message.getConversationId()),
                () -> assertEquals(3L, message.getTurnNo()),
                () -> assertEquals("USER", message.getRole()),
                () -> assertEquals("查询项目", message.getContent()),
                () -> assertEquals(createdAt, message.getCreatedAt())
        );
        assertBoundHistoryQuery(5);
    }

    @Test
    void shouldReturnEmptyListWhenQueryHasNoAccessibleCompleteHistory() {
        stubEmptyHistory(5);

        List<AgentMessage> messages = assertDoesNotThrow(
                () -> repository.findRecentOwnedMessages(77L, 1L, 101L, 5));

        assertNotNull(messages);
        assertTrue(messages.isEmpty());
        assertBoundHistoryQuery(5);
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 20})
    void shouldAllowBothTurnLimitBoundaries(int turnLimit) {
        stubEmptyHistory(turnLimit);

        assertTrue(repository.findRecentOwnedMessages(77L, 1L, 101L, turnLimit).isEmpty());
        assertBoundHistoryQuery(turnLimit);
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 0, 21, Integer.MIN_VALUE, Integer.MAX_VALUE})
    void shouldRejectInvalidTurnLimitBeforeCallingJdbc(int turnLimit) {
        assertThrows(IllegalArgumentException.class,
                () -> repository.findRecentOwnedMessages(77L, 1L, 101L, turnLimit));

        verifyNoInteractions(jdbcTemplate);
    }

    @Test
    void shouldNotHideHistoryQueryFailureAsEmptyHistory() {
        var failure = new DataAccessResourceFailureException("test database unavailable");
        when(jdbcTemplate.query(anyString(),
                org.mockito.ArgumentMatchers.<RowMapper<AgentMessage>>any(),
                eq(77L), eq(1L), eq(101L), eq(5))).thenThrow(failure);

        assertSame(failure, assertThrows(DataAccessResourceFailureException.class,
                () -> repository.findRecentOwnedMessages(77L, 1L, 101L, 5)));
        assertBoundHistoryQuery(5);
    }

    @ParameterizedTest
    @ValueSource(longs = {0L, 1L, 7L, 2147483648L, Long.MAX_VALUE})
    void shouldBindOwnershipAndReturnMaximumTurnWithoutIncrementingOrTruncating(long maximumTurn) {
        // 准备：这里只模拟查询结果，真实 MAX/COALESCE 的行为由 IT 验证。
        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class), eq(77L), eq(1L), eq(101L)))
                .thenReturn(maximumTurn);

        long result = repository.findMaxOwnedTurnNo(77L, 1L, 101L);

        assertEquals(maximumTurn, result);
        assertBoundMaximumTurnQuery();
    }

    @ParameterizedTest
    @MethodSource("invalidMaximumTurnArguments")
    void shouldRejectNonPositiveMaximumTurnQueryIdsBeforeJdbc(long conversationId, long tenantId, long userId) {
        assertThrowsExactly(IllegalArgumentException.class,
                () -> repository.findMaxOwnedTurnNo(conversationId, tenantId, userId));

        verifyNoInteractions(jdbcTemplate);
    }

    static Stream<Arguments> invalidMaximumTurnArguments() {
        return Stream.of(
                Arguments.of(0L, 1L, 101L),
                Arguments.of(-1L, 1L, 101L),
                Arguments.of(Long.MIN_VALUE, 1L, 101L),
                Arguments.of(77L, 0L, 101L),
                Arguments.of(77L, -1L, 101L),
                Arguments.of(77L, Long.MIN_VALUE, 101L),
                Arguments.of(77L, 1L, 0L),
                Arguments.of(77L, 1L, -1L),
                Arguments.of(77L, 1L, Long.MIN_VALUE)
        );
    }

    @Test
    void shouldPropagateMaximumTurnQueryFailureInsteadOfPretendingThereAreNoMessages() {
        var failure = new DataAccessResourceFailureException("test database unavailable");
        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class), eq(77L), eq(1L), eq(101L)))
                .thenThrow(failure);

        assertSame(failure, assertThrowsExactly(DataAccessResourceFailureException.class,
                () -> repository.findMaxOwnedTurnNo(77L, 1L, 101L)));
        assertBoundMaximumTurnQuery();
    }

    private void assertBoundMaximumTurnQuery() {
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).queryForObject(sql.capture(), eq(Long.class), eq(77L), eq(1L), eq(101L));
        assertEquals(normalize("""
                SELECT COALESCE(MAX(m.turn_no), 0)
                FROM agent_message m
                JOIN agent_conversation c ON c.id = m.conversation_id
                WHERE c.id = ? AND c.tenant_id = ? AND c.user_id = ?
                """), normalize(sql.getValue()));
        verifyNoMoreInteractions(jdbcTemplate);
    }

    private void stubEmptyHistory(int turnLimit) {
        when(jdbcTemplate.query(anyString(),
                org.mockito.ArgumentMatchers.<RowMapper<AgentMessage>>any(),
                eq(77L), eq(1L), eq(101L), eq(turnLimit))).thenReturn(List.of());
    }

    private void assertBoundHistoryQuery(int turnLimit) {
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).query(sql.capture(),
                org.mockito.ArgumentMatchers.<RowMapper<AgentMessage>>any(),
                eq(77L), eq(1L), eq(101L), eq(turnLimit));
        assertEquals(normalize("""
                WITH recent_turns AS (
                    SELECT m.conversation_id, m.turn_no
                    FROM agent_message m
                    JOIN agent_conversation c ON c.id = m.conversation_id
                    WHERE c.id = ? AND c.tenant_id = ? AND c.user_id = ?
                    GROUP BY m.conversation_id, m.turn_no
                    HAVING COUNT(*) = 2
                    ORDER BY m.turn_no DESC
                    LIMIT ?
                )
                SELECT m.id, m.conversation_id, m.turn_no, m.role, m.content, m.created_at
                FROM agent_message m
                JOIN recent_turns r ON r.conversation_id = m.conversation_id
                    AND r.turn_no = m.turn_no
                ORDER BY m.turn_no ASC, CASE m.role WHEN 'USER' THEN 0 ELSE 1 END
                """), normalize(sql.getValue()));
        verifyNoMoreInteractions(jdbcTemplate);
    }

    private static String normalize(String sql) {
        return sql.replaceAll("\\s+", " ").trim()
                .replaceAll("\\s*([(),])\\s*", "$1")
                .toLowerCase(Locale.ROOT);
    }
}
