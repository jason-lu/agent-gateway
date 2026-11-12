package dev.kualu.web.email;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/** Process-local settings; only registered when {@code agent.store=memory}. */
public class InMemoryEmailSettingsStore implements EmailSettingsStore {

    private final AtomicReference<EmailSettings> settings = new AtomicReference<>();

    @Override
    public Optional<EmailSettings> find() {
        return Optional.ofNullable(settings.get());
    }

    @Override
    public void save(EmailSettings settings) {
        this.settings.set(settings);
    }
}
