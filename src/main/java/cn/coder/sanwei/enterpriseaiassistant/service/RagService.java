package cn.coder.sanwei.enterpriseaiassistant.service;

import cn.coder.sanwei.enterpriseaiassistant.dto.RagResponse;

/**
 * RagService
 *
 * @author caiyang
 * @date 2026/10/1
 */
public interface RagService {

    RagResponse ask(long tenantId, String question, int topK, double minSimilarity);
}
