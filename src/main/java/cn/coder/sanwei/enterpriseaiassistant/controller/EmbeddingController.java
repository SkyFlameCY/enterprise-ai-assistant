package cn.coder.sanwei.enterpriseaiassistant.controller;

import cn.coder.sanwei.enterpriseaiassistant.dto.EmbeddingRequest;
import cn.coder.sanwei.enterpriseaiassistant.dto.EmbeddingResponse;
import cn.coder.sanwei.enterpriseaiassistant.dto.SimilarityRequest;
import cn.coder.sanwei.enterpriseaiassistant.dto.SimilarityResponse;
import cn.coder.sanwei.enterpriseaiassistant.model.SimilarityResult;
import cn.coder.sanwei.enterpriseaiassistant.service.EmbeddingService;
import cn.hutool.core.convert.Convert;
import cn.hutool.core.util.ArrayUtil;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * EmbeddingController
 *
 * @author caiyang
 * @date 2026/9/17
 */
@RequestMapping("/api/embeddings")
@RestController
public class EmbeddingController {

    private final EmbeddingService embeddingService;

    public EmbeddingController(EmbeddingService embeddingService) {
        this.embeddingService = embeddingService;
    }

    @PostMapping
    public EmbeddingResponse embed(@Valid @RequestBody EmbeddingRequest request) {
        float[] vector = embeddingService.embed(request.text());
        return new EmbeddingResponse(vector.length, Convert.toList(Float.class, ArrayUtil.sub(vector, 0, 8)));
    }

    @PostMapping("/similarity")
    public SimilarityResponse similarity(@Valid @RequestBody SimilarityRequest request) {
        SimilarityResult result = embeddingService.similarity(request.left(), request.right());
        return new SimilarityResponse(result.score(), result.dimensions());
    }
}
