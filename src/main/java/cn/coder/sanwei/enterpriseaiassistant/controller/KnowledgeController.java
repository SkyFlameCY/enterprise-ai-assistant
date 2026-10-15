package cn.coder.sanwei.enterpriseaiassistant.controller;

import cn.coder.sanwei.enterpriseaiassistant.context.AgentExecutionContext;
import cn.coder.sanwei.enterpriseaiassistant.dto.KnowledgeImportRequest;
import cn.coder.sanwei.enterpriseaiassistant.dto.KnowledgeSearchRequest;
import cn.coder.sanwei.enterpriseaiassistant.dto.RagResponse;
import cn.coder.sanwei.enterpriseaiassistant.entity.KnowledgeDocument;
import cn.coder.sanwei.enterpriseaiassistant.model.KnowledgeSearchHit;
import cn.coder.sanwei.enterpriseaiassistant.security.AgentUserDetails;
import cn.coder.sanwei.enterpriseaiassistant.service.KnowledgeImportService;
import cn.coder.sanwei.enterpriseaiassistant.service.KnowledgeRetrievalService;
import cn.coder.sanwei.enterpriseaiassistant.service.RagService;
import cn.hutool.core.bean.BeanUtil;
import jakarta.validation.Valid;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;

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
    public Long importText(
            @Valid @RequestBody KnowledgeImportRequest request,
            @AuthenticationPrincipal(errorOnInvalidType = true) AgentUserDetails user) {
        AgentExecutionContext context = convertAgentContext(user);
        KnowledgeDocument document = BeanUtil.copyProperties(request, KnowledgeDocument.class);
        return importService.importText(document, request.text(), context);
    }

    @PostMapping("/import/file")
    public Long importFile(
            @Valid @RequestBody KnowledgeImportRequest request,
            Path file,
            @AuthenticationPrincipal(errorOnInvalidType = true) AgentUserDetails user) throws IOException {
        AgentExecutionContext context = convertAgentContext(user);
        KnowledgeDocument document = BeanUtil.copyProperties(request, KnowledgeDocument.class);
        return importService.importFile(document, file, context);
    }

    @PostMapping("/search")
    public List<KnowledgeSearchHit> search(
            @Valid @RequestBody KnowledgeSearchRequest request,
            @AuthenticationPrincipal(errorOnInvalidType = true) AgentUserDetails user) {
        AgentExecutionContext context = convertAgentContext(user);
        return retrievalService.retrieval(request.question(), request.topK(), request.minSimilarity(), context);
    }

    @PostMapping("/ask")
    public RagResponse ask(
            @Valid @RequestBody KnowledgeSearchRequest request,
            @AuthenticationPrincipal(errorOnInvalidType = true) AgentUserDetails user) {
        AgentExecutionContext context = convertAgentContext(user);
        return ragService.ask(request.question(), request.topK(), request.minSimilarity(), context);
    }

    private AgentExecutionContext convertAgentContext(AgentUserDetails user) {
        if (user == null) {
            throw new IllegalStateException("未取到有效的登录信息");
        }

        var permissions = user.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .collect(Collectors.toUnmodifiableSet());

        return new AgentExecutionContext(
                user.getUserId(),
                user.getTenantId(),
                permissions
        );
    }

}
