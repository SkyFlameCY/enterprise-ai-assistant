package cn.coder.sanwei.enterpriseaiassistant.dto;

/**
 * SimilarityResponse
 *
 * @author caiyang
 * @date 2026/9/17
 */
public record SimilarityResponse(
        Double score,
        Integer dimensions
) {
}
