package cn.coder.sanwei.enterpriseaiassistant.service;

import cn.coder.sanwei.enterpriseaiassistant.model.SimilarityResult;

/**
 * EmbeddingService
 *
 * @author caiyang
 * @date 2026/9/17
 */
public interface EmbeddingService {

    /**
     * 调用 AI，把文本向量化
     * @param text 需要向量化的文本
     * @return 向量
     */
    float[] embed(String text);

    /**
     * 分别生成两个向量，再计算余弦相似度
     * @param left 文本A
     * @param right 文本B
     * @return 余弦相似度和对比的向量维度
     */
    SimilarityResult similarity(String left, String right);
}
