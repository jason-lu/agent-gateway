package dev.kualu.web.api;

import dev.kualu.web.api.ApiDtos.CreateSessionRequest;
import dev.kualu.web.api.ApiDtos.HistoryMessage;
import dev.kualu.web.api.ApiDtos.MessageResponse;
import dev.kualu.web.api.ApiDtos.SessionResponse;
import dev.kualu.web.api.ApiDtos.SseDelta;
import dev.kualu.web.api.ApiDtos.SseDone;
import dev.kualu.web.api.ApiDtos.SseError;
import dev.kualu.llm.metrics.TurnTimings;
import dev.kualu.web.chat.ChatService;
import dev.kualu.web.chat.LlmNotConfiguredException;
import dev.kualu.web.chat.SessionNotFoundException;
import dev.kualu.web.metrics.TurnMetrics;
import dev.kualu.web.session.Session;
import dev.kualu.web.session.SessionStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.concurrent.TimeUnit;

@RestController
@RequestMapping("/api/v1/sessions")
public class SessionController {

    private static final Logger log = LoggerFactory.getLogger(SessionController.class);

    /**
     * Tomcat's connector-wide async timeout is 30s from the moment the async request
     * starts, and SSE writes do not reset it. {@code new SseEmitter()} inherits that
     * default, so any generation longer than 30s is cut mid-stream without a terminal
     * event. Override it with a ceiling generous enough for the longest answers.
     */
    private static final long SSE_TIMEOUT_MS = TimeUnit.MINUTES.toMillis(30);

    /** Per-message image cap — enough for a photo set, small enough to bound the payload. */
    private static final int MAX_IMAGES = 10;

    /** Per-image cap in characters (base64 data URIs included); ~18 MB of binary. */
    private static final int MAX_IMAGE_CHARS = 25_000_000;

    private final SessionStore sessionStore;
    private final ChatService chatService;
    private final TurnMetrics turnMetrics;

    public SessionController(SessionStore sessionStore, ChatService chatService, TurnMetrics turnMetrics) {
        this.sessionStore = sessionStore;
        this.chatService = chatService;
        this.turnMetrics = turnMetrics;
    }

    @GetMapping
    public List<SessionResponse> list(@AuthenticationPrincipal String username) {
        TurnTimings timings = TurnTimings.begin();
        try {
            return sessionStore.listByOwner(username).stream()
                    .map(summary -> new SessionResponse(
                            summary.id(), summary.owner(), summary.title(),
                            summary.createdAt().toString(), summary.messageCount()))
                    .toList();
        } finally {
            turnMetrics.complete("list", "-", timings);
            TurnTimings.clear();
        }
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
        TurnTimings timings = TurnTimings.begin();
        try {
            return toResponse(requireSession(username, id));
        } finally {
            turnMetrics.complete("get", id, timings);
            TurnTimings.clear();
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@AuthenticationPrincipal String username, @PathVariable String id) {
        if (!sessionStore.delete(username, id)) {
            throw new SessionNotFoundException();
        }
        return ResponseEntity.noContent().build();
    }

    /**
     * Serves one stored message image as raw bytes. History returns these URLs
     * (not the pixels) so opening a chat does not transfer every image inline;
     * the owner check on the session is the authorisation.
     */
    @GetMapping("/{id}/images/{imageId}")
    public ResponseEntity<byte[]> image(
            @AuthenticationPrincipal String username,
            @PathVariable String id,
            @PathVariable String imageId) {
        requireSession(username, id);
        String dataUri = sessionStore.findImageContent(id, imageId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "image not found"));
        return dataUriResponse(dataUri);
    }

