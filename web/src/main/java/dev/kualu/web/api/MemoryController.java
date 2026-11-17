package dev.kualu.web.api;

import dev.kualu.llm.memory.HardIdentifierDetector;
import dev.kualu.llm.memory.MemoryEntry;
import dev.kualu.llm.memory.UserProfile;
import dev.kualu.web.api.ApiDtos.MemoryEntryResponse;
import dev.kualu.web.api.ApiDtos.MemoryListResponse;
import dev.kualu.web.api.ApiDtos.MemoryUpdateBody;
import dev.kualu.web.api.ApiDtos.ProfileBody;
import dev.kualu.web.api.ApiDtos.ProfileResponse;
import dev.kualu.web.memory.MemoryStore;
import dev.kualu.web.memory.MemoryTools;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * The memory panel API: view, edit, delete. Writes of new entries happen only
 * through the in-conversation tools — there is no create endpoint, so nothing
 * can be persisted without passing the tool gate. Edits here pass the same
 * hard-identifier gate. Everything is owner-scoped; there is no admin view.
 */
@RestController
@RequestMapping("/api/v1")
public class MemoryController {

    private static final String REFUSAL_PII =
            "内容含身份信息（手机号/邮箱/证件/住址等），按隐私策略不能保存";

    private final MemoryStore store;
    private final HardIdentifierDetector detector = new HardIdentifierDetector();

    public MemoryController(MemoryStore store) {
        this.store = store;
    }

    @GetMapping("/memories")
    public MemoryListResponse list(@AuthenticationPrincipal String username) {
        UserProfile profile = store.findProfile(username).orElse(null);
        List<MemoryEntryResponse> entries = store.listEntries(username).stream()
                .map(MemoryController::toResponse)
                .toList();
        return new MemoryListResponse(toProfileResponse(profile), entries);
    }

    @PatchMapping("/memories/{id}")
    public MemoryEntryResponse update(@AuthenticationPrincipal String username,
            @PathVariable String id, @RequestBody MemoryUpdateBody body) {
        String content = body.content() == null ? "" : body.content().strip();
        if (content.isEmpty() || content.length() > MemoryTools.MAX_CONTENT_CHARS) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "content 需为 1–" + MemoryTools.MAX_CONTENT_CHARS + " 字符");
        }
        if (detector.scan(content).isPresent()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, REFUSAL_PII);
        }
        MemoryEntry entry = store.findEntry(username, parseId(id))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "not found"));
        MemoryEntry updated = store.saveEntry(new MemoryEntry(
                entry.id(), entry.owner(), entry.kind(), content, entry.source(),
                entry.sourceSessionId(), entry.createdAt(), entry.updatedAt()));
        return toResponse(updated);
    }

    /** Idempotent: deleting a missing entry is still a success. */
    @DeleteMapping("/memories/{id}")
    public ResponseEntity<Void> delete(@AuthenticationPrincipal String username, @PathVariable String id) {
        store.deleteEntry(username, parseId(id));
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/memories/clear")
    public ResponseEntity<Void> clear(@AuthenticationPrincipal String username) {
        store.clear(username);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/profile")
    public ProfileResponse getProfile(@AuthenticationPrincipal String username) {
        return toProfileResponse(store.findProfile(username).orElse(null));
    }

    /** PUT semantics: the body is the whole profile, a null field clears it. */
    @PutMapping("/profile")
    public ProfileResponse putProfile(@AuthenticationPrincipal String username,
            @RequestBody ProfileBody body) {
        String nickname = trimmedOrNull(body.nickname());
        String city = trimmedOrNull(body.city());
        UserProfile.Gender gender = parseGender(body.gender());

        if (nickname != null) {
            if (nickname.length() > MemoryTools.MAX_NICKNAME_CHARS) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "称呼需为 1–" + MemoryTools.MAX_NICKNAME_CHARS + " 字符");
            }
            if (detector.scan(nickname).isPresent()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, REFUSAL_PII);
            }
        }
        if (city != null) {
            if (city.length() > MemoryTools.MAX_CITY_CHARS) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "城市需为 1–" + MemoryTools.MAX_CITY_CHARS + " 字符");
            }
            if (MemoryTools.cityTooGranular(city) || detector.scan(city).isPresent()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "城市只写到市级（不含区/县/街道/镇/路/门牌）：" + REFUSAL_PII);
            }
        }
        if (body.gender() != null && gender == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "gender 只能是 male / female / unspecified");
        }
        store.saveProfile(username, new UserProfile(nickname, gender, city));
        return new ProfileResponse(nickname,
                gender == null ? null : gender.name().toLowerCase(Locale.ROOT), city);
    }

    private static UUID parseId(String id) {
        try {
            return UUID.fromString(id);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "not found");
        }
    }

    private static String trimmedOrNull(String value) {
        return value == null ? null : value.strip();
    }

    private static UserProfile.Gender parseGender(String value) {
        if (value == null) {
            return null;
        }
        return switch (value.strip().toLowerCase(Locale.ROOT)) {
            case "male" -> UserProfile.Gender.MALE;
            case "female" -> UserProfile.Gender.FEMALE;
            case "unspecified" -> UserProfile.Gender.UNSPECIFIED;
            default -> null;
        };
    }

    private static ProfileResponse toProfileResponse(UserProfile profile) {
        if (profile == null) {
            return new ProfileResponse(null, null, null);
        }
        return new ProfileResponse(profile.nickname(),
                profile.gender() == null ? null : profile.gender().name().toLowerCase(Locale.ROOT),
                profile.city());
    }

    private static MemoryEntryResponse toResponse(MemoryEntry entry) {
        return new MemoryEntryResponse(
                entry.id().toString(),
                entry.kind().name().toLowerCase(Locale.ROOT),
                entry.content(),
                entry.source().name().toLowerCase(Locale.ROOT),
                entry.sourceSessionId(),
                entry.createdAt() == null ? null : entry.createdAt().toString(),
                entry.updatedAt() == null ? null : entry.updatedAt().toString());
    }
}
