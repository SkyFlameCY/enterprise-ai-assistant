package cn.coder.sanwei.enterpriseaiassistant.model;

/**
 * KnowledgeSearchHit
 *
 * @author caiyang
 * @date 2026/9/30
 */
public record KnowledgeSearchHit(
        long chunkId,
        long documentId,
        String title,
        String sourceRef,
        int chunkIndex,
        String content,
        double similarity
) {
}
