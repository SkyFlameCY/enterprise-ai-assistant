package cn.coder.sanwei.enterpriseaiassistant.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * KnowledgeDocument
 *
 * @author caiyang
 * @date 2026/9/28
 */
@Data
public class KnowledgeDocument {

    private Long id;

    private Long tenantId;

    private String documentKey;

    private Integer version;

    private String title;

    private String sourceRef;

    private String contentSha256;

    private String status;

    private String permissionMetadata;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
