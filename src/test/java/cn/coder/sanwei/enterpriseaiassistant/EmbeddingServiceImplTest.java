package cn.coder.sanwei.enterpriseaiassistant;

import cn.coder.sanwei.enterpriseaiassistant.exception.AiServiceException;
import cn.coder.sanwei.enterpriseaiassistant.model.SimilarityResult;
import cn.coder.sanwei.enterpriseaiassistant.service.impl.EmbeddingServiceImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.embedding.EmbeddingModel;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * EmbeddingServiceImplTest
 *
 * @author caiyang
 * @date 2026/9/18
 */
@ExtendWith(MockitoExtension.class)
public class EmbeddingServiceImplTest {

    @Mock
    private EmbeddingModel embeddingModel;

    @InjectMocks
    private EmbeddingServiceImpl embeddingService;

    @Test
    void shouldGenerateEmbedding() {
        // 1. 准备：规定假模型应该返回什么
        when(embeddingModel.embed("你好")).thenReturn(new float[]{1.0f, 2.0f});

        // 2. 执行
        float[] result = embeddingService.embed("你好");

        // 3. 验证
        assertArrayEquals(new float[]{1.0f, 2.0f}, result);
    }

    @Test
    void shouldRejectBlankTextWithoutCallingModel() {
        assertThatThrownBy(() -> embeddingService.embed("   "))
                .isInstanceOf(IllegalArgumentException.class);

        verifyNoInteractions(embeddingModel);
    }

    @Test
    void shouldThrowAiServiceExceptionWhenModelReturnsEmptyVector() {
        when(embeddingModel.embed("你好")).thenReturn(new float[0]);

        assertThrows(AiServiceException.class, () -> embeddingService.embed("你好"));
    }

    @Test
    void shouldWrapModelExceptionAsAiServiceException() {
        when(embeddingModel.embed("你好")).thenThrow(new RuntimeException("Ollama unavailable"));

        assertThrows(AiServiceException.class, () -> embeddingService.embed("你好"));
    }

    @Test
    void shouldReturnOneForVectorsInSameDirection() {
        when(embeddingModel.embed("left")).thenReturn(new float[]{1.0f, 2.0f});

        when(embeddingModel.embed("right")).thenReturn(new float[]{2.0f, 4.0f});

        SimilarityResult result = embeddingService.similarity("left", "right");

        assertEquals(1.0, result.score(), 1e-6);
        assertEquals(2, result.dimensions());
    }

    @Test
    void shouldReturnZeroForOrthogonalVectors() {
        when(embeddingModel.embed("left")).thenReturn(new float[]{1.0f, 0.0f});

        when(embeddingModel.embed("right")).thenReturn(new float[]{0.0f, 1.0f});

        SimilarityResult result = embeddingService.similarity("left", "right");

        assertEquals(0.0, result.score(), 1e-6);
        assertEquals(2, result.dimensions());
    }

    @Test
    void shouldRejectVectorsWithDifferentDimensions() {
        when(embeddingModel.embed("left")).thenReturn(new float[]{1.0f, 2.0f});

        when(embeddingModel.embed("right")).thenReturn(new float[]{1.0f, 2.0f, 3.0f});

        assertThrows(IllegalArgumentException.class, () -> embeddingService.similarity("left", "right"));
    }

    @Test
    void shouldRejectZeroVector() {
        when(embeddingModel.embed("left")).thenReturn(new float[]{0.0f, 0.0f});

        when(embeddingModel.embed("right")).thenReturn(new float[]{1.0f, 2.0f});

        assertThrows(IllegalArgumentException.class, () -> embeddingService.similarity("left", "right"));
    }

    @Test
    void shouldWrapModelExceptionWhenCalculatingSimilarity() {
        when(embeddingModel.embed("left")).thenThrow(new RuntimeException("Ollama unavailable"));

        assertThrows(AiServiceException.class, () -> embeddingService.similarity("left", "right"));
    }
}
