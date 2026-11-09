package dev.kualu.web.llm;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** LLM providers in {@code agent.llm_providers} (see {@code V9__llm_providers.sql}). */
public class PostgresLlmProviderStore implements LlmProviderStore {

    private static final TypeReference<List<String>> LIST_TYPE = new TypeReference<>() {
    };

    private static final String COLUMNS =
            "id, name, base_url, api_key, models, default_model, is_primary";

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public PostgresLlmProviderStore(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @Override
    public List<LlmProvider> findAll() {
        return jdbc.query(
                "select " + COLUMNS + " from agent.llm_providers order by name",
                (rs, rowNum) -> read(rs));
    }

    @Override
    public Optional<LlmProvider> find(String id) {
        return jdbc.query(
                "select " + COLUMNS + " from agent.llm_providers where id = ?",
                (rs, rowNum) -> read(rs), id).stream().findFirst();
    }

    @Override
    public void save(LlmProvider provider) {
        jdbc.update(
                "insert into agent.llm_providers"
                        + " (id, name, base_url, api_key, models, default_model, is_primary, updated_at)"
                        + " values (?, ?, ?, ?, ?::jsonb, ?, ?, ?)"
                        + " on conflict (id) do update set"
                        + " name = excluded.name,"
                        + " base_url = excluded.base_url,"
                        + " api_key = excluded.api_key,"
                        + " models = excluded.models,"
                        + " default_model = excluded.default_model,"
                        + " is_primary = excluded.is_primary,"
                        + " updated_at = excluded.updated_at",
                provider.id(), provider.name(), provider.baseUrl(), provider.apiKey(),
                write(provider.models()), provider.defaultModel(), provider.primary(),
                Timestamp.from(Instant.now()));
    }

    @Override
    public boolean delete(String id) {
        return jdbc.update("delete from agent.llm_providers where id = ?", id) > 0;
    }

    /**
     * Clear-then-set inside one transaction: a single {@code set is_primary = (id = ?)}
     * could hit the one-primary index mid-statement depending on row order.
     */
    @Override
    @Transactional
    public void setPrimary(String id) {
        jdbc.update("update agent.llm_providers set is_primary = false where is_primary");
        jdbc.update("update agent.llm_providers set is_primary = true where id = ?", id);
    }

    private LlmProvider read(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new LlmProvider(
                rs.getString("id"),
                rs.getString("name"),
                rs.getString("base_url"),
                rs.getString("api_key"),
                readList(rs.getString("models")),
                rs.getString("default_model"),
                rs.getBoolean("is_primary"));
    }

    private List<String> readList(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        return List.copyOf(mapper.readValue(json, LIST_TYPE));
    }

    private String write(List<String> list) {
        return mapper.writeValueAsString(list == null ? List.of() : list);
    }
}
