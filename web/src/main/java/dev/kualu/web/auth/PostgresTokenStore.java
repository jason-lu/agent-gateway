package dev.kualu.web.auth;

import dev.kualu.mcp.auth.McpTokenStore;
import dev.kualu.mcp.auth.StoredTokens;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** OAuth tokens for MCP servers in {@code agent.mcp_tokens} (see {@code V3__mcp_tokens.sql}). */
public class PostgresTokenStore implements McpTokenStore {

    private final JdbcTemplate jdbc;

    public PostgresTokenStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<StoredTokens> load(String serverName) {
        List<StoredTokens> rows = jdbc.query(
                "select access_token, refresh_token, expires_at from agent.mcp_tokens where server_name = ?",
                (rs, rowNum) -> new StoredTokens(
                        rs.getString("access_token"),
                        rs.getString("refresh_token"),
                        rs.getLong("expires_at")),
                serverName);
        return rows.stream().findFirst();
    }

    @Override
    public void save(String serverName, StoredTokens tokens) {
        jdbc.update(
                "insert into agent.mcp_tokens (server_name, access_token, refresh_token, expires_at, updated_at)"
                        + " values (?, ?, ?, ?, ?)"
                        + " on conflict (server_name) do update set"
                        + " access_token = excluded.access_token,"
                        + " refresh_token = excluded.refresh_token,"
                        + " expires_at = excluded.expires_at,"
                        + " updated_at = excluded.updated_at",
                serverName, tokens.accessToken(), tokens.refreshToken(), tokens.expiresAt(),
                Timestamp.from(Instant.now()));
    }

    @Override
    public void delete(String serverName) {
        jdbc.update("delete from agent.mcp_tokens where server_name = ?", serverName);
    }
}
