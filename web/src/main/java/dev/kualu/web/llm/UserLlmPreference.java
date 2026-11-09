package dev.kualu.web.llm;

import java.util.Objects;

/** The (provider, model) pair a user chose in the chat window. */
public record UserLlmPreference(String providerId, String model) {

    public UserLlmPreference {
        Objects.requireNonNull(providerId, "providerId");
        Objects.requireNonNull(model, "model");
    }
}
