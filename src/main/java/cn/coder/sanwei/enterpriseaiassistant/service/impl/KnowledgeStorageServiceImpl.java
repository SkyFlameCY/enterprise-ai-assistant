package cn.coder.sanwei.enterpriseaiassistant.service.impl;

import cn.coder.sanwei.enterpriseaiassistant.entity.KnowledgeChunk;
import cn.coder.sanwei.enterpriseaiassistant.entity.KnowledgeDocument;
import cn.coder.sanwei.enterpriseaiassistant.repository.JdbcKnowledgeRepository;
import cn.coder.sanwei.enterpriseaiassistant.service.KnowledgeStorageService;
import cn.hutool.core.collection.CollectionUtil;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * KnowledgeStorageServiceImpl
 *
 * @author caiyang
 * @date 2026/9/30
 */
@Service
public class KnowledgeStorageServiceImpl implements KnowledgeStorageService {

    private final JdbcKnowledgeRepository repository;

    public KnowledgeStorageServiceImpl(JdbcKnowledgeRepository repository) {
        this.repository = repository;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long saveDocumentWithChunk(KnowledgeDocument document, List<KnowledgeChunk> chunks) {

        if (CollectionUtil.isEmpty(chunks)) {
            throw new IllegalArgumentException("文档片段不能为空");
        }

        Long documentId = repository.insertDocument(document);

        for (KnowledgeChunk chunk : chunks) {
            chunk.setDocumentId(documentId);
            repository.insertChunk(chunk);
        }

        return documentId;
    }
}
