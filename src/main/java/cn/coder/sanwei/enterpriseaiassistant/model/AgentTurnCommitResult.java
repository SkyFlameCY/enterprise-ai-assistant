package cn.coder.sanwei.enterpriseaiassistant.model;

import cn.coder.sanwei.enterpriseaiassistant.dto.PendingActionPreview;

import java.util.List;

/**
 * AgentTurnCommitResult
 *
 * @author caiyang
 * @date 2026/10/27
 */
public record AgentTurnCommitResult(
        long turnNo,
        List<PendingActionPreview> pendingActions
) {
}
