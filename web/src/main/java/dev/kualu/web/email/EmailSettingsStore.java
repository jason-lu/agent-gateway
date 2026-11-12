package dev.kualu.web.email;

import java.util.Optional;

/** The singleton outbound-mail configuration. */
public interface EmailSettingsStore {

    Optional<EmailSettings> find();

    void save(EmailSettings settings);
}
