package dev.kualu.web.llm;

import java.util.List;
import java.util.Optional;

/** Persistence for the admin-configured LLM providers. */
public interface LlmProviderStore {

    List<LlmProvider> findAll();

    Optional<LlmProvider> find(String id);

    /** Insert or replace the row. {@code provider.primary()} is stored as given. */
    void save(LlmProvider provider);

    boolean delete(String id);

    /**
     * Makes {@code id} the only primary: every other row loses the flag in the same
     * atomic step, so "exactly one primary" holds between calls. A no-op when the id
     * is unknown.
     */
    void setPrimary(String id);
}
