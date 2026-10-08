package cn.coder.sanwei.enterpriseaiassistant.service.impl;

import cn.coder.sanwei.enterpriseaiassistant.exception.AiServiceException;
import cn.coder.sanwei.enterpriseaiassistant.model.KnowledgeSearchHit;
import cn.coder.sanwei.enterpriseaiassistant.repository.JdbcKnowledgeRepository;
import cn.coder.sanwei.enterpriseaiassistant.service.EmbeddingService;
import cn.coder.sanwei.enterpriseaiassistant.service.KnowledgeRetrievalService;
import cn.hutool.core.util.StrUtil;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.List;

/**
 * KnowledgeRetrievalServiceImpl
 *
 * @author caiyang
 * @date 2026/10/1
 */
@Service
public class KnowledgeRetrievalServiceImpl implements KnowledgeRetrievalService {

    @Value("${spring.ai.ollama.embedding.options.model}")
    private String EMBEDDING_MODEL;

    @Value("${knowledge.embedding-revision}")
    private String EMBEDDING_REVISION;

    private final EmbeddingService embeddingService;

    private final JdbcKnowledgeRepository repository;

    public KnowledgeRetrievalServiceImpl(EmbeddingService embeddingService,
                                         JdbcKnowledgeRepository repository) {
        this.embeddingService = embeddingService;
        this.repository = repository;
    }

    @Override
    public List<KnowledgeSearchHit> retrieval(long tenantId, String question, int topK, double minSimilarity) {
        if (tenantId <= 0) {
            throw new IllegalArgumentException("组户 ID 不正确");
        }
        if (StrUtil.isBlank(question)) {
            throw new IllegalArgumentException("问题不能为空");
        }
        if (topK < 1 || topK > 20) {
            throw new IllegalArgumentException("topK 必须在 1-20 内");
        }
        if (!Double.isFinite(minSimilarity) || minSimilarity < -1.0 || minSimilarity > 1) {
            throw new IllegalArgumentException("阈值必须是 [-1,1] 范围内的有限值");
        }

        float[] vector = embeddingService.embed(question);
        if (vector.length != 1024) {
            throw new AiServiceException("模型返回的向量维度必须为 1024");
        }
        boolean hasNonZero = false;
        for (float value : vector) {
            if (value != 0.0f) {
                hasNonZero = true;
                break;
            }
        }
        if (!hasNonZero) {
            throw new AiServiceException("模型返回零向量，无法用于检索");
        }

        List<KnowledgeSearchHit> hits = repository.findTopK(tenantId, EMBEDDING_MODEL, EMBEDDING_REVISION, Arrays.toString(vector), topK);

        return hits.stream().filter(hit -> hit.similarity() >= minSimilarity).toList();
    }
}
