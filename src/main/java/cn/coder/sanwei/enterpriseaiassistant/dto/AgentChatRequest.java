package cn.coder.sanwei.enterpriseaiassistant.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * AgentChatRequest
 *
 * @author caiyang
 * @date 2026/10/9
 */
public record AgentChatRequest(
        @Positive(message = "conversationId 必须为正数")
        Long conversationId,
        @NotBlank(message = "message 不能为空")
        @Size(max = 4000, message = "message 不能超过 4000 个字符")
        String message
) {
}
