package cn.coder.sanwei.enterpriseaiassistant;

import cn.coder.sanwei.enterpriseaiassistant.audit.ToolAuditEvent;
import cn.coder.sanwei.enterpriseaiassistant.audit.ToolAuditService;
import cn.coder.sanwei.enterpriseaiassistant.context.AgentExecutionContext;
import cn.coder.sanwei.enterpriseaiassistant.exception.AiServiceException;
import cn.coder.sanwei.enterpriseaiassistant.model.KnowledgeSearchHit;
import cn.coder.sanwei.enterpriseaiassistant.service.KnowledgeRetrievalService;
import cn.coder.sanwei.enterpriseaiassistant.service.impl.PermissionServiceImpl;
import cn.coder.sanwei.enterpriseaiassistant.tools.KnowledgeQueryTools;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.security.access.AccessDeniedException;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 只模拟检索与审计，不请求 Embedding、聊天模型或数据库。行为与审计分开验证，便于定位失败。 */
@ExtendWith(MockitoExtension.class)
class KnowledgeQueryToolsTest {

    private static final AgentExecutionContext OWNER =
            new AgentExecutionContext(401L, 1L, Set.of("project:read", "ticket:read", "knowledge:read"));
    private static final String QUESTION = "缺陷提单规范要求哪些信息？";

    @Mock private KnowledgeRetrievalService retrieval;
    @Mock private ToolAuditService auditService;
    @Spy private PermissionServiceImpl permissions = new PermissionServiceImpl();
    @InjectMocks private KnowledgeQueryTools tools;

    @ParameterizedTest
    @ValueSource(longs = {1L, 2L})
    void shouldUseCurrentIdentityAndServerControlledSearchSettings(long tenantId) {
        var identity = new AgentExecutionContext(400L + tenantId, tenantId,
                Set.of("project:read", "ticket:read", "knowledge:read"));
        var expected = List.of(hit(tenantId));
        when(retrieval.retrieval(QUESTION, 3, 0.25, identity)).thenReturn(expected);

        assertSame(expected, tools.searchKnowledge(QUESTION, context(identity)));

        verify(retrieval).retrieval(QUESTION, 3, 0.25, identity);
        verifyNoMoreInteractions(retrieval);
    }

    @Test
    void shouldAcceptExactly4000CharactersWithoutChangingQuestion() {
        String question = "问".repeat(4000);
        when(retrieval.retrieval(question, 3, 0.25, OWNER)).thenReturn(List.of());

        assertEquals(List.of(), tools.searchKnowledge(question, context(OWNER)));

        verify(retrieval).retrieval(question, 3, 0.25, OWNER);
    }

    @Test
    void shouldReturnEmptyResultsWithoutInventingKnowledge() {
        when(retrieval.retrieval(QUESTION, 3, 0.25, OWNER)).thenReturn(List.of());

        assertEquals(List.of(), tools.searchKnowledge(QUESTION, context(OWNER)));
    }

    @Test
    void shouldPreserveSourceContentAndExposeOnlyExistingAllowlistedFields() throws Exception {
        var source = hit(1L);
        when(retrieval.retrieval(QUESTION, 3, 0.25, OWNER)).thenReturn(List.of(source));

        var result = tools.searchKnowledge(QUESTION, context(OWNER));
        var json = new ObjectMapper().valueToTree(result).get(0);

        assertEquals(source, result.getFirst());
        Set<String> actualFields = new HashSet<>();
        json.fieldNames().forEachRemaining(actualFields::add);
        assertEquals(Set.of("chunkId", "documentId", "title", "sourceRef",
                "chunkIndex", "content", "similarity"), actualFields);
        assertEquals(source.content(), json.get("content").asText());
        assertFalse(json.has("tenantId"));
        assertFalse(json.has("userId"));
        assertFalse(json.has("permissions"));
    }

    @ParameterizedTest
    @MethodSource("invalidContexts")
    void shouldRejectMissingOrForgedIdentityBeforeRetrieval(ToolContext invalid) {
        assertThrowsExactly(IllegalArgumentException.class, () -> tools.searchKnowledge(QUESTION, invalid));

        verifyNoInteractions(retrieval);
    }

    static Stream<Arguments> invalidContexts() {
        Map<String, Object> nullIdentity = new HashMap<>();
        nullIdentity.put("executionContext", null);
        return Stream.of(
                Arguments.of((Object) null),
                Arguments.of(new ToolContext(Map.of())),
                Arguments.of(new ToolContext(Map.of("tenantId", 2L))),
                Arguments.of(new ToolContext(Map.of("executionContext", "不是可信身份"))),
                Arguments.of(new ToolContext(nullIdentity))
        );
    }

    @ParameterizedTest
    @MethodSource("insufficientPermissions")
    void shouldRejectNonKnowledgePermissionsBeforeRetrieval(Set<String> scopes) {
        var identity = new AgentExecutionContext(101L, 1L, scopes);

        assertThrowsExactly(AccessDeniedException.class,
                () -> tools.searchKnowledge(QUESTION, context(identity)));

        verifyNoInteractions(retrieval);
    }

    static Stream<Set<String>> insufficientPermissions() {
        return Stream.of(Set.of(), Set.of("project:read"), Set.of("ticket:read"),
                Set.of("knowledge:write"), Set.of("project:read", "ticket:read"));
    }

    @ParameterizedTest
    @MethodSource("invalidQuestions")
    void shouldRejectInvalidQuestionBeforeRetrieval(String question) {
        assertThrowsExactly(IllegalArgumentException.class,
                () -> tools.searchKnowledge(question, context(OWNER)));

        verifyNoInteractions(retrieval);
    }

    static Stream<Arguments> invalidQuestions() {
        return Stream.of(null, "", " \n\t", "问".repeat(4001)).map(Arguments::of);
    }

