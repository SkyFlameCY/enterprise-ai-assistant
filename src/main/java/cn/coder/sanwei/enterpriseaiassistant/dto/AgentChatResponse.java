package cn.coder.sanwei.enterpriseaiassistant.dto;

import java.util.List;

/**
 * AgentChatResponse
 *
 * @author caiyang
 * @date 2026/10/9
 */
public record AgentChatResponse(
        long conversationId,
        long turnNo,
        String answer,
        List<PendingActionPreview> pendingActions
) {
}
