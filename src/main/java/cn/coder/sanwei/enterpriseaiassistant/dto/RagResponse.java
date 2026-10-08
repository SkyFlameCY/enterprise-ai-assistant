package cn.coder.sanwei.enterpriseaiassistant.dto;

import cn.coder.sanwei.enterpriseaiassistant.model.KnowledgeSearchHit;

import java.util.List;

/**
 * RagResponse
 *
 * @author caiyang
 * @date 2026/10/1
 */
public record RagResponse(
        String answer,
        List<KnowledgeSearchHit> sources
) {
}
