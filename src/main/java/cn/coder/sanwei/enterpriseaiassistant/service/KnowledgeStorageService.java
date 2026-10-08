package cn.coder.sanwei.enterpriseaiassistant.service;

import cn.coder.sanwei.enterpriseaiassistant.entity.KnowledgeChunk;
import cn.coder.sanwei.enterpriseaiassistant.entity.KnowledgeDocument;

import java.util.List;

/**
 * KnowledgeStorageService
 *
 * @author caiyang
 * @date 2026/9/28
 */
public interface KnowledgeStorageService {

    Long saveDocumentWithChunk(KnowledgeDocument document, List<KnowledgeChunk> chunks);
}
