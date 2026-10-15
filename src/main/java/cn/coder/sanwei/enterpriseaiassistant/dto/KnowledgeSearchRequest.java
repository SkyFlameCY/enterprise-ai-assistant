package cn.coder.sanwei.enterpriseaiassistant.dto;

import jakarta.validation.constraints.*;

/**
 * KnowledgeSearchRequest
 *
 * @author caiyang
 * @date 2026/10/1
 */
public record KnowledgeSearchRequest(

        @NotBlank(message = "question 不能为空")
        @Size(max = 4000, message = "question 最大不能超过 4000 个字符")
        String question,

        @NotNull(message = "topK 不能为空")
        @Min(value = 1, message = "topK 不能小于 1")
        @Max(value = 20, message = "topK 不能大于 20")
        Integer topK,

        @NotNull(message = "minSimilarity 不能为空")
        @DecimalMin(value = "-1.0", message = "minSimilarity 不能小于 -1")
        @DecimalMax(value = "1.0", message = "minSimilarity 不能大于 1")
        Double minSimilarity
) {
}
