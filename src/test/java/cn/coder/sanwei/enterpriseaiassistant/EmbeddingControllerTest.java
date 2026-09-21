package cn.coder.sanwei.enterpriseaiassistant;

import cn.coder.sanwei.enterpriseaiassistant.controller.EmbeddingController;
import cn.coder.sanwei.enterpriseaiassistant.exception.GlobalExceptionHandler;
import cn.coder.sanwei.enterpriseaiassistant.service.EmbeddingService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * EmbeddingControllerTest
 *
 * @author caiyang
 * @date 2026/9/21
 */
@WebMvcTest(EmbeddingController.class)
@Import(GlobalExceptionHandler.class)
public class EmbeddingControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private EmbeddingService embeddingService;

    @Test
    void shouldGenerateEmbeddingSuccessfully() {

    }
}
