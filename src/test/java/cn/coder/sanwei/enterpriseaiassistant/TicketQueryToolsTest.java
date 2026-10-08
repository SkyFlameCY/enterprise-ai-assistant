package cn.coder.sanwei.enterpriseaiassistant;

import cn.coder.sanwei.enterpriseaiassistant.audit.ToolAuditEvent;
import cn.coder.sanwei.enterpriseaiassistant.audit.ToolAuditService;
import cn.coder.sanwei.enterpriseaiassistant.context.AgentExecutionContext;
import cn.coder.sanwei.enterpriseaiassistant.dto.TicketDetail;
import cn.coder.sanwei.enterpriseaiassistant.dto.TicketSummary;
import cn.coder.sanwei.enterpriseaiassistant.repository.JdbcBusinessRepository;
import cn.coder.sanwei.enterpriseaiassistant.tool.TicketQueryTools;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.security.access.AccessDeniedException;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 工单工具的离线测试：只模拟 Repository 和审计服务，不调用模型或数据库。
 * 验证服务端租户传递、参数和权限检查、返回字段及异常审计。
 * 真实 SQL 的租户隔离、排序和 10 条上限由 JdbcBusinessRepositoryIT 验证。
 */
@ExtendWith(MockitoExtension.class)
class TicketQueryToolsTest {

    private static final String PROJECT = "EA-2026";
    private static final String TICKET = "TK-001";
    private static final String STATUS = "OPEN";
    private static final ObjectMapper JSON = new ObjectMapper();

    enum Operation {
        LIST("searchTickets"), DETAIL("getTicketDetail");

        final String toolName;

        Operation(String toolName) {
            this.toolName = toolName;
        }
    }

    @Mock
    private JdbcBusinessRepository repository;

    @Mock
    private ToolAuditService auditService;

    private TicketQueryTools tools;

    @BeforeEach
    void setUp() {
        tools = new TicketQueryTools(auditService, repository);
    }

    @ParameterizedTest
    @MethodSource("tenantQueries")
    void shouldUseServerTenantAndReturnOnlyResultFields(Operation operation, long tenantId) throws Exception {
        // 准备：登录身份和数据库返回值由测试指定，而不是使用工具中的演示数据。
        Object expected = stubResult(operation, tenantId, false);

        // 执行：调用真实工具方法。
        Object actual = invoke(operation, context(tenantId, Set.of("ticket:read")));

        // 验证：使用登录租户查询，原样返回 DTO，并只审计一次。
        assertSame(expected, actual);
        assertRepositoryCall(operation, tenantId);
        var result = JSON.readTree(JSON.writeValueAsString(actual));
        if (operation == Operation.LIST) {
            assertTrue(result.isArray());
            assertEquals(1, result.size());
            result = result.get(0);
        }
        Set<String> fields = new HashSet<>();
        result.fieldNames().forEachRemaining(fields::add);
        Set<String> expectedFields = operation == Operation.LIST
                ? Set.of("ticketNo", "projectCode", "title", "status", "priority")
                : Set.of("ticketNo", "projectCode", "title", "status", "priority", "description");
        assertEquals(expectedFields, fields);
        assertAudit(operation, tenantId, "SUCCESS", 1, null);
    }

    static Stream<Arguments> tenantQueries() {
        return Stream.of(Operation.values()).flatMap(operation -> Stream.of(
                Arguments.of(operation, 1L), Arguments.of(operation, 2L)));
    }

