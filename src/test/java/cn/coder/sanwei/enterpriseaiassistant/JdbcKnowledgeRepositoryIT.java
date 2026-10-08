package cn.coder.sanwei.enterpriseaiassistant;

import cn.coder.sanwei.enterpriseaiassistant.entity.KnowledgeChunk;
import cn.coder.sanwei.enterpriseaiassistant.entity.KnowledgeDocument;
import cn.coder.sanwei.enterpriseaiassistant.model.KnowledgeSearchHit;
import cn.coder.sanwei.enterpriseaiassistant.repository.JdbcKnowledgeRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * JdbcKnowledgeRepositoryIT
 *
 * @author caiyang
 * @date 2026/9/29
 */
@JdbcTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(JdbcKnowledgeRepository.class)
@Testcontainers
class JdbcKnowledgeRepositoryIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:0.8.6-pg17-bookworm").asCompatibleSubstituteFor("postgres")
    );

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    JdbcKnowledgeRepository repository;

    @Test
    void shouldReturnGeneratedIdAndReadBackDocument() {
        KnowledgeDocument document = new KnowledgeDocument();
        document.setTenantId(1L);
        document.setDocumentKey("test01");
        document.setTitle("员工报销手册");
        document.setVersion(1);
        document.setSourceRef("公司内部");
        document.setContentSha256("a".repeat(64));
        document.setStatus("DRAFT");
        document.setPermissionMetadata("{}");

        Long id = repository.insertDocument(document);

        assertThat(id).isNotNull().isPositive();

        KnowledgeDocument saved = repository.findDocumentById(id);
        assertThat(saved.getDocumentKey()).isEqualTo("test01");
    }

    @Test
    void shouldReturnGeneratedIdAndReadBackChunk() {
        KnowledgeDocument document = new KnowledgeDocument();
        document.setTenantId(1L);
        document.setDocumentKey("test01");
        document.setTitle("员工报销手册");
        document.setVersion(1);
        document.setSourceRef("公司内部");
        document.setContentSha256("a".repeat(64));
        document.setStatus("ACTIVE");
        document.setPermissionMetadata("{}");

        Long documentId = repository.insertDocument(document);

        KnowledgeChunk chunk = new KnowledgeChunk();
        chunk.setDocumentId(documentId);
        chunk.setChunkIndex(0);
        chunk.setContent("test");
        chunk.setContentSha256("b".repeat(64));
        chunk.setEmbeddingModel("bge-m3");
        chunk.setEmbeddingRevision("V0.1");
        chunk.setEmbedding("[1" + ",0".repeat(1023) + "]");

        KnowledgeChunk chunk2 = new KnowledgeChunk();
        chunk2.setDocumentId(documentId);
        chunk2.setChunkIndex(1);
        chunk2.setContent("test");
        chunk2.setContentSha256("b".repeat(64));
        chunk2.setEmbeddingModel("bge-m3");
        chunk2.setEmbeddingRevision("V0.1");
        chunk2.setEmbedding("[0" + ",1".repeat(1023) + "]");

        Long chunkId = repository.insertChunk(chunk);
        Long chunk2Id = repository.insertChunk(chunk2);
        assertThat(chunkId).isNotNull().isPositive();
        assertThat(chunk2Id).isNotNull().isPositive();

        KnowledgeChunk saved = repository.findChunkById(chunkId);
        assertThat(saved.getDocumentId()).isEqualTo(documentId);
        assertThat(saved.getContent()).isEqualTo("test");

        assertThat(repository.findTopK(1L, "bge-m3", "V0.1", chunk.getEmbedding(), 1))
                .extracting(KnowledgeSearchHit::chunkId).containsExactly(chunkId);
        assertThat(repository.findTopK(2L, "bge-m3", "V0.1", chunk.getEmbedding(), 1))
                .isEmpty();

        assertThat(repository.findTopK(1L, "bge-m3", "V0.1", chunk2.getEmbedding(), 1))
                .extracting(KnowledgeSearchHit::chunkId).containsExactly(chunk2Id);
    }

}