    @ParameterizedTest
    @MethodSource("retrievalFailures")
    void shouldPreserveOriginalRetrievalException(RuntimeException failure) {
        when(retrieval.retrieval(QUESTION, 3, 0.25, OWNER)).thenThrow(failure);

        assertSame(failure, assertThrows(RuntimeException.class,
                () -> tools.searchKnowledge(QUESTION, context(OWNER))));
    }

    static Stream<Arguments> retrievalFailures() {
        return Stream.of(
                Arguments.of(new AiServiceException("测试 Embedding 失败")),
                Arguments.of(new DataAccessResourceFailureException("测试查询失败"))
        );
    }

    @Test
    void shouldRecordSuccessfulRetrievalExactlyOnce() {
        when(retrieval.retrieval(QUESTION, 3, 0.25, OWNER)).thenReturn(List.of(hit(1L)));

        tools.searchKnowledge(QUESTION, context(OWNER));

        assertAudit(OWNER, "SUCCESS", 1, null);
    }

    @Test
    void shouldRecordSuccessfulEmptyRetrievalAsZeroResults() {
        when(retrieval.retrieval(QUESTION, 3, 0.25, OWNER)).thenReturn(List.of());

        tools.searchKnowledge(QUESTION, context(OWNER));

        assertAudit(OWNER, "SUCCESS", 0, null);
    }

    @Test
    void shouldRecordPermissionDenialWithoutRetrieval() {
        var identity = new AgentExecutionContext(101L, 1L, Set.of("project:read"));

        assertThrowsExactly(AccessDeniedException.class,
                () -> tools.searchKnowledge(QUESTION, context(identity)));

        assertAudit(identity, "DENIED", 0, "FORBIDDEN");
        verifyNoInteractions(retrieval);
    }

    @Test
    void shouldRecordInvalidContextWithoutPretendingToKnowIdentity() {
        assertThrowsExactly(IllegalArgumentException.class, () -> tools.searchKnowledge(QUESTION, null));

        assertAudit(null, "FAILED", 0, "INVALID_CONTEXT");
        verifyNoInteractions(retrieval);
    }

    @Test
    void shouldRecordInvalidArgumentsWithCurrentIdentity() {
        assertThrowsExactly(IllegalArgumentException.class,
                () -> tools.searchKnowledge(" ", context(OWNER)));

        assertAudit(OWNER, "FAILED", 0, "INVALID_ARGUMENT");
        verifyNoInteractions(retrieval);
    }

    @Test
    void shouldRecordRetrievalFailureWithoutLosingItsCause() {
        var failure = new AiServiceException("测试 Embedding 失败");
        when(retrieval.retrieval(QUESTION, 3, 0.25, OWNER)).thenThrow(failure);

        assertSame(failure, assertThrows(AiServiceException.class,
                () -> tools.searchKnowledge(QUESTION, context(OWNER))));

        assertAudit(OWNER, "FAILED", 0, "TOOL_ERROR");
    }

    @ParameterizedTest
    @ValueSource(strings = {"success", "denied", "failure"})
    void shouldNotLetAuditFailureReplaceResultOrOriginalFailure(String mode) {
        // 当前遗漏 record 时此 stub 不会执行，仍通过下面 verify 明确报错，而不是报无关的多余 stub。
        lenient().doThrow(new IllegalStateException("测试日志服务故障"))
                .when(auditService).record(any(ToolAuditEvent.class));
        switch (mode) {
            case "success" -> {
                var expected = List.of(hit(1L));
                when(retrieval.retrieval(QUESTION, 3, 0.25, OWNER)).thenReturn(expected);
                assertSame(expected, tools.searchKnowledge(QUESTION, context(OWNER)));
            }
            case "denied" -> assertThrowsExactly(AccessDeniedException.class,
                    () -> tools.searchKnowledge(QUESTION,
                            context(new AgentExecutionContext(101L, 1L, Set.of("project:read")))));
            case "failure" -> {
                var failure = new AiServiceException("测试原始模型异常");
                when(retrieval.retrieval(QUESTION, 3, 0.25, OWNER)).thenThrow(failure);
                assertSame(failure, assertThrows(AiServiceException.class,
                        () -> tools.searchKnowledge(QUESTION, context(OWNER))));
            }
            default -> fail("未知测试模式");
        }
        verify(auditService).record(any(ToolAuditEvent.class));
        verifyNoMoreInteractions(auditService);
    }

    private void assertAudit(AgentExecutionContext identity, String outcome, int count, String errorCode) {
        var captured = ArgumentCaptor.forClass(ToolAuditEvent.class);
        verify(auditService).record(captured.capture());
        var event = captured.getValue();
        assertAll(
                () -> assertEquals("searchKnowledge", event.toolName()),
                () -> assertEquals(identity == null ? null : Long.valueOf(identity.userId()), event.userId()),
                () -> assertEquals(identity == null ? null : Long.valueOf(identity.tenantId()), event.tenantId()),
                () -> assertEquals(outcome, event.outcome()),
                () -> assertEquals(count, event.resultCount()),
                () -> assertEquals(errorCode, event.errorCode()),
                () -> assertTrue(event.durationMs() >= 0)
        );
        verifyNoMoreInteractions(auditService);
    }

    private static ToolContext context(AgentExecutionContext identity) {
        return new ToolContext(Map.of("executionContext", identity));
    }

    private static KnowledgeSearchHit hit(long tenantId) {
        return new KnowledgeSearchHit(100L + tenantId, 10L + tenantId, "租户" + tenantId + "模拟规范",
                "demo:rules-" + tenantId, 0, "缺陷说明需包含复现步骤、期望结果、实际结果和运行环境。", 0.9);
    }
}
