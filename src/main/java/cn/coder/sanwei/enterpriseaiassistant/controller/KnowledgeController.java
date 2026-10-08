package cn.coder.sanwei.enterpriseaiassistant.controller;

import cn.coder.sanwei.enterpriseaiassistant.dto.KnowledgeImportRequest;
import cn.coder.sanwei.enterpriseaiassistant.dto.KnowledgeSearchRequest;
import cn.coder.sanwei.enterpriseaiassistant.dto.RagResponse;
import cn.coder.sanwei.enterpriseaiassistant.entity.KnowledgeDocument;
import cn.coder.sanwei.enterpriseaiassistant.model.KnowledgeSearchHit;
import cn.coder.sanwei.enterpriseaiassistant.service.KnowledgeImportService;
import cn.coder.sanwei.enterpriseaiassistant.service.KnowledgeRetrievalService;
import cn.coder.sanwei.enterpriseaiassistant.service.RagService;
import cn.hutool.core.bean.BeanUtil;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * KnowledgeController
 *
 * @author caiyang
 * @date 2026/10/1
 */
@RequestMapping("/knowledge")
@RestController
public class KnowledgeController {

    private final RagService ragService;

    private final KnowledgeRetrievalService retrievalService;

    private final KnowledgeImportService importService;

    public KnowledgeController(RagService ragService,
                               KnowledgeRetrievalService retrievalService,
                               KnowledgeImportService importService) {
        this.ragService = ragService;
        this.retrievalService = retrievalService;
        this.importService = importService;
    }

    @PostMapping("/import/text")
    public Long importText(@Valid @RequestBody KnowledgeImportRequest request) {
        KnowledgeDocument document = BeanUtil.copyProperties(request, KnowledgeDocument.class);
        return importService.importText(document, request.text());
    }

    @PostMapping("/search")
    public List<KnowledgeSearchHit> search(@Valid @RequestBody KnowledgeSearchRequest request) {
        return retrievalService.retrieval(request.tenantId(), request.question(), request.topK(), request.minSimilarity());
    }

    @PostMapping("/ask")
    public RagResponse ask(@Valid @RequestBody KnowledgeSearchRequest request) {
        return ragService.ask(request.tenantId(), request.question(), request.topK(), request.minSimilarity());
    }

}
