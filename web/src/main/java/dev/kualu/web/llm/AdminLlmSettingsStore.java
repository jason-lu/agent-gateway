package dev.kualu.web.llm;

import java.util.Optional;

/** Persistence for the admin LLM upstream overrides. One deployment-level row. */
public interface AdminLlmSettingsStore {

    Optional<AdminLlmSettings> find();

    /** Insert or replace the single settings row. */
    void save(AdminLlmSettings settings);

    /** Remove the row so every field falls back to the environment configuration. */
    void delete();
}
