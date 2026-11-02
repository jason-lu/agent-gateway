package dev.kualu.web.api;

import dev.kualu.web.api.ApiDtos.HealthResponse;
import dev.kualu.web.chat.ChatService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class HealthController {

    private final ChatService chatService;
    private final JdbcTemplate jdbc;

    public HealthController(ChatService chatService, ObjectProvider<JdbcTemplate> jdbcs) {
        this.chatService = chatService;
        this.jdbc = jdbcs.getIfAvailable();
    }

    @GetMapping("/health")
    public HealthResponse health() {
        return new HealthResponse("UP", chatService.isLlmConfigured(), dbUp());
    }

    /**
     * {@code agent.store=memory} has no database to probe and reports healthy;
     * otherwise one cheap round trip. A down database must not fail this endpoint —
     * that is exactly when it needs to answer.
     */
    private boolean dbUp() {
        if (jdbc == null) {
            return true;
        }
        try {
            jdbc.queryForObject("select 1", Integer.class);
            return true;
        } catch (DataAccessException ex) {
            return false;
        }
    }
}