    @ParameterizedTest
    @EnumSource(Operation.class)
    void shouldKeepEmptyRepositoryResultWithoutDemoFallback(Operation operation) {
        Object expected = stubResult(operation, 1L, true);

        Object actual = invoke(operation, readContext());

        assertSame(expected, actual);
        assertRepositoryCall(operation, 1L);
        assertAudit(operation, 1L, "SUCCESS", 0, null);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "\t\n"})
    void shouldRejectBlankProjectBeforeQuery(String projectCode) {
        assertThrows(IllegalArgumentException.class,
                () -> tools.searchTickets(projectCode, STATUS, readContext()));

        verifyNoInteractions(repository);
        assertAudit(Operation.LIST, 1L, "FAILED", 0, "INVALID_ARGUMENT");
    }

    @Test
    void shouldRejectProjectCodeLongerThan64CharactersBeforeQuery() {
        // 准备：项目编号比数据库字段和项目查询工具的上限多一个字符。
        String projectCode = "X".repeat(65);

        // 执行并验证：工具层拒绝参数，不应继续访问数据库。
        assertThrows(IllegalArgumentException.class,
                () -> tools.searchTickets(projectCode, STATUS, readContext()));

        verifyNoInteractions(repository);
        assertAudit(Operation.LIST, 1L, "FAILED", 0, "INVALID_ARGUMENT");
    }

    @Test
    void shouldAccept64CharacterProjectCode() {
        String projectCode = "X".repeat(64);
        when(repository.findTickets(1L, projectCode, STATUS)).thenReturn(List.of());

        assertEquals(List.of(), tools.searchTickets(projectCode, STATUS, readContext()));

        verify(repository).findTickets(1L, projectCode, STATUS);
        verifyNoMoreInteractions(repository);
        assertAudit(Operation.LIST, 1L, "SUCCESS", 0, null);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "\t\n"})
    void shouldRejectBlankStatusBeforeQuery(String status) {
        assertThrows(IllegalArgumentException.class,
                () -> tools.searchTickets(PROJECT, status, readContext()));

        verifyNoInteractions(repository);
        assertAudit(Operation.LIST, 1L, "FAILED", 0, "INVALID_ARGUMENT");
    }

    @ParameterizedTest
    @ValueSource(strings = {"open", "CLOSED", "OPEN' OR '1'='1"})
    void shouldRejectUnsupportedStatusBeforeQuery(String status) {
        assertThrows(IllegalArgumentException.class,
                () -> tools.searchTickets(PROJECT, status, readContext()));

        verifyNoInteractions(repository);
        assertAudit(Operation.LIST, 1L, "FAILED", 0, "INVALID_ARGUMENT");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "\t\n"})
    void shouldRejectBlankTicketNumberBeforeQuery(String ticketNo) {
        assertThrows(IllegalArgumentException.class,
                () -> tools.getTicketDetail(ticketNo, readContext()));

        verifyNoInteractions(repository);
        assertAudit(Operation.DETAIL, 1L, "FAILED", 0, "INVALID_ARGUMENT");
    }

    @Test
    void shouldRejectTicketNumberLongerThan64Characters() {
        assertThrows(IllegalArgumentException.class,
                () -> tools.getTicketDetail("X".repeat(65), readContext()));

        verifyNoInteractions(repository);
        assertAudit(Operation.DETAIL, 1L, "FAILED", 0, "INVALID_ARGUMENT");
    }

    @Test
    void shouldAccept64CharacterTicketNumber() {
        String ticketNo = "X".repeat(64);

        assertNull(tools.getTicketDetail(ticketNo, readContext()));

        verify(repository).findTicketDetail(1L, ticketNo);
        verifyNoMoreInteractions(repository);
        assertAudit(Operation.DETAIL, 1L, "SUCCESS", 0, null);
    }

    @ParameterizedTest
    @MethodSource("invalidToolContexts")
    void shouldRejectMissingOrForgedIdentity(Operation operation, ToolContext toolContext) {
        assertThrows(IllegalArgumentException.class, () -> invoke(operation, toolContext));

        verifyNoInteractions(repository);
        assertAudit(operation, null, "FAILED", 0, "INVALID_CONTEXT");
    }

    static Stream<Arguments> invalidToolContexts() {
        return Stream.of(Operation.values()).flatMap(operation -> Stream.of(
                Arguments.of(operation, null),
                Arguments.of(operation, new ToolContext(Map.of())),
                Arguments.of(operation, new ToolContext(Map.of("executionContext", Map.of(
                        "tenantId", 2L, "permissions", List.of("ticket:read")))))));
    }

    @ParameterizedTest
    @MethodSource("deniedQueries")
    void shouldRejectMissingTicketPermissionBeforeQuery(Operation operation, Set<String> permissions) {
        assertThrows(AccessDeniedException.class,
                () -> invoke(operation, context(1L, permissions)));

        verifyNoInteractions(repository);
        assertAudit(operation, 1L, "DENIED", 0, "FORBIDDEN");
    }

    static Stream<Arguments> deniedQueries() {
        return Stream.of(Operation.values()).flatMap(operation -> Stream.of(
                Arguments.of(operation, Set.of()),
                Arguments.of(operation, Set.of("project:read"))));
    }

    @ParameterizedTest
    @EnumSource(Operation.class)
    void shouldCheckPermissionBeforeArgumentValidation(Operation operation) {
        assertThrows(AccessDeniedException.class,
                () -> invokeWithMissingArgument(operation, context(1L, Set.of("project:read"))));

        verifyNoInteractions(repository);
        assertAudit(operation, 1L, "DENIED", 0, "FORBIDDEN");
    }

    @ParameterizedTest
    @EnumSource(Operation.class)
    void shouldPreserveDatabaseFailureAndAuditToolError(Operation operation) {
        var original = stubDatabaseFailure(operation);

        var actual = assertThrows(DataAccessResourceFailureException.class,
                () -> invoke(operation, readContext()));

        assertSame(original, actual);
        assertRepositoryCall(operation, 1L);
        assertAudit(operation, 1L, "FAILED", 0, "TOOL_ERROR");
    }

    @ParameterizedTest
    @EnumSource(Operation.class)
    void shouldKeepSuccessfulResultWhenAuditFails(Operation operation) {
        Object expected = stubResult(operation, 1L, false);
        failAudit();

        assertSame(expected, assertDoesNotThrow(() -> invoke(operation, readContext())));

        assertRepositoryCall(operation, 1L);
        assertAudit(operation, 1L, "SUCCESS", 1, null);
    }

    @ParameterizedTest
    @EnumSource(Operation.class)
    void shouldKeepEmptyResultWhenAuditFails(Operation operation) {
        Object expected = stubResult(operation, 1L, true);
        failAudit();

        assertSame(expected, assertDoesNotThrow(() -> invoke(operation, readContext())));

        assertRepositoryCall(operation, 1L);
        assertAudit(operation, 1L, "SUCCESS", 0, null);
    }

    @ParameterizedTest
    @EnumSource(Operation.class)
    void shouldKeepPermissionFailureWhenAuditFails(Operation operation) {
        failAudit();

        assertThrows(AccessDeniedException.class,
                () -> invoke(operation, context(1L, Set.of("project:read"))));

        verifyNoInteractions(repository);
        assertAudit(operation, 1L, "DENIED", 0, "FORBIDDEN");
    }

    @ParameterizedTest
    @EnumSource(Operation.class)
    void shouldKeepValidationFailureWhenAuditFails(Operation operation) {
        failAudit();

        assertThrows(IllegalArgumentException.class,
                () -> invokeWithMissingArgument(operation, readContext()));

        verifyNoInteractions(repository);
        assertAudit(operation, 1L, "FAILED", 0, "INVALID_ARGUMENT");
    }

    @ParameterizedTest
    @EnumSource(Operation.class)
    void shouldKeepOriginalDatabaseFailureWhenAuditAlsoFails(Operation operation) {
        var original = stubDatabaseFailure(operation);
        failAudit();

        var actual = assertThrows(DataAccessResourceFailureException.class,
                () -> invoke(operation, readContext()));

        assertSame(original, actual);
        assertRepositoryCall(operation, 1L);
        assertAudit(operation, 1L, "FAILED", 0, "TOOL_ERROR");
    }

    private Object invoke(Operation operation, ToolContext toolContext) {
        return operation == Operation.LIST
                ? tools.searchTickets(PROJECT, STATUS, toolContext)
                : tools.getTicketDetail(TICKET, toolContext);
    }

    private Object invokeWithMissingArgument(Operation operation, ToolContext toolContext) {
        return operation == Operation.LIST
                ? tools.searchTickets(null, STATUS, toolContext)
                : tools.getTicketDetail(null, toolContext);
    }

    private Object stubResult(Operation operation, long tenantId, boolean empty) {
        if (operation == Operation.LIST) {
            List<TicketSummary> result = empty ? List.of() : List.of(
                    new TicketSummary(TICKET, PROJECT, "数据库工单-" + tenantId, STATUS, "HIGH"));
            when(repository.findTickets(tenantId, PROJECT, STATUS)).thenReturn(result);
            return result;
        }
        TicketDetail result = empty ? null : new TicketDetail(
                TICKET, PROJECT, "数据库工单-" + tenantId, STATUS, "HIGH", "数据库详情-" + tenantId);
        when(repository.findTicketDetail(tenantId, TICKET)).thenReturn(result);
        return result;
    }

    private DataAccessResourceFailureException stubDatabaseFailure(Operation operation) {
        var error = new DataAccessResourceFailureException("synthetic database failure");
        if (operation == Operation.LIST) {
            when(repository.findTickets(1L, PROJECT, STATUS)).thenThrow(error);
        } else {
            when(repository.findTicketDetail(1L, TICKET)).thenThrow(error);
        }
        return error;
    }

    private ToolContext readContext() {
        return context(1L, Set.of("ticket:read"));
    }

    private ToolContext context(long tenantId, Set<String> permissions) {
        return new ToolContext(Map.of("executionContext",
                new AgentExecutionContext(100L + tenantId, tenantId, permissions)));
    }

    private void failAudit() {
        doThrow(new IllegalStateException("synthetic audit failure"))
                .when(auditService).record(any(ToolAuditEvent.class));
    }

    private void assertRepositoryCall(Operation operation, long tenantId) {
        if (operation == Operation.LIST) {
            verify(repository).findTickets(tenantId, PROJECT, STATUS);
        } else {
            verify(repository).findTicketDetail(tenantId, TICKET);
        }
        verifyNoMoreInteractions(repository);
    }

    private void assertAudit(Operation operation, Long tenantId, String outcome,
                             int resultCount, String errorCode) {
        ArgumentCaptor<ToolAuditEvent> captor = ArgumentCaptor.forClass(ToolAuditEvent.class);
        verify(auditService).record(captor.capture());
        verifyNoMoreInteractions(auditService);
        ToolAuditEvent event = captor.getValue();
        assertEquals(operation.toolName, event.toolName());
        assertEquals(tenantId == null ? null : Long.valueOf(100L + tenantId), event.userId());
        assertEquals(tenantId, event.tenantId());
        assertEquals(outcome, event.outcome());
        assertEquals(resultCount, event.resultCount());
        assertEquals(errorCode, event.errorCode());
        assertTrue(event.durationMs() >= 0);
    }
}
