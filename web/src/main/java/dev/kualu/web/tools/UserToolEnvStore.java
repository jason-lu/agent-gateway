package dev.kualu.web.tools;

import java.util.Map;
import java.util.Optional;

/**
 * Persistence for per-user tool credentials (e.g. {@code GH_TOKEN}). Implementations
 * must isolate by {@code owner}; values are secrets and are masked in API responses.
 */
public interface UserToolEnvStore {

    /** All stored entries for this owner, sorted by key. */
    Map<String, String> listByOwner(String owner);

    Optional<String> find(String owner, String key);

    /** Insert or replace the named entry for this owner. */
    void save(String owner, String key, String value);

    boolean delete(String owner, String key);
}
