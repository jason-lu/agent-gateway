package dev.kualu.web.llm;

import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;

/** Admin LLM settings in {@code agent.admin_llm_settings} (see {@code V8__admin_llm_settings.sql}). */
public class PostgresAdminLlmSettingsStore implements AdminLlmSettingsStore {

    private final JdbcTemplate jdbc;

    public PostgresAdminLlmSettingsStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<AdminLlmSettings> find() {
        return jdbc.query(
                "select base_url, api_key, model from agent.admin_llm_settings where id = 1",
                (rs, rowNum) -> new AdminLlmSettings(
                        rs.getString("base_url"),
                        rs.getString("api_key"),
                        rs.getString("model")))
                .stream().findFirst();
    }

    @Override
    public void save(AdminLlmSettings settings) {
        jdbc.update(
                "insert into agent.admin_llm_settings (id, base_url, api_key, model, updated_at)"
                        + " values (1, ?, ?, ?, ?)"
                        + " on conflict (id) do update set"
                        + " base_url = excluded.base_url,"
                        + " api_key = excluded.api_key,"
                        + " model = excluded.model,"
                        + " updated_at = excluded.updated_at",
                settings.baseUrl(), settings.apiKey(), settings.model(), Timestamp.from(Instant.now()));
    }

    @Override
    public void delete() {
        jdbc.update("delete from agent.admin_llm_settings where id = 1");
    }
}
