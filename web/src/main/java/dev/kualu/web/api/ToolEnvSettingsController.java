package dev.kualu.web.api;

import dev.kualu.web.tools.ToolEnvKeys;
import dev.kualu.web.tools.UserToolEnvStore;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;
import java.util.TreeMap;

/**
 * The caller's own tool credentials ({@code GH_TOKEN} and friends), injected into
 * that user's exec runs. Values are secrets: every response carries the
 * {@link #MASK} placeholder instead. A {@link #MASK} value in a request means
 * "keep what is stored".
 */
@RestController
@RequestMapping("/api/v1/settings/tool-env")
public class ToolEnvSettingsController {

    /** Placeholder returned in place of real values. */
    public static final String MASK = "••••••••";

    private final UserToolEnvStore store;

    public ToolEnvSettingsController(UserToolEnvStore store) {
        this.store = store;
    }

    @GetMapping
    public Map<String, String> list(Authentication authentication) {
        Map<String, String> masked = new TreeMap<>();
        for (String key : store.listByOwner(authentication.getName()).keySet()) {
            masked.put(key, MASK);
        }
        return masked;
    }

    @PutMapping("/{key}")
    public Map<String, String> save(
            Authentication authentication, @PathVariable String key, @RequestBody ToolEnvBody body) {
        String owner = authentication.getName();
        String value = body.value();
        if (MASK.equals(value)) {
            if (store.find(owner, key).isEmpty()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "cannot keep a masked value that was never stored: " + key);
            }
            return Map.of(key, MASK);
        }
        try {
            ToolEnvKeys.validate(key, value);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage());
        }
        store.save(owner, key, value);
        return Map.of(key, MASK);
    }

    @DeleteMapping("/{key}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(Authentication authentication, @PathVariable String key) {
        if (!store.delete(authentication.getName(), key)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "no such key: " + key);
        }
    }

    public record ToolEnvBody(@NotBlank String value) {
    }
}
