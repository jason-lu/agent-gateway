package dev.kualu.web.llm;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Process-local preferences. Only registered when {@code agent.store=memory}. */
public class InMemoryUserLlmPreferenceStore implements UserLlmPreferenceStore {

    private final Map<String, UserLlmPreference> rows = new ConcurrentHashMap<>();

    @Override
    public Optional<UserLlmPreference> find(String owner) {
        return Optional.ofNullable(rows.get(owner));
    }

    @Override
    public void save(String owner, UserLlmPreference preference) {
        rows.put(owner, preference);
    }
}
