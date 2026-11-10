package cn.coder.sanwei.enterpriseaiassistant;

import cn.coder.sanwei.enterpriseaiassistant.config.TimeConfig;
import cn.coder.sanwei.enterpriseaiassistant.context.AgentExecutionContext;
import cn.coder.sanwei.enterpriseaiassistant.dto.CreateTicketParams;
import cn.coder.sanwei.enterpriseaiassistant.dto.PendingActionPreview;
import cn.coder.sanwei.enterpriseaiassistant.dto.ProjectSummary;
import cn.coder.sanwei.enterpriseaiassistant.entity.PendingAgentAction;
import cn.coder.sanwei.enterpriseaiassistant.exception.ConversationStateException;
import cn.coder.sanwei.enterpriseaiassistant.repository.JdbcActionAuditRepository;
import cn.coder.sanwei.enterpriseaiassistant.repository.JdbcBusinessRepository;
import cn.coder.sanwei.enterpriseaiassistant.repository.JdbcPendingActionRepository;
import cn.coder.sanwei.enterpriseaiassistant.service.ConversationService;
import cn.coder.sanwei.enterpriseaiassistant.service.impl.PendingActionServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.access.AccessDeniedException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 检查真实 Service 的草稿契约；Repository 为 mock，不证明真实数据库约束和事务回滚。 */
@ExtendWith(MockitoExtension.class)
class PendingActionServiceImplTest {

    private static final long CONVERSATION = 77L;
    private static final long TURN = 5L;
    private static final AgentExecutionContext OPERATOR = new AgentExecutionContext(502L, 2L, Set.of("ticket:create"));
    private static final AgentExecutionContext READER = new AgentExecutionContext(402L, 2L, Set.of("ticket:read"));
    private static final UUID REQUEST = UUID.fromString("0447d20a-466e-4a99-b3a3-c5051eef6fbd");
    private static final UUID OLD_ID = UUID.fromString("09db987f-bd8a-4ed1-849b-5f195ee2c4fd");
    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-11-09T09:00:00Z");
    private static final CreateTicketParams PARAMS = new CreateTicketParams("EA-2026", "来源缺失", "原始说明", "HIGH");
    private static final String CANONICAL_JSON =
            "{\"projectCode\":\"EA-2026\",\"title\":\"来源缺失\",\"description\":\"原始说明\",\"priority\":\"HIGH\"}";
    private static final String HASH = sha256(CANONICAL_JSON);

