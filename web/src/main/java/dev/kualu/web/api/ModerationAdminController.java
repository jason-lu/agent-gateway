package dev.kualu.web.api;

import dev.kualu.web.moderation.ModerationEvent;
import dev.kualu.web.moderation.ModerationEventStore;
import dev.kualu.web.moderation.ModerationEventStore.ModerationQuery;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/**
 * Moderation review queue (ADMIN only). Reviewers triage findings, mark false
 * kills ({@code override=NONE}) and confirmed hits ({@code CONFIRM}); the
 * exports feed lexicon iteration. Findings never include the raw violating
 * text — only the masked excerpt.
 */
@RestController
@RequestMapping("/api/v1/admin/moderation")
@PreAuthorize("hasRole('ADMIN')")
public class ModerationAdminController {

    private static final int MAX_PAGE_SIZE = 200;

    private final ModerationEventStore store;

    public ModerationAdminController(ModerationEventStore store) {
        this.store = store;
    }

    /**
     * Newest-first page of audit rows. {@code reviewed=false} is the pending
     * queue; {@code needsReview=true} narrows it to the findings worth eyes.
     */
    @GetMapping("/events")
    public List<ApiDtos.ModerationEventResponse> events(
            @RequestParam(defaultValue = "false") boolean reviewed,
            @RequestParam(defaultValue = "false") boolean needsReview,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        int limit = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        int offset = Math.max(page, 0) * limit;
        ModerationQuery query = new ModerationQuery(!reviewed, needsReview, null, limit, offset);
        return store.list(query).stream().map(ModerationAdminController::toResponse).toList();
    }

    /**
     * Export reviewed samples for lexicon iteration: {@code kind=confirm} (true
     * positives → lexicon terms) or {@code kind=none} (false kills → miss list).
     */
    @GetMapping("/events/export")
    public List<ApiDtos.ModerationEventResponse> export(
            @RequestParam String kind,
            @RequestParam(defaultValue = "500") int size) {
        String override = switch (kind.toLowerCase()) {
            case "confirm" -> "CONFIRM";
            case "none" -> "NONE";
            default -> throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "kind must be 'confirm' or 'none'");
        };
        return store.list(ModerationQuery.byOverride(override, Math.min(Math.max(size, 1), MAX_PAGE_SIZE), 0))
                .stream().map(ModerationAdminController::toResponse).toList();
    }

    /** Records the reviewer's verdict. */
    @PostMapping("/events/{id}/review")
    public ApiDtos.ModerationEventResponse review(
            @PathVariable long id,
            @RequestBody ApiDtos.ModerationReviewBody body) {
        String override = body.override() == null ? null : switch (body.override().toUpperCase()) {
            case "CONFIRM", "NONE" -> body.override().toUpperCase();
            default -> throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "override must be CONFIRM or NONE");
        };
        if (!store.review(id, body.note(), override)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "no such moderation event: " + id);
        }
        return store.find(id).map(ModerationAdminController::toResponse)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "no such event: " + id));
    }

    private static ApiDtos.ModerationEventResponse toResponse(ModerationEvent event) {
        return new ApiDtos.ModerationEventResponse(
                event.id(),
                event.createdAt() == null ? null : event.createdAt().toString(),
                event.owner(),
                event.sessionId(),
                event.direction().name(),
                event.category().name(),
                event.severity().name(),
                event.action().name(),
                event.detector(),
                event.excerpt(),
                event.needsReview(),
                event.reviewed(),
                event.reviewNote(),
                event.reviewOverride());
    }
}
