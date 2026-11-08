package dev.kualu.web.llm;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/** Process-local settings row. Only registered when {@code agent.store=memory}. */
public class InMemoryAdminLlmSettingsStore implements AdminLlmSettingsStore {

    private final AtomicReference<AdminLlmSettings> settings = new AtomicReference<>();

    @Override
    public Optional<AdminLlmSettings> find() {
        return Optional.ofNullable(settings.get());
    }

    @Override
    public void save(AdminLlmSettings value) {
        settings.set(value);
    }

    @Override
    public void delete() {
        settings.set(null);
    }
}
