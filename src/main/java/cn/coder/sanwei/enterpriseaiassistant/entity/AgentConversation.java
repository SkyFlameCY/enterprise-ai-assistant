package cn.coder.sanwei.enterpriseaiassistant.entity;

import lombok.Data;

import java.time.OffsetDateTime;

/**
 * AgentConversation
 *
 * @author caiyang
 * @date 2026/10/8
 */
@Data
public class AgentConversation {

    private Long id;

    private Long tenantId;

    private Long userId;

    private String title;

    private String status;

    private Long version;

    private OffsetDateTime createdAt;

    private OffsetDateTime updatedAt;
}
