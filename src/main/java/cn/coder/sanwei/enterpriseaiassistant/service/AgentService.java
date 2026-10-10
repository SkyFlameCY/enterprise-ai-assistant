package cn.coder.sanwei.enterpriseaiassistant.service;

import cn.coder.sanwei.enterpriseaiassistant.context.AgentExecutionContext;
import org.springframework.ai.chat.messages.Message;

import java.util.List;

/**
 * AgentService
 *
 * @author caiyang
 * @date 2026/10/2
 */
public interface AgentService {

    String chat(String message, AgentExecutionContext context);

    String chatWithMessage(List<Message> messages, AgentExecutionContext context);
}
