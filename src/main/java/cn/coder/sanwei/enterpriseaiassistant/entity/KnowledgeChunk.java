package cn.coder.sanwei.enterpriseaiassistant.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * KnowledgeChunk
 *
 * @author caiyang
 * @date 2026/9/28
 */
@Data
public class KnowledgeChunk {

    private Long id;

    private Long documentId;

    private Integer chunkIndex;

    private String content;

    private String contentSha256;

    private String embedding;

    private String embeddingModel;

    private String embeddingRevision;

    private LocalDateTime createdAt;
}
