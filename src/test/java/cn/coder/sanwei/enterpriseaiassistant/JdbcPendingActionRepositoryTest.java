package cn.coder.sanwei.enterpriseaiassistant;

import cn.coder.sanwei.enterpriseaiassistant.entity.PendingAgentAction;
import cn.coder.sanwei.enterpriseaiassistant.repository.JdbcPendingActionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
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
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 运行真实 Repository，模拟 JdbcTemplate：核对 SQL、绑定顺序和 RowMapper。
 * 不连接 PostgreSQL，不证明真实 ON CONFLICT、约束、归属隔离或事务回滚。
 */
@ExtendWith(MockitoExtension.class)
class JdbcPendingActionRepositoryTest {

    private static final UUID ID = UUID.fromString("d11daec1-88df-42cf-9a94-118ba9c32d39");
    private static final UUID REQUEST = UUID.fromString("c27ff6ce-04d3-44ce-a665-f55ef67cb684");
    private static final long TENANT = 2L;
    private static final long USER = 502L;
    private static final long CONVERSATION = 77L;
    private static final String JSON = "{\"projectCode\":\"EA-2026\",\"title\":\"来源缺失\",\"description\":\"实际说明\",\"priority\":\"HIGH\"}";
    private static final String HASH = "ab".repeat(32);
    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-10-27T09:00:00+08:00");
    private static final OffsetDateTime EXPIRES = NOW.plusMinutes(10);

    @Mock
    private JdbcTemplate jdbc;
    private JdbcPendingActionRepository repository;

    @BeforeEach
    void setUp() {
        repository = new JdbcPendingActionRepository(jdbc);
    }

    @Test
    void shouldRejectNullActionBeforeJdbc() {
        assertThrows(NullPointerException.class, () -> repository.insertDraftIfAbsent(null));
        verifyNoInteractions(jdbc);
    }

    @Test
    void shouldRequireServiceGeneratedIdBeforeJdbc() {
        assertThrows(NullPointerException.class,
                () -> repository.insertDraftIfAbsent(new PendingAgentAction()));
        verifyNoInteractions(jdbc);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void shouldBindEightInsertValuesAndReturnNullOnlyForDeduplicatedDraft(boolean inserted) {
        PendingAgentAction action = draft();
        when(jdbc.queryForList(anyString(), eq(UUID.class),
                eq(ID), eq(TENANT), eq(USER), eq(CONVERSATION), eq(REQUEST),
                eq(JSON), eq(HASH), eq(EXPIRES)))
                .thenReturn(inserted ? List.of(ID) : List.of());

        assertEquals(inserted ? ID : null, repository.insertDraftIfAbsent(action));
        assertEquals(ID, action.getId(), "Repository 不重新生成服务端 ID");

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).queryForList(sql.capture(), eq(UUID.class),
                eq(ID), eq(TENANT), eq(USER), eq(CONVERSATION), eq(REQUEST),
                eq(JSON), eq(HASH), eq(EXPIRES));
        assertEquals(normalize("""
                INSERT INTO agent_pending_action (
                    id, tenant_id, user_id, conversation_id, request_id,
                    source_turn_no, action_type, status, params_json,
                    preview_hash, expires_at, result_json
                ) VALUES (
                    ?, ?, ?, ?, ?, NULL, 'CREATE_TICKET', 'DRAFT', CAST(? AS jsonb), ?, ?, NULL
                ) ON CONFLICT (
                    tenant_id, user_id, request_id, action_type, preview_hash
                ) DO NOTHING RETURNING id
                """), normalize(sql.getValue()));
        assertFalse(sql.getValue().contains(JSON));
        verifyNoMoreInteractions(jdbc);
    }

