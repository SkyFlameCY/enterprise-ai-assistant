package cn.coder.sanwei.enterpriseaiassistant.entity;

import lombok.Data;

import java.time.OffsetDateTime;

/**
 * AgentMessage
 *
 * @author caiyang
 * @date 2026/10/9
 */
@Data
public class AgentMessage {

    private Long id;

    private Long conversationId;

    private Long turnNo;

    private String role;

    private String content;

    private OffsetDateTime createdAt;
}
