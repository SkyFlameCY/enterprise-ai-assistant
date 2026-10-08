package cn.coder.sanwei.enterpriseaiassistant.service.impl;

import cn.coder.sanwei.enterpriseaiassistant.entity.KnowledgeChunk;
import cn.coder.sanwei.enterpriseaiassistant.entity.KnowledgeDocument;
import cn.coder.sanwei.enterpriseaiassistant.exception.AiServiceException;
import cn.coder.sanwei.enterpriseaiassistant.exception.DocumentAlreadyExistsException;
import cn.coder.sanwei.enterpriseaiassistant.repository.JdbcKnowledgeRepository;
import cn.coder.sanwei.enterpriseaiassistant.service.EmbeddingService;
import cn.coder.sanwei.enterpriseaiassistant.service.KnowledgeImportService;
import cn.coder.sanwei.enterpriseaiassistant.service.KnowledgeStorageService;
import cn.coder.sanwei.enterpriseaiassistant.utils.TextChunker;
import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.digest.DigestUtil;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * KnowledgeImportServiceImpl
 *
 * @author caiyang
 * @date 2026/9/30
 */
@Service
public class KnowledgeImportServiceImpl implements KnowledgeImportService {

    @Value("${spring.ai.ollama.embedding.options.model}")
    private String EMBEDDING_MODEL;

    @Value("${knowledge.embedding-revision}")
    private String EMBEDDING_REVISION;

    private final EmbeddingService embeddingService;

    private final KnowledgeStorageService knowledgeStorageService;

    private final JdbcKnowledgeRepository repository;

    public KnowledgeImportServiceImpl(EmbeddingService embeddingService,
                                      KnowledgeStorageService knowledgeStorageService,
                                      JdbcKnowledgeRepository repository) {
        this.embeddingService  = embeddingService;
        this.knowledgeStorageService = knowledgeStorageService;
        this.repository = repository;
    }

    @Override
    public Long importText(KnowledgeDocument document, String text) {
        if (repository.existsDocument(document.getTenantId(), document.getDocumentKey(), document.getVersion())) {
            throw new DocumentAlreadyExistsException("改文档版本已存在");
        }
        List<String> parts = TextChunker.split(text, 500, 100);
        List<KnowledgeChunk> chunks = new ArrayList<>();
        int seq = 0;
        for (String part : parts) {
            float[] vector = embeddingService.embed(part);
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

            KnowledgeChunk chunk = new KnowledgeChunk();
            chunk.setChunkIndex(seq++);
            chunk.setContent(part);
            chunk.setContentSha256(DigestUtil.sha256Hex(part));
            chunk.setEmbedding(Arrays.toString(vector));
            chunk.setEmbeddingModel(EMBEDDING_MODEL);
            chunk.setEmbeddingRevision(EMBEDDING_REVISION);
            chunks.add(chunk);
        }
        if (CollectionUtil.isEmpty(chunks)) {
            throw new AiServiceException("切割出片段为空");
        }
        document.setContentSha256(DigestUtil.sha256Hex(text));
        document.setStatus("ACTIVE");
        if (document.getPermissionMetadata() == null) {
            document.setPermissionMetadata("{}");
        }
        return knowledgeStorageService.saveDocumentWithChunk(document, chunks);
    }

    @Override
    public Long importFile(KnowledgeDocument document, Path file) throws IOException {
        if (file == null || !Files.isRegularFile(file)) {
            throw new IllegalArgumentException("文件不能为空且须是普通文件");
        }

        String filename = file.getFileName().toString().toLowerCase(Locale.ROOT);
        if (!filename.endsWith(".txt") && !filename.endsWith(".md")) {
            throw new IllegalArgumentException("只支持 TXT 和 Markdown 文件");
        }

        if (Files.size(file) > 1024 * 1024) {
            throw new IllegalArgumentException("文件不能超过 1 MB");
        }

        try {
            String text = Files.readString(file, StandardCharsets.UTF_8);
            if (StrUtil.isBlank(document.getSourceRef())) {
                document.setSourceRef(file.getFileName().toString());
            }
            return importText(document, text);
        } catch (IOException e) {
            throw new IOException("文件读取失败", e);
        }
    }
}
