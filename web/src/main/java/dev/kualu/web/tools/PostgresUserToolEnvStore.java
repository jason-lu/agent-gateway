package dev.kualu.web.tools;

import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/** User tool credentials in {@code agent.user_tool_env} (see {@code V13__user_tool_env.sql}). */
public class PostgresUserToolEnvStore implements UserToolEnvStore {

    private final JdbcTemplate jdbc;

    public PostgresUserToolEnvStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Map<String, String> listByOwner(String owner) {
        Map<String, String> entries = new TreeMap<>();
        jdbc.query(
                "select key, value from agent.user_tool_env where owner = ? order by key",
                (rs, rowNum) -> {
                    entries.put(rs.getString("key"), rs.getString("value"));
                    return null;
                },
                owner);
        return entries;
    }

    @Override
    public Optional<String> find(String owner, String key) {
        return jdbc.query(
                "select value from agent.user_tool_env where owner = ? and key = ?",
                (rs, rowNum) -> rs.getString("value"),
                owner, key).stream().findFirst();
    }

    @Override
    public void save(String owner, String key, String value) {
        jdbc.update(
                "insert into agent.user_tool_env (owner, key, value, updated_at) values (?, ?, ?, ?)"
                        + " on conflict (owner, key) do update set value = excluded.value,"
                        + " updated_at = excluded.updated_at",
                owner, key, value, Timestamp.from(Instant.now()));
    }

    @Override
    public boolean delete(String owner, String key) {
        return jdbc.update("delete from agent.user_tool_env where owner = ? and key = ?", owner, key) > 0;
    }
}
