package dev.kualu.web.api;

import dev.kualu.web.api.ApiDtos.CreateSessionRequest;
import dev.kualu.web.api.ApiDtos.HistoryMessage;
import dev.kualu.web.api.ApiDtos.MessageResponse;
import dev.kualu.web.api.ApiDtos.SessionResponse;
import dev.kualu.web.api.ApiDtos.SseDelta;
import dev.kualu.web.api.ApiDtos.SseDone;
import dev.kualu.web.api.ApiDtos.SseError;
import dev.kualu.web.chat.ChatService;
import dev.kualu.web.chat.LlmNotConfiguredException;
import dev.kualu.web.chat.SessionNotFoundException;
import dev.kualu.web.session.Session;
import dev.kualu.web.session.SessionStore;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.concurrent.TimeUnit;

@RestController
@RequestMapping("/api/v1/sessions")
public class SessionController {

    /**
     * Tomcat's connector-wide async timeout is 30s from the moment the async request
     * starts, and SSE writes do not reset it. {@code new SseEmitter()} inherits that
     * default, so any generation longer than 30s is cut mid-stream without a terminal
     * event. Override it with a ceiling generous enough for the longest answers.
     */
    private static final long SSE_TIMEOUT_MS = TimeUnit.MINUTES.toMillis(30);

    private final SessionStore sessionStore;
    private final ChatService chatService;

    public SessionController(SessionStore sessionStore, ChatService chatService) {
        this.sessionStore = sessionStore;
        this.chatService = chatService;
    }

    @GetMapping
    public List<SessionResponse> list(@AuthenticationPrincipal String username) {
        return sessionStore.listByOwner(username).stream()
                .map(SessionController::toResponse)
                .toList();
    }

    @PostMapping
    public ResponseEntity<SessionResponse> create(
            @AuthenticationPrincipal String username,
            @RequestBody(required = false) CreateSessionRequest request) {
        String systemPrompt = request == null ? null : request.systemPrompt();
        Session session = sessionStore.create(username, systemPrompt);
        return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(session));
    }

    @GetMapping("/{id}")
    public SessionResponse get(@AuthenticationPrincipal String username, @PathVariable String id) {
        return toResponse(requireSession(username, id));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@AuthenticationPrincipal String username, @PathVariable String id) {
        if (!sessionStore.delete(username, id)) {
            throw new SessionNotFoundException();
        }
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{id}/messages")
    public List<HistoryMessage> history(@AuthenticationPrincipal String username, @PathVariable String id) {
        return chatService.history(requireSession(username, id));
    }

    @PostMapping("/{id}/messages")
    public MessageResponse send(
            @AuthenticationPrincipal String username,
            @PathVariable String id,
            @Valid @RequestBody MessageBody body) {
        Session session = requireSession(username, id);
        String reply = chatService.chat(session, body.content());
        return new MessageResponse(reply);
    }

    @PostMapping(value = "/{id}/messages/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(
            @AuthenticationPrincipal String username,
            @PathVariable String id,
            @Valid @RequestBody MessageBody body) {
        Session session = requireSession(username, id);
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
        Thread worker = new Thread(() -> {
            try {
                String reply = chatService.chatStream(session, body.content(), delta -> {
                    try {
                        emitter.send(SseEmitter.event().name("delta").data(new SseDelta(delta)));
                    } catch (Exception ex) {
                        throw new IllegalStateException("SSE send failed", ex);
                    }
                });
                emitter.send(SseEmitter.event().name("done").data(new SseDone(reply)));
                emitter.complete();
            } catch (Exception ex) {
                String message = ex instanceof LlmNotConfiguredException
                        ? ex.getMessage()
                        : (ex instanceof dev.kualu.web.chat.UpstreamLlmException
                                ? ex.getMessage()
                                : "stream failed");
                try {
                    emitter.send(SseEmitter.event().name("error").data(new SseError(message)));
                    emitter.complete();
                } catch (Exception ignored) {
                    emitter.completeWithError(ex);
                }
            }
        }, "chat-sse-" + id);
        worker.setDaemon(true);
        worker.start();
        return emitter;
    }

    private Session requireSession(String username, String id) {
        return sessionStore.find(username, id).orElseThrow(SessionNotFoundException::new);
    }

    private static SessionResponse toResponse(Session session) {
        return new SessionResponse(
                session.id(),
                session.owner(),
                session.createdAt().toString(),
                session.messageCount());
    }

    public record MessageBody(@NotBlank String content) {
    }
}
