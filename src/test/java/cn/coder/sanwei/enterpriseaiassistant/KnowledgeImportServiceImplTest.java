package cn.coder.sanwei.enterpriseaiassistant;

import cn.coder.sanwei.enterpriseaiassistant.context.AgentExecutionContext;
import cn.coder.sanwei.enterpriseaiassistant.entity.KnowledgeChunk;
import cn.coder.sanwei.enterpriseaiassistant.entity.KnowledgeDocument;
import cn.coder.sanwei.enterpriseaiassistant.exception.DocumentAlreadyExistsException;
import cn.coder.sanwei.enterpriseaiassistant.repository.JdbcKnowledgeRepository;
import cn.coder.sanwei.enterpriseaiassistant.service.EmbeddingService;
import cn.coder.sanwei.enterpriseaiassistant.service.KnowledgeStorageService;
import cn.coder.sanwei.enterpriseaiassistant.service.impl.KnowledgeImportServiceImpl;
import cn.coder.sanwei.enterpriseaiassistant.service.impl.PermissionServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 使用真实权限判断，模拟数据库、Embedding 和事务保存，不访问外部服务。 */
@ExtendWith(MockitoExtension.class)
class KnowledgeImportServiceImplTest {

    private static final String TEXT = "员工应在次月提交报销申请。";
    private static final AgentExecutionContext WRITER =
            new AgentExecutionContext(303L, 1L, Set.of("knowledge:write"));

    @Mock private EmbeddingService embeddings;
    @Mock private KnowledgeStorageService storage;
    @Mock private JdbcKnowledgeRepository repository;
    @Captor private ArgumentCaptor<KnowledgeDocument> savedDocument;
    @Captor private ArgumentCaptor<List<KnowledgeChunk>> savedChunks;

    private KnowledgeImportServiceImpl imports;

    @BeforeEach
    void setUp() {
        imports = new KnowledgeImportServiceImpl(embeddings, storage, repository,
                new PermissionServiceImpl());
        ReflectionTestUtils.setField(imports, "EMBEDDING_MODEL", "bge-m3");
        ReflectionTestUtils.setField(imports, "EMBEDDING_REVISION", "test-revision");
    }

    static Stream<AgentExecutionContext> deniedIdentities() {
        return Stream.of(null,
                new AgentExecutionContext(103L, 1L, Set.of()),
                new AgentExecutionContext(301L, 1L, Set.of("knowledge:read")));
    }

    @ParameterizedTest
    @MethodSource("deniedIdentities")
    void shouldDenyTextImportBeforeValidationOrExternalWork(AgentExecutionContext identity) {
        // 准备：无写权限，同时故意给非法业务参数。
        // 执行与验证：先拒绝权限，不能查询重复文档、计算向量或保存。
        assertThrows(AccessDeniedException.class, () -> imports.importText(null, "", identity));
        verifyNoInteractions(repository, embeddings, storage);
    }

    @Test
    void shouldUseTrustedTenantForDuplicateCheckAndSave() {
        // 准备：实体伪造租户 2，登录身份属于租户 1。
        KnowledgeDocument document = documentForTenant(2L);
        float[] vector = new float[1024];
        vector[0] = 1.0f;
        when(embeddings.embed(TEXT)).thenReturn(vector);
        when(storage.saveDocumentWithChunk(any(), anyList())).thenReturn(77L);

        Long id = imports.importText(document, TEXT, WRITER);

        assertEquals(77L, id);
        verify(repository).existsDocument(1L, "expense-policy", 1);
        verifyNoMoreInteractions(repository);
        verify(storage).saveDocumentWithChunk(savedDocument.capture(), savedChunks.capture());
        assertEquals(1L, savedDocument.getValue().getTenantId());
        assertEquals("ACTIVE", savedDocument.getValue().getStatus());
        assertEquals(1, savedChunks.getValue().size());
    }

    @Test
    void shouldStopDuplicateImportBeforeEmbeddingOrSave() {
        when(repository.existsDocument(1L, "expense-policy", 1)).thenReturn(true);

        assertThrows(DocumentAlreadyExistsException.class,
                () -> imports.importText(documentForTenant(2L), TEXT, WRITER));

        verify(repository).existsDocument(1L, "expense-policy", 1);
        verifyNoInteractions(embeddings, storage);
    }

    @Test
    void shouldRejectMissingDocumentBeforeExternalWork() {
        assertThrows(IllegalArgumentException.class, () -> imports.importText(null, TEXT, WRITER));
        verifyNoInteractions(repository, embeddings, storage);
    }

    @ParameterizedTest
    @MethodSource("deniedIdentities")
    void shouldReportFileImportPermissionFailureAsAccessDenied(AgentExecutionContext identity) {
        // 空文件参数用于确认：应先拒绝权限，而不是进入文件参数检查。
        assertThrows(AccessDeniedException.class, () -> imports.importFile(null, null, identity));
        verifyNoInteractions(repository, embeddings, storage);
    }

    private KnowledgeDocument documentForTenant(long tenantId) {
        KnowledgeDocument document = new KnowledgeDocument();
        document.setTenantId(tenantId);
        document.setDocumentKey("expense-policy");
        document.setVersion(1);
        document.setTitle("员工报销制度");
        return document;
    }
}
