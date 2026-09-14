package cn.coder.sanwei.enterpriseaiassistant.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * ChatRequest
 *
 * @author caiyang
 * @date 2026/9/10
 */
public record ChatRequest(
        @NotBlank(message = "message 不能为空")
        @Size(max = 4000, message = "message 不能超过 4000 个字符")
        String message
) {
}
