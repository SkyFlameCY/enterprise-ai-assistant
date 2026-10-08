package cn.coder.sanwei.enterpriseaiassistant.service;

import cn.coder.sanwei.enterpriseaiassistant.model.KnowledgeSearchHit;

import java.util.List;

/**
 * KnowledgeRetrievalService
 *
 * @author caiyang
 * @date 2026/9/30
 */
public interface KnowledgeRetrievalService {
    
    List<KnowledgeSearchHit> retrieval(long tenantId, String question, int topK, double minSimilarity);

}