    @Mock
    private JdbcPendingActionRepository drafts;
    @Mock
    private JdbcActionAuditRepository audits;
    @Mock
    private ConversationService conversations;
    @Mock
    private TimeConfig timeConfig;
    @Mock
    private JdbcBusinessRepository businessRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private PendingActionServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new PendingActionServiceImpl(drafts, conversations, timeConfig, audits, objectMapper, businessRepository);
    }

    @Test
    void shouldPersistTrustedScopeCanonicalJsonAndFutureExpiryForNewDraft() throws Exception {
        stubNewDraft();
        when(audits.insert(any(UUID.class), eq(2L), eq(502L), eq("DRAFT_CREATED"), anyString(), isNull()))
                .thenReturn(1);

        PendingActionPreview preview = service.prepareCreateTicketDraft(CONVERSATION, REQUEST, PARAMS, OPERATOR);

        var captured = ArgumentCaptor.forClass(PendingAgentAction.class);
        var order = inOrder(conversations, businessRepository, drafts, audits);
        order.verify(conversations).requireActiveOwnedConversation(CONVERSATION, OPERATOR);
        order.verify(businessRepository).findProject(2L, PARAMS.projectCode());
        order.verify(drafts).insertDraftIfAbsent(captured.capture());
        PendingAgentAction inserted = captured.getValue();
        order.verify(audits).insert(inserted.getId(), 2L, 502L, "DRAFT_CREATED", HASH, null);
        assertAll(
                () -> assertNotNull(inserted.getId()),
                () -> assertEquals(2L, inserted.getTenantId()),
                () -> assertEquals(502L, inserted.getUserId()),
                () -> assertEquals(CONVERSATION, inserted.getConversationId()),
                () -> assertEquals(REQUEST, inserted.getRequestId()),
                () -> assertEquals("DRAFT", inserted.getStatus()),
                () -> assertNull(inserted.getSourceTurnNo()),
                () -> assertNull(inserted.getResultJson()),
                () -> assertEquals(CANONICAL_JSON, inserted.getParamsJson()),
                () -> assertEquals(PARAMS, objectMapper.readValue(inserted.getParamsJson(), CreateTicketParams.class)),
                () -> assertEquals(HASH, inserted.getPreviewHash()),
                () -> assertEquals(NOW.plusMinutes(10), inserted.getExpiresAt()),
                () -> assertEquals(previewOf(inserted, "DRAFT"), preview));
    }

    @Test
    void shouldNormalizeBeforeHashingPersistingAndReturningPreview() {
        var input = new CreateTicketParams(" EA-2026 ", " 来源缺失 ", "\t原始说明\n", " HIGH ");
        stubNewDraft();
        when(audits.insert(any(UUID.class), eq(2L), eq(502L), eq("DRAFT_CREATED"), anyString(), isNull()))
                .thenReturn(1);

        PendingActionPreview preview = service.prepareCreateTicketDraft(CONVERSATION, REQUEST, input, OPERATOR);

        var captured = ArgumentCaptor.forClass(PendingAgentAction.class);
        verify(drafts).insertDraftIfAbsent(captured.capture());
        assertAll(
                () -> assertEquals(PARAMS, preview.params()),
                () -> assertEquals(HASH, preview.previewHash()),
                () -> assertEquals(CANONICAL_JSON, captured.getValue().getParamsJson()));
    }

    @Test
    void shouldRejectMissingPriorityAsInvalidArgumentBeforeRepository() {
        var input = new CreateTicketParams("EA-2026", "标题", "说明", null);

        assertThrowsExactly(IllegalArgumentException.class,
                () -> service.prepareCreateTicketDraft(CONVERSATION, REQUEST, input, OPERATOR));

        verifyNoInteractions(conversations, businessRepository, drafts, audits);
    }

    @Test
    void shouldRejectReaderBeforeLookingUpConversationOrWritingDraft() {
        assertThrowsExactly(AccessDeniedException.class,
                () -> service.prepareCreateTicketDraft(CONVERSATION, REQUEST, PARAMS, READER));

        verifyNoInteractions(conversations, businessRepository, drafts, audits);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = "PAUSED")
    void shouldRejectMissingOrInactiveProjectBeforeWritingDraft(String projectStatus) {
        ProjectSummary project = projectStatus == null
                ? null : new ProjectSummary(PARAMS.projectCode(), "测试项目", projectStatus);
        when(businessRepository.findProject(2L, PARAMS.projectCode())).thenReturn(project);
        // 如果错误地继续执行，固定时钟不能提前以 NPE 掩盖后续写入。
        lenient().when(timeConfig.clock()).thenReturn(Clock.fixed(NOW.toInstant(), ZoneOffset.UTC));

        var failure = assertThrows(RuntimeException.class,
                () -> service.prepareCreateTicketDraft(CONVERSATION, REQUEST, PARAMS, OPERATOR));

        assertFalse(failure instanceof NullPointerException, "不存在的项目应受控拒绝");
        verify(businessRepository).findProject(2L, PARAMS.projectCode());
        verifyNoInteractions(drafts, audits);
    }

    @Test
    void shouldReuseOriginalExpiryAndNotAuditAgainOnIdenticalRequest() {
        freezeClock();
        stubActiveProject();
        PendingAgentAction original = storedDraft();
        when(drafts.insertDraftIfAbsent(any(PendingAgentAction.class))).thenReturn(null);
        when(drafts.findByRequestAndHash(2L, 502L, REQUEST, "CREATE_TICKET", HASH)).thenReturn(original);

        PendingActionPreview actual = service.prepareCreateTicketDraft(CONVERSATION, REQUEST, PARAMS, OPERATOR);

        assertAll(
                () -> assertEquals(previewOf(original, "DRAFT"), actual),
                () -> verifyNoInteractions(audits));
    }

    @ParameterizedTest
    @ValueSource(strings = {"OTHER_CONVERSATION", "EXPIRED", "ALREADY_ACTIVATED"})
    void shouldRejectInvalidExistingDraftWithoutAuditing(String scenario) {
        freezeClock();
        stubActiveProject();
        PendingAgentAction original = storedDraft();
        switch (scenario) {
            case "OTHER_CONVERSATION" -> original.setConversationId(CONVERSATION + 1);
            case "EXPIRED" -> original.setExpiresAt(NOW);
            case "ALREADY_ACTIVATED" -> {
                original.setStatus("PENDING_CONFIRMATION");
                original.setSourceTurnNo(TURN);
            }
            default -> throw new AssertionError(scenario);
        }
        when(drafts.insertDraftIfAbsent(any(PendingAgentAction.class))).thenReturn(null);
        when(drafts.findByRequestAndHash(2L, 502L, REQUEST, "CREATE_TICKET", HASH)).thenReturn(original);

        assertThrows(RuntimeException.class,
                () -> service.prepareCreateTicketDraft(CONVERSATION, REQUEST, PARAMS, OPERATOR));

        verify(drafts).findByRequestAndHash(2L, 502L, REQUEST, "CREATE_TICKET", HASH);
        verifyNoInteractions(audits);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 2})
    void shouldRejectUnexpectedAffectedRowsFromDraftAuditInsteadOfReportingSuccess(int affectedRows) {
        stubNewDraft();
        when(audits.insert(any(UUID.class), eq(2L), eq(502L), eq("DRAFT_CREATED"), eq(HASH), isNull()))
                .thenReturn(affectedRows);

        assertThrows(RuntimeException.class,
                () -> service.prepareCreateTicketDraft(CONVERSATION, REQUEST, PARAMS, OPERATOR));
        verify(audits).insert(any(UUID.class), eq(2L), eq(502L), eq("DRAFT_CREATED"), eq(HASH), isNull());
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 2})
    void shouldNotReturnActivatedPreviewWhenReadyAuditDoesNotWriteExactlyOneRow(int affectedRows) {
        freezeClock();
        when(drafts.findDraftByRequest(CONVERSATION, REQUEST, 2L, 502L)).thenReturn(List.of(storedDraft()));
        when(drafts.activeDraft(OLD_ID, CONVERSATION, REQUEST, 2L, 502L, TURN, NOW)).thenReturn(1);
        when(audits.insert(OLD_ID, 2L, 502L, "PREVIEW_READY", HASH, null)).thenReturn(affectedRows);

        assertThrows(RuntimeException.class,
                () -> service.activeDraftsForTurn(CONVERSATION, REQUEST, TURN, OPERATOR));

        verify(audits).insert(OLD_ID, 2L, 502L, "PREVIEW_READY", HASH, null);
    }

    @Test
    void shouldPropagateDraftAuditFailureToTransactionalCaller() {
        stubNewDraft();
        var failure = new DataIntegrityViolationException("模拟数据库审计写入失败");
        when(audits.insert(any(UUID.class), eq(2L), eq(502L), eq("DRAFT_CREATED"), eq(HASH), isNull()))
                .thenThrow(failure);

        assertSame(failure, assertThrows(DataIntegrityViolationException.class,
                () -> service.prepareCreateTicketDraft(CONVERSATION, REQUEST, PARAMS, OPERATOR)));
    }

    @Test
    void shouldDecodeStoredJsonAndReturnActivatedPreview() {
        freezeClock();
        PendingAgentAction original = storedDraft();
        // JSONB 读回的字段顺序可以不同；返回的 hash 来自已保存记录。
        original.setParamsJson("{\"priority\":\"HIGH\",\"title\":\"来源缺失\",\"description\":\"原始说明\",\"projectCode\":\"EA-2026\"}");
        when(drafts.findDraftByRequest(CONVERSATION, REQUEST, 2L, 502L)).thenReturn(List.of(original));
        when(drafts.activeDraft(OLD_ID, CONVERSATION, REQUEST, 2L, 502L, TURN, NOW)).thenReturn(1);
        when(audits.insert(OLD_ID, 2L, 502L, "PREVIEW_READY", HASH, null)).thenReturn(1);

        List<PendingActionPreview> result = service.activeDraftsForTurn(CONVERSATION, REQUEST, TURN, OPERATOR);

        assertEquals(List.of(previewOf(original, "PENDING_CONFIRMATION")), result);
        var order = inOrder(drafts, audits);
        order.verify(drafts).findDraftByRequest(CONVERSATION, REQUEST, 2L, 502L);
        order.verify(drafts).activeDraft(OLD_ID, CONVERSATION, REQUEST, 2L, 502L, TURN, NOW);
        order.verify(audits).insert(OLD_ID, 2L, 502L, "PREVIEW_READY", HASH, null);
    }

    @Test
    void shouldRejectStaleDraftWithoutWritingReadyAudit() {
        freezeClock();
        when(drafts.findDraftByRequest(CONVERSATION, REQUEST, 2L, 502L)).thenReturn(List.of(storedDraft()));
        when(drafts.activeDraft(OLD_ID, CONVERSATION, REQUEST, 2L, 502L, TURN, NOW)).thenReturn(0);

        assertThrows(ConversationStateException.class,
                () -> service.activeDraftsForTurn(CONVERSATION, REQUEST, TURN, OPERATOR));
        verifyNoInteractions(audits);
    }

    @Test
    void shouldAllowReadOnlyTurnWithoutDraftsAndWithoutCreatePermission() {
        when(drafts.findDraftByRequest(CONVERSATION, REQUEST, 2L, 402L)).thenReturn(List.of());

        assertEquals(List.of(), service.activeDraftsForTurn(CONVERSATION, REQUEST, TURN, READER));

        verify(drafts).findDraftByRequest(CONVERSATION, REQUEST, 2L, 402L);
        verifyNoMoreInteractions(drafts);
        verifyNoInteractions(audits, timeConfig);
    }

    private void freezeClock() {
        when(timeConfig.clock()).thenReturn(Clock.fixed(NOW.toInstant(), ZoneOffset.UTC));
    }

    private void stubNewDraft() {
        freezeClock();
        stubActiveProject();
        when(drafts.insertDraftIfAbsent(any(PendingAgentAction.class)))
                .thenAnswer(call -> call.<PendingAgentAction>getArgument(0).getId());
    }

    private void stubActiveProject() {
        when(businessRepository.findProject(2L, PARAMS.projectCode()))
                .thenReturn(new ProjectSummary(PARAMS.projectCode(), "测试项目", "ACTIVE"));
    }

    private static PendingAgentAction storedDraft() {
        PendingAgentAction action = new PendingAgentAction();
        action.setId(OLD_ID);
        action.setTenantId(2L);
        action.setUserId(502L);
        action.setConversationId(CONVERSATION);
        action.setRequestId(REQUEST);
        action.setActionType("CREATE_TICKET");
        action.setStatus("DRAFT");
        action.setParamsJson(CANONICAL_JSON);
        action.setPreviewHash(HASH);
        action.setExpiresAt(NOW.plusMinutes(3));
        return action;
    }

    private static PendingActionPreview previewOf(PendingAgentAction action, String status) {
        return new PendingActionPreview(action.getId(), "CREATE_TICKET", status, PARAMS,
                action.getPreviewHash(), action.getExpiresAt());
    }

    private static String sha256(String json) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(json.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
    }
}
