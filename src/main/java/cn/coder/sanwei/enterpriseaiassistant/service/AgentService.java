package cn.coder.sanwei.enterpriseaiassistant.service;

import cn.coder.sanwei.enterpriseaiassistant.context.AgentExecutionContext;

/**
 * AgentService
 *
 * @author caiyang
 * @date 2026/10/2
 */
public interface AgentService {

    String chat(String message, AgentExecutionContext context);
}
