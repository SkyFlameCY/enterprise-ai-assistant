package cn.coder.sanwei.enterpriseaiassistant;

import cn.coder.sanwei.enterpriseaiassistant.context.AgentExecutionContext;
import cn.coder.sanwei.enterpriseaiassistant.model.KnowledgeSearchHit;
import cn.coder.sanwei.enterpriseaiassistant.repository.JdbcKnowledgeRepository;
import cn.coder.sanwei.enterpriseaiassistant.service.EmbeddingService;
import cn.coder.sanwei.enterpriseaiassistant.service.impl.KnowledgeRetrievalServiceImpl;
import cn.coder.sanwei.enterpriseaiassistant.service.impl.PermissionServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 直接验证服务入口，避免仅测 HTTP 过滤器掩盖服务权限遗漏或条件反转。 */
@ExtendWith(MockitoExtension.class)
class KnowledgeRetrievalServiceImplTest {

    @Mock private EmbeddingService embeddings;
    @Mock private JdbcKnowledgeRepository repository;
    private KnowledgeRetrievalServiceImpl retrieval;

    @BeforeEach
    void setUp() {
        retrieval = new KnowledgeRetrievalServiceImpl(embeddings, repository,
                new PermissionServiceImpl());
        ReflectionTestUtils.setField(retrieval, "EMBEDDING_MODEL", "bge-m3");
        ReflectionTestUtils.setField(retrieval, "EMBEDDING_REVISION", "test-revision");
    }

    static Stream<AgentExecutionContext> deniedIdentities() {
        return Stream.of(null,
                new AgentExecutionContext(103L, 1L, Set.of()),
                new AgentExecutionContext(303L, 1L, Set.of("knowledge:write")));
    }

    @ParameterizedTest
    @MethodSource("deniedIdentities")
    void shouldDenyBeforeArgumentValidationOrExternalWork(AgentExecutionContext identity) {
        assertThrows(AccessDeniedException.class,
                () -> retrieval.retrieval("", 0, Double.NaN, identity));
        verifyNoInteractions(embeddings, repository);
    }

    @Test
    void shouldAllowReaderAndBindRepositoryQueryToTrustedTenant() {
        // 准备：租户 2 的读者与正常模型响应。
        var reader = new AgentExecutionContext(302L, 2L, Set.of("knowledge:read"));
        float[] vector = new float[1024];
        vector[0] = 1.0f;
        var hit = new KnowledgeSearchHit(21L, 7L, "报销制度", "模拟资料", 0,
                "次月提交报销申请。", 0.9);
        when(embeddings.embed("报销期限？")).thenReturn(vector);
        when(repository.findTopK(2L, "bge-m3", "test-revision", Arrays.toString(vector), 5))
                .thenReturn(List.of(hit));

        List<KnowledgeSearchHit> hits = retrieval.retrieval("报销期限？", 5, 0.5, reader);

        assertEquals(List.of(hit), hits);
        verify(embeddings).embed("报销期限？");
        verify(repository).findTopK(2L, "bge-m3", "test-revision", Arrays.toString(vector), 5);
        verifyNoMoreInteractions(embeddings, repository);
    }
}
