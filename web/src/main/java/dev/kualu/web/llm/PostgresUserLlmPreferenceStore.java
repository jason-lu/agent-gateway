package dev.kualu.web.llm;

import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;

/** User model choices in {@code agent.user_llm_preferences} (see {@code V9__llm_providers.sql}). */
public class PostgresUserLlmPreferenceStore implements UserLlmPreferenceStore {

    private final JdbcTemplate jdbc;

    public PostgresUserLlmPreferenceStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<UserLlmPreference> find(String owner) {
        return jdbc.query(
                "select provider_id, model from agent.user_llm_preferences where owner = ?",
                (rs, rowNum) -> new UserLlmPreference(rs.getString("provider_id"), rs.getString("model")),
                owner).stream().findFirst();
    }

    @Override
    public void save(String owner, UserLlmPreference preference) {
        jdbc.update(
                "insert into agent.user_llm_preferences (owner, provider_id, model, updated_at)"
                        + " values (?, ?, ?, ?)"
                        + " on conflict (owner) do update set"
                        + " provider_id = excluded.provider_id,"
                        + " model = excluded.model,"
                        + " updated_at = excluded.updated_at",
                owner, preference.providerId(), preference.model(), Timestamp.from(Instant.now()));
    }
}
