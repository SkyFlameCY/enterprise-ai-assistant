package cn.coder.sanwei.enterpriseaiassistant.controller;

import cn.coder.sanwei.enterpriseaiassistant.context.AgentExecutionContext;
import cn.coder.sanwei.enterpriseaiassistant.dto.AgentChatRequest;
import cn.coder.sanwei.enterpriseaiassistant.dto.AgentChatResponse;
import cn.coder.sanwei.enterpriseaiassistant.security.AgentUserDetails;
import cn.coder.sanwei.enterpriseaiassistant.service.ConversationAgentService;
import jakarta.validation.Valid;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.stream.Collectors;

/**
 * AgentController
 *
 * @author caiyang
 * @date 2026/10/2
 */
@RequestMapping("/agent")
@RestController
public class AgentController {

    private final ConversationAgentService conversationAgentService;

    public AgentController(ConversationAgentService conversationAgentService) {
        this.conversationAgentService = conversationAgentService;
    }

    @PostMapping("/chat")
    public AgentChatResponse chat(
            @Valid @RequestBody AgentChatRequest request,
            @AuthenticationPrincipal(errorOnInvalidType = true) AgentUserDetails user) {
        if (user == null) {
            throw new IllegalStateException("未取到有效的登录信息");
        }

        var permissions = user.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .collect(Collectors.toUnmodifiableSet());

        var context = new AgentExecutionContext(
                user.getUserId(),
                user.getTenantId(),
                permissions
        );

        return conversationAgentService.chat(request.conversationId(), request.message(), context);
    }

 }
