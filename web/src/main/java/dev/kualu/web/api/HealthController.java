package dev.kualu.web.api;

import dev.kualu.web.api.ApiDtos.HealthResponse;
import dev.kualu.web.chat.ChatService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class HealthController {

    private final ChatService chatService;

    public HealthController(ChatService chatService) {
        this.chatService = chatService;
    }

    @GetMapping("/health")
    public HealthResponse health() {
        return new HealthResponse("UP", chatService.isLlmConfigured());
    }
}
