package cn.coder.sanwei.enterpriseaiassistant.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * EmbeddingRequest
 *
 * @author caiyang
 * @date 2026/9/17
 */
public record EmbeddingRequest(
        @NotBlank(message = "text 不能为空")
        @Size(max = 4000, message = "text 最大不超过 4000 个字符")
        String text
) {
}
