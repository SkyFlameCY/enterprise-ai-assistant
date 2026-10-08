package cn.coder.sanwei.enterpriseaiassistant;

import cn.coder.sanwei.enterpriseaiassistant.repository.JdbcKnowledgeRepository;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@ActiveProfiles("test")
@SpringBootTest(properties = "knowledge.embedding-revision=test-revision")
class EnterpriseAiAssistantApplicationTests {

    @MockitoBean
    private EmbeddingModel embeddingModel;

    @MockitoBean
    private JdbcKnowledgeRepository knowledgeRepository;

    @Test
    void contextLoads() {
    }

}
