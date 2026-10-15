package cn.coder.sanwei.enterpriseaiassistant.service;

import cn.coder.sanwei.enterpriseaiassistant.context.AgentExecutionContext;
import cn.coder.sanwei.enterpriseaiassistant.dto.RagResponse;

/**
 * RagService
 *
 * @author caiyang
 * @date 2026/10/1
 */
public interface RagService {

    RagResponse ask(String question, int topK, double minSimilarity, AgentExecutionContext context);
}
