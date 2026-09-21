package cn.coder.sanwei.enterpriseaiassistant.dto;

import java.util.List;

/**
 * EmbeddingResponse
 *
 * @author caiyang
 * @date 2026/9/17
 */
public record EmbeddingResponse(
        Integer dimensions,
        List<Float> preview
) {
}