    @Test
    void shouldPropagateInsertFailureInsteadOfTreatingItAsDuplicate() {
        var failure = new DataAccessResourceFailureException("offline failure");
        when(jdbc.queryForList(anyString(), eq(UUID.class),
                eq(ID), eq(TENANT), eq(USER), eq(CONVERSATION), eq(REQUEST),
                eq(JSON), eq(HASH), eq(EXPIRES))).thenThrow(failure);

        assertSame(failure, assertThrows(DataAccessResourceFailureException.class,
                () -> repository.insertDraftIfAbsent(draft())));
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void shouldBindCompleteDeduplicationKeyAndHandleMissingOriginal(boolean found) {
        PendingAgentAction original = draft();
        when(jdbc.query(anyString(), org.mockito.ArgumentMatchers.<RowMapper<PendingAgentAction>>any(),
                eq(TENANT), eq(USER), eq(REQUEST), eq("CREATE_TICKET"), eq(HASH)))
                .thenReturn(found ? List.of(original) : List.of());

        assertSame(found ? original : null,
                repository.findByRequestAndHash(TENANT, USER, REQUEST, "CREATE_TICKET", HASH));

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).query(sql.capture(), org.mockito.ArgumentMatchers.<RowMapper<PendingAgentAction>>any(),
                eq(TENANT), eq(USER), eq(REQUEST), eq("CREATE_TICKET"), eq(HASH));
        assertTrue(normalize(sql.getValue()).endsWith(normalize("""
                WHERE tenant_id = ? AND user_id = ? AND request_id = ?
                  AND action_type = ? AND preview_hash = ?
                """)));
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(longs = {1L, 2147483648L})
    void shouldMapUuidNullableTurnJsonAndOffsetTimesFromOwnedRow(Long turnNo) throws Exception {
        ResultSet row = mock(ResultSet.class);
        when(row.getObject("id", UUID.class)).thenReturn(ID);
        when(row.getLong("tenant_id")).thenReturn(TENANT);
        when(row.getLong("user_id")).thenReturn(USER);
        when(row.getLong("conversation_id")).thenReturn(CONVERSATION);
        when(row.getObject("request_id", UUID.class)).thenReturn(REQUEST);
        when(row.getObject("source_turn_no", Long.class)).thenReturn(turnNo);
        when(row.getString("action_type")).thenReturn("CREATE_TICKET");
        when(row.getString("status")).thenReturn(turnNo == null ? "DRAFT" : "PENDING_CONFIRMATION");
        when(row.getString("params_json")).thenReturn(JSON);
        when(row.getString("preview_hash")).thenReturn(HASH);
        when(row.getObject("expires_at", OffsetDateTime.class)).thenReturn(EXPIRES);
        when(row.getString("result_json")).thenReturn(null);
        when(row.getObject("created_at", OffsetDateTime.class)).thenReturn(NOW);
        when(row.getObject("updated_at", OffsetDateTime.class)).thenReturn(NOW.plusSeconds(1));
        when(jdbc.query(anyString(), org.mockito.ArgumentMatchers.<RowMapper<PendingAgentAction>>any(),
                eq(ID), eq(TENANT), eq(USER)))
                .thenAnswer(call -> {
                    RowMapper<PendingAgentAction> mapper = call.getArgument(1);
                    return List.of(mapper.mapRow(row, 0));
                });

        PendingAgentAction result = repository.findOwnedById(ID, TENANT, USER);

        assertNotNull(result);
        assertAll(
                () -> assertEquals(ID, result.getId()),
                () -> assertEquals(TENANT, result.getTenantId()),
                () -> assertEquals(USER, result.getUserId()),
                () -> assertEquals(CONVERSATION, result.getConversationId()),
                () -> assertEquals(REQUEST, result.getRequestId()),
                () -> assertEquals(turnNo, result.getSourceTurnNo()),
                () -> assertEquals("CREATE_TICKET", result.getActionType()),
                () -> assertEquals(turnNo == null ? "DRAFT" : "PENDING_CONFIRMATION", result.getStatus()),
                () -> assertEquals(JSON, result.getParamsJson()),
                () -> assertEquals(HASH, result.getPreviewHash()),
                () -> assertEquals(EXPIRES, result.getExpiresAt()),
                () -> assertNull(result.getResultJson()),
                () -> assertEquals(NOW, result.getCreatedAt()),
                () -> assertEquals(NOW.plusSeconds(1), result.getUpdatedAt())
        );
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).query(sql.capture(), org.mockito.ArgumentMatchers.<RowMapper<PendingAgentAction>>any(),
                eq(ID), eq(TENANT), eq(USER));
        assertTrue(normalize(sql.getValue()).endsWith("where id = ? and tenant_id = ? and user_id = ?"));
        verify(row, never()).getLong("source_turn_no");
    }

