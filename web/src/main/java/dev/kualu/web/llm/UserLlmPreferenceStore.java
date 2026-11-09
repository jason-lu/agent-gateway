package dev.kualu.web.llm;

import java.util.Optional;

/** Persistence for the model a user picked in the chat window. One row per owner. */
public interface UserLlmPreferenceStore {

    Optional<UserLlmPreference> find(String owner);

    /** Insert or replace the owner's choice. */
    void save(String owner, UserLlmPreference preference);
}
