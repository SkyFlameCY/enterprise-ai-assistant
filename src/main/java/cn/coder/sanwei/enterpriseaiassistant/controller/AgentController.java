package cn.coder.sanwei.enterpriseaiassistant.controller;

import cn.coder.sanwei.enterpriseaiassistant.context.AgentExecutionContext;
import cn.coder.sanwei.enterpriseaiassistant.dto.ChatRequest;
import cn.coder.sanwei.enterpriseaiassistant.dto.ChatResponse;
import cn.coder.sanwei.enterpriseaiassistant.security.AgentUserDetails;
import cn.coder.sanwei.enterpriseaiassistant.service.AgentService;
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

    private final AgentService agentService;

    public AgentController(AgentService agentService) {
        this.agentService = agentService;
    }

    @PostMapping("/chat")
    public ChatResponse chat(
            @Valid @RequestBody ChatRequest request,
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

        return new ChatResponse(agentService.chat(request.message(), context));
    }

 }
