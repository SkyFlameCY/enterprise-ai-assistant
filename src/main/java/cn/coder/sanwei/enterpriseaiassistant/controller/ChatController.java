package cn.coder.sanwei.enterpriseaiassistant.controller;

import cn.coder.sanwei.enterpriseaiassistant.dto.ChatRequest;
import cn.coder.sanwei.enterpriseaiassistant.dto.ChatResponse;
import cn.coder.sanwei.enterpriseaiassistant.service.ChatService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * ChatController
 *
 * @author caiyang
 * @date 2026/9/10
 */
@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private final ChatService chatService;

    public ChatController(ChatService chatService) {
        this.chatService = chatService;
    }

    @PostMapping
    public ChatResponse chat(@Valid @RequestBody ChatRequest request) {
        String answer = chatService.chat(request.message());
        return new ChatResponse(answer);
    }
}
