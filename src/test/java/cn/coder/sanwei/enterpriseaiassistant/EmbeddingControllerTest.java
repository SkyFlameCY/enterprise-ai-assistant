package cn.coder.sanwei.enterpriseaiassistant;

import cn.coder.sanwei.enterpriseaiassistant.controller.EmbeddingController;
import cn.coder.sanwei.enterpriseaiassistant.exception.AiServiceException;
import cn.coder.sanwei.enterpriseaiassistant.exception.GlobalExceptionHandler;
import cn.coder.sanwei.enterpriseaiassistant.model.SimilarityResult;
import cn.coder.sanwei.enterpriseaiassistant.service.EmbeddingService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
    void shouldGenerateEmbeddingSuccessfully() throws Exception {
        when(embeddingService.embed("你好")).thenReturn(new float[]{1.0f, 2.0f, 3.0f});

        mockMvc.perform(post("/api/embeddings")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                          {"text":"你好"}
                          """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dimensions").value(3))
                .andExpect(jsonPath("$.preview[0]").value(1.0))
                .andExpect(jsonPath("$.preview[1]").value(2.0))
                .andExpect(jsonPath("$.preview[2]").value(3.0));
    }

    @Test
    void shouldRejectBlankText() throws Exception {
        mockMvc.perform(post("/api/embeddings")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"text": "   "}
                        """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_REQUEST"));

        verifyNoInteractions(embeddingService);
    }

    @Test
    void shouldRejectSimilarityWithoutLeft() throws Exception {
        mockMvc.perform(post("/api/embeddings/similarity")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"right": "公司差旅标准"}
                        """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.message").value("left 不能为空"));

        verifyNoInteractions(embeddingService);
    }

    @Test
    void shouldRejectSimilarityWithoutRight() throws Exception {
        mockMvc.perform(post("/api/embeddings/similarity")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"left": "员工出差标准"}
                        """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.message").value("right 不能为空"));

        verifyNoInteractions(embeddingService);
    }

    @Test
    void shouldReturnSimilaritySuccessfully() throws Exception {
        when(embeddingService.similarity("出差住宿标准", "差旅酒店上限")).thenReturn(new SimilarityResult(0.8, 1024));

        mockMvc.perform(post("/api/embeddings/similarity")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"left": "出差住宿标准",
                        "right": "差旅酒店上限"}
                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.score").value(0.8))
                .andExpect(jsonPath("$.dimensions").value(1024));

        verify(embeddingService).similarity("出差住宿标准", "差旅酒店上限");
        verifyNoMoreInteractions(embeddingService);
    }

    @Test
    void shouldReturnBadGatewayWhenEmbeddingFails() throws Exception {
        when(embeddingService.embed("你好")).thenThrow(new AiServiceException("Ollama 调用失败"));

        mockMvc.perform(post("/api/embeddings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                          {"text":"你好"}
                          """))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.errorCode").value("AI_SERVICE_ERROR"))
                .andExpect(jsonPath("$.message").value("AI 服务暂时不可用"));
    }

}