    @Test
    void shouldReturnNullForMissingOwnedAction() {
        when(jdbc.query(anyString(), org.mockito.ArgumentMatchers.<RowMapper<PendingAgentAction>>any(),
                eq(ID), eq(TENANT), eq(USER))).thenReturn(List.of());

        assertNull(repository.findOwnedById(ID, TENANT, USER));
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void shouldBindCurrentRequestConversationOwnerAndDraftStatus(boolean hasDrafts) {
        List<PendingAgentAction> rows = hasDrafts ? List.of(draft(), draft()) : List.of();
        when(jdbc.query(anyString(), org.mockito.ArgumentMatchers.<RowMapper<PendingAgentAction>>any(),
                eq(CONVERSATION), eq(REQUEST), eq(TENANT), eq(USER))).thenReturn(rows);

        assertSame(rows, repository.findDraftByRequest(CONVERSATION, REQUEST, TENANT, USER));

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).query(sql.capture(), org.mockito.ArgumentMatchers.<RowMapper<PendingAgentAction>>any(),
                eq(CONVERSATION), eq(REQUEST), eq(TENANT), eq(USER));
        assertTrue(normalize(sql.getValue()).endsWith(normalize("""
                WHERE conversation_id = ? AND request_id = ? AND tenant_id = ? AND user_id = ?
                  AND status = 'DRAFT' ORDER BY created_at ASC, id ASC
                """)));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1})
    void shouldConditionallyActivateWithActualTurnAndStrictExpiryAndReturnAffectedRows(int affected) {
        when(jdbc.update(anyString(), eq(2147483648L), eq(NOW), eq(ID), eq(CONVERSATION),
                eq(REQUEST), eq(TENANT), eq(USER), eq(NOW))).thenReturn(affected);

        assertEquals(affected, repository.activeDraft(ID, CONVERSATION, REQUEST, TENANT, USER, 2147483648L, NOW));

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).update(sql.capture(), eq(2147483648L), eq(NOW), eq(ID), eq(CONVERSATION),
                eq(REQUEST), eq(TENANT), eq(USER), eq(NOW));
        assertEquals(normalize("""
                UPDATE agent_pending_action
                SET status = 'PENDING_CONFIRMATION', source_turn_no = ?, updated_at = ?
                WHERE id = ? AND conversation_id = ? AND request_id = ?
                  AND tenant_id = ? AND user_id = ? AND status = 'DRAFT' AND expires_at > ?
                """), normalize(sql.getValue()));
        verifyNoMoreInteractions(jdbc);
    }

    @Test
    void shouldPropagateActivationFailureRatherThanReturningZero() {
        var failure = new DataAccessResourceFailureException("offline failure");
        when(jdbc.update(anyString(), eq(1L), eq(NOW), eq(ID), eq(CONVERSATION),
                eq(REQUEST), eq(TENANT), eq(USER), eq(NOW))).thenThrow(failure);

        assertSame(failure, assertThrows(DataAccessResourceFailureException.class,
                () -> repository.activeDraft(ID, CONVERSATION, REQUEST, TENANT, USER, 1L, NOW)));
    }

    @Test
    void shouldNotTurnQueryFailureIntoMissingAction() {
        var failure = new DataAccessResourceFailureException("offline failure");
        when(jdbc.query(anyString(), org.mockito.ArgumentMatchers.<RowMapper<PendingAgentAction>>any(),
                eq(ID), eq(TENANT), eq(USER))).thenThrow(failure);

        assertSame(failure, assertThrows(DataAccessResourceFailureException.class,
                () -> repository.findOwnedById(ID, TENANT, USER)));
    }

    private static PendingAgentAction draft() {
        PendingAgentAction action = new PendingAgentAction();
        action.setId(ID);
        action.setTenantId(TENANT);
        action.setUserId(USER);
        action.setConversationId(CONVERSATION);
        action.setRequestId(REQUEST);
        action.setActionType("CREATE_TICKET");
        action.setStatus("DRAFT");
        action.setParamsJson(JSON);
        action.setPreviewHash(HASH);
        action.setExpiresAt(EXPIRES);
        return action;
    }

    private static String normalize(String sql) {
        return sql.replaceAll("\\s+", " ").trim()
                .replaceAll("\\s*([(),])\\s*", "$1")
                .toLowerCase(Locale.ROOT);
    }
}
