package cn.coder.sanwei.enterpriseaiassistant.service.impl;

import cn.coder.sanwei.enterpriseaiassistant.exception.AiServiceException;
import cn.coder.sanwei.enterpriseaiassistant.model.SimilarityResult;
import cn.coder.sanwei.enterpriseaiassistant.service.EmbeddingService;
import cn.hutool.core.util.ArrayUtil;
import cn.hutool.core.util.StrUtil;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.stereotype.Service;

/**
 * EmbeddingServiceImpl
 *
 * @author caiyang
 * @date 2026/9/17
 */
@Service
public class EmbeddingServiceImpl implements EmbeddingService {

    private final EmbeddingModel embeddingModel;

    public EmbeddingServiceImpl(EmbeddingModel embeddingModel) {
        this.embeddingModel = embeddingModel;
    }

    @Override
    public float[] embed(String text) {
        if (StrUtil.isBlank(text)) {
            throw new IllegalArgumentException("文本不能为空或者空白字符");
        }
        float[] vector;
        try {
            vector = embeddingModel.embed(text);
        } catch (RuntimeException e) {
            throw new AiServiceException("Embedding 模型调用失败", e);
        }
        if (ArrayUtil.isEmpty(vector)) {
            throw new AiServiceException("模型生成 Vector 出错");
        }
        return vector;
    }

    @Override
    public SimilarityResult similarity(String left, String right) {
        if (StrUtil.isBlank(left) || StrUtil.isBlank(right)) {
            throw new IllegalArgumentException("文本不能为空或者空白字符");
        }
        float[] vectorA = embed(left);
        float[] vectorB = embed(right);

        if (vectorA.length != vectorB.length) {
            throw new IllegalArgumentException("两个向量长度必须一样");
        }

        double dotProduct = 0.0;
        double normA = 0.0;
        double normB = 0.0;

        for (int i = 0; i < vectorA.length; i++) {
            dotProduct += (double) vectorA[i] * vectorB[i];
            normA += (double) vectorA[i] * vectorA[i];
            normB += (double) vectorB[i] * vectorB[i];
        }

        if (normA == 0.0 || normB == 0.0) {
            throw new IllegalArgumentException("向量模长不能为 0");
        }

        double score = dotProduct / (Math.sqrt(normA) * Math.sqrt(normB));

        return new SimilarityResult(score, vectorA.length);
    }
}
