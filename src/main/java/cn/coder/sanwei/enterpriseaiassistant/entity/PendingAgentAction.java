package cn.coder.sanwei.enterpriseaiassistant.entity;

import lombok.Data;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * PendingAgentAction
 *
 * @author caiyang
 * @date 2026/10/19
 */
@Data
public class PendingAgentAction {

    private UUID id;

    private Long tenantId;

    private Long userId;

    private Long conversationId;

    private UUID requestId;

    private Long sourceTurnNo;

    private String actionType;

    private String status;

    private String paramsJson;

    private String previewHash;

    private OffsetDateTime expiresAt;

    private String resultJson;

    private OffsetDateTime createdAt;

    private OffsetDateTime updatedAt;
}