    private static ResponseEntity<byte[]> dataUriResponse(String dataUri) {
        int semi = dataUri.indexOf(';');
        int comma = dataUri.indexOf(',');
        if (!dataUri.startsWith("data:") || semi < 0 || comma < 0 || comma < semi) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "image not found");
        }
        byte[] bytes = java.util.Base64.getDecoder().decode(dataUri.substring(comma + 1));
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(dataUri.substring("data:".length(), semi)))
                .body(bytes);
    }

    @GetMapping("/{id}/messages")
    public List<HistoryMessage> history(@AuthenticationPrincipal String username, @PathVariable String id) {
        TurnTimings timings = TurnTimings.begin();
        try {
            return chatService.history(requireSession(username, id));
        } finally {
            turnMetrics.complete("history", id, timings);
            TurnTimings.clear();
        }
    }

    @PostMapping("/{id}/messages")
    public MessageResponse send(
            @AuthenticationPrincipal String username,
            @PathVariable String id,
            @RequestBody MessageBody body) {
        validate(body);
        TurnTimings timings = TurnTimings.begin();
        try {
            Session session = requireSession(username, id);
            String reply = chatService.chat(session, body.content(), body.images());
            return new MessageResponse(reply);
        } finally {
            turnMetrics.complete("send", id, timings);
            TurnTimings.clear();
        }
    }

    @PostMapping(value = "/{id}/messages/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(
            @AuthenticationPrincipal String username,
            @PathVariable String id,
            @RequestBody MessageBody body) {
        validate(body);
        // Bound before the worker thread is created so it inherits this collector;
        // the load segment records on the request thread, the rest inside the worker.
        TurnTimings timings = TurnTimings.begin();
        Session session;
        try {
            session = requireSession(username, id);
        } catch (RuntimeException ex) {
            TurnTimings.clear();
            throw ex;
        }
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
        Thread worker = new Thread(() -> {
            try {
                String reply = chatService.chatStream(session, body.content(), body.images(), delta -> {
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
                // The event below reaches only the caller; the server log is the
                // place an operator sees why turns keep failing.
                if (ex instanceof dev.kualu.web.chat.UpstreamLlmException || ex instanceof LlmNotConfiguredException) {
                    log.warn("chat stream failed for session={}: {}", id, message);
                } else {
                    log.warn("chat stream failed for session={}", id, ex);
                }
                try {
                    emitter.send(SseEmitter.event().name("error").data(new SseError(message)));
                    emitter.complete();
                } catch (Exception ignored) {
                    emitter.completeWithError(ex);
                }
            } finally {
                turnMetrics.complete("stream", id, timings);
                TurnTimings.clear();
            }
        }, "chat-sse-" + id);
        worker.setDaemon(true);
        worker.start();
        // The worker inherited its own binding at construction; clearing here only
        // releases the servlet thread's slot.
        TurnTimings.clear();
        return emitter;
    }

    private Session requireSession(String username, String id) {
        return sessionStore.find(username, id).orElseThrow(SessionNotFoundException::new);
    }

    private static SessionResponse toResponse(Session session) {
        return new SessionResponse(
                session.id(),
                session.owner(),
                session.title(),
                session.createdAt().toString(),
                session.messageCount());
    }

    /**
     * A turn is text and/or images: image-only messages are legal (recognition
     * without a question), blank-and-imageless ones are not.
     */
    private static void validate(MessageBody body) {
        if (body.content().isBlank() && body.images().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "content and images must not both be empty");
        }
        if (body.images().isEmpty()) {
            return;
        }
        if (body.images().size() > MAX_IMAGES) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "at most " + MAX_IMAGES + " images per message");
        }
        for (String image : body.images()) {
            if (image == null || image.isBlank()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "image entries must not be blank");
            }
            if (image.length() > MAX_IMAGE_CHARS) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "image exceeds the size limit");
            }
            if (!isSupportedImageUrl(image)) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST, "images must be data:image/ or http(s) image URLs");
            }
        }
    }

    private static boolean isSupportedImageUrl(String image) {
        return image.startsWith("data:image/")
                || image.startsWith("http://")
                || image.startsWith("https://");
    }

    /**
     * @param content user text; {@code null} is normalized to blank (image-only turns)
     * @param images  image URLs or {@code data:image/...} URIs for vision models;
     *                {@code null} is normalized to an empty list
     */
    public record MessageBody(String content, List<String> images) {

        public MessageBody {
            content = content == null ? "" : content;
            images = images == null ? List.of() : List.copyOf(images);
        }
    }
}
