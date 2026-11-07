package cn.coder.sanwei.enterpriseaiassistant;

import cn.coder.sanwei.enterpriseaiassistant.repository.JdbcActionAuditRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Locale;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 只验证参数绑定、影响行数及异常传播；不作为数据库审计事务成功的证据。 */
@ExtendWith(MockitoExtension.class)
class JdbcActionAuditRepositoryTest {

    private static final UUID ID = UUID.fromString("d11daec1-88df-42cf-9a94-118ba9c32d39");
    private static final String HASH = "ab".repeat(32);
    @Mock
    private JdbcTemplate jdbc;
    private JdbcActionAuditRepository repository;

    @BeforeEach
    void setUp() {
        repository = new JdbcActionAuditRepository(jdbc);
    }

    @ParameterizedTest
    @CsvSource({"DRAFT_CREATED,1", "PREVIEW_READY,1", "DRAFT_CREATED,0", "PREVIEW_READY,0"})
    void shouldBindActualOwnerEventAndHashAndReturnAffectedRows(String eventType, int affected) {
        when(jdbc.update(anyString(), eq(ID), eq(2L), eq(502L), eq(eventType), eq(HASH), isNull()))
                .thenReturn(affected);

        assertEquals(affected, repository.insert(ID, 2L, 502L, eventType, HASH, null));

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).update(sql.capture(), eq(ID), eq(2L), eq(502L), eq(eventType), eq(HASH), isNull());
        assertEquals(normalize("""
                INSERT INTO agent_action_audit (
                    action_id, tenant_id, user_id, event_type, params_hash, business_ref
                ) VALUES (?, ?, ?, ?, ?, ?)
                """), normalize(sql.getValue()));
        assertFalse(normalize(sql.getValue()).contains("on conflict"));
        verifyNoMoreInteractions(jdbc);
    }

    @Test
    void shouldBindBusinessReferenceAsDataWhenProvided() {
        String businessRef = "TK'); --";
        when(jdbc.update(anyString(), eq(ID), eq(2L), eq(502L), eq("CONFIRMED"), eq(HASH), eq(businessRef)))
                .thenReturn(1);

        assertEquals(1, repository.insert(ID, 2L, 502L, "CONFIRMED", HASH, businessRef));

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).update(sql.capture(), eq(ID), eq(2L), eq(502L), eq("CONFIRMED"), eq(HASH), eq(businessRef));
        assertFalse(sql.getValue().contains(businessRef));
    }

    @ParameterizedTest
    @ValueSource(strings = {"DRAFT_CREATED", "PREVIEW_READY"})
    void shouldPropagateAuditInsertFailureForServiceTransactionRollback(String eventType) {
        var failure = new DataAccessResourceFailureException("offline audit failure");
        when(jdbc.update(anyString(), eq(ID), eq(2L), eq(502L), eq(eventType), eq(HASH), isNull()))
                .thenThrow(failure);

        assertSame(failure, assertThrows(DataAccessResourceFailureException.class,
                () -> repository.insert(ID, 2L, 502L, eventType, HASH, null)));
    }

    private static String normalize(String sql) {
        return sql.replaceAll("\\s+", " ").trim()
                .replaceAll("\\s*([(),])\\s*", "$1")
                .toLowerCase(Locale.ROOT);
    }
}
