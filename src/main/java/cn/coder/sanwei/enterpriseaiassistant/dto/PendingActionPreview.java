package cn.coder.sanwei.enterpriseaiassistant.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * PendingActionPreview
 *
 * @author caiyang
 * @date 2026/10/21
 */
public record PendingActionPreview(

        UUID actionId,

        String actionType,

        String status,

        CreateTicketParams params,

        String previewHash,

        OffsetDateTime expiresAt
) {
}
