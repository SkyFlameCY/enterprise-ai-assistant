package cn.coder.sanwei.enterpriseaiassistant.service;

import cn.coder.sanwei.enterpriseaiassistant.context.AgentExecutionContext;
import cn.coder.sanwei.enterpriseaiassistant.entity.KnowledgeDocument;

import java.io.IOException;
import java.nio.file.Path;

/**
 * KnowledgeImportService
 *
 * @author caiyang
 * @date 2026/9/30
 */
public interface KnowledgeImportService {

    Long importText(KnowledgeDocument document, String text, AgentExecutionContext context);

    Long importFile(KnowledgeDocument document, Path file, AgentExecutionContext context) throws IOException;
}
