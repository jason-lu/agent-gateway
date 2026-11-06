package dev.kualu.web.mcp;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** User MCP servers in {@code agent.user_mcp_servers} (see {@code V7__user_mcp_servers.sql}). */
public class PostgresUserMcpServerStore implements UserMcpServerStore {

    private static final TypeReference<Map<String, String>> MAP_TYPE = new TypeReference<>() {
    };
    private static final TypeReference<List<String>> LIST_TYPE = new TypeReference<>() {
    };

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public PostgresUserMcpServerStore(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @Override
    public List<UserMcpServer> listByOwner(String owner) {
        return jdbc.query(
                "select name, transport, command, args, env, url, headers, enabled"
                        + " from agent.user_mcp_servers where owner = ? order by name",
                (rs, rowNum) -> new UserMcpServer(
                        rs.getString("name"),
                        rs.getString("transport"),
                        rs.getString("command"),
                        readList(rs.getString("args")),
                        readMap(rs.getString("env")),
                        rs.getString("url"),
                        readMap(rs.getString("headers")),
                        rs.getBoolean("enabled")),
                owner);
    }

    @Override
    public Optional<UserMcpServer> find(String owner, String name) {
        return jdbc.query(
                "select name, transport, command, args, env, url, headers, enabled"
                        + " from agent.user_mcp_servers where owner = ? and name = ?",
                (rs, rowNum) -> new UserMcpServer(
                        rs.getString("name"),
                        rs.getString("transport"),
                        rs.getString("command"),
                        readList(rs.getString("args")),
                        readMap(rs.getString("env")),
                        rs.getString("url"),
                        readMap(rs.getString("headers")),
                        rs.getBoolean("enabled")),
                owner, name).stream().findFirst();
    }

    @Override
    public void save(String owner, UserMcpServer server) {
        jdbc.update(
                "insert into agent.user_mcp_servers"
                        + " (owner, name, transport, command, args, env, url, headers, enabled, updated_at)"
                        + " values (?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?::jsonb, ?, ?)"
                        + " on conflict (owner, name) do update set"
                        + " transport = excluded.transport,"
                        + " command = excluded.command,"
                        + " args = excluded.args,"
                        + " env = excluded.env,"
                        + " url = excluded.url,"
                        + " headers = excluded.headers,"
                        + " enabled = excluded.enabled,"
                        + " updated_at = excluded.updated_at",
                owner, server.name(), server.transport(), server.command(),
                writeList(server.args()), writeMap(server.env()), server.url(), writeMap(server.headers()),
                server.enabled(), Timestamp.from(Instant.now()));
    }

    @Override
    public boolean delete(String owner, String name) {
        return jdbc.update("delete from agent.user_mcp_servers where owner = ? and name = ?", owner, name) > 0;
    }

    private Map<String, String> readMap(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        Map<String, String> map = mapper.readValue(json, MAP_TYPE);
        return new LinkedHashMap<>(map);
    }

    private List<String> readList(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        return List.copyOf(mapper.readValue(json, LIST_TYPE));
    }

    private String writeMap(Map<String, String> map) {
        return mapper.writeValueAsString(map == null ? Map.of() : map);
    }

    private String writeList(List<String> list) {
        return mapper.writeValueAsString(list == null ? List.of() : list);
    }
}
