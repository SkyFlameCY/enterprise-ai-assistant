package cn.coder.sanwei.enterpriseaiassistant;

import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@ActiveProfiles("test")
@SpringBootTest
class EnterpriseAiAssistantApplicationTests {

    @MockitoBean
    private EmbeddingModel embeddingModel;

    @Test
    void contextLoads() {
    }

}
