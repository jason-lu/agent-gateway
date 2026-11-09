package dev.kualu.web.llm;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Process-local provider rows. Only registered when {@code agent.store=memory}. */
public class InMemoryLlmProviderStore implements LlmProviderStore {

    private final Map<String, LlmProvider> rows = new LinkedHashMap<>();

    @Override
    public synchronized List<LlmProvider> findAll() {
        List<LlmProvider> all = new ArrayList<>(rows.values());
        all.sort(Comparator.comparing(LlmProvider::name));
        return all;
    }

    @Override
    public synchronized Optional<LlmProvider> find(String id) {
        return Optional.ofNullable(rows.get(id));
    }

    @Override
    public synchronized void save(LlmProvider provider) {
        rows.put(provider.id(), provider);
    }

    @Override
    public synchronized boolean delete(String id) {
        return rows.remove(id) != null;
    }

    @Override
    public synchronized void setPrimary(String id) {
        for (Map.Entry<String, LlmProvider> entry : rows.entrySet()) {
            boolean flag = entry.getKey().equals(id);
            if (entry.getValue().primary() != flag) {
                entry.setValue(entry.getValue().withPrimary(flag));
            }
        }
    }
}
