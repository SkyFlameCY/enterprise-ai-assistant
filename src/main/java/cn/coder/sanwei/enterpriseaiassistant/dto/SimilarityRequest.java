package cn.coder.sanwei.enterpriseaiassistant.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * SimilarityRequest
 *
 * @author caiyang
 * @date 2026/9/17
 */
public record SimilarityRequest(
        @NotBlank(message = "left 不能为空")
        @Size(max = 4000, message = "left 最大不超过 4000 个字符")
        String left,
        @NotBlank(message = "right 不能为空")
        @Size(max = 4000, message = "right 最大不超过 4000 个字符")
        String right
) {
}
