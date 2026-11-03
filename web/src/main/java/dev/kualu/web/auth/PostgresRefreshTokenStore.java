package dev.kualu.web.auth;

import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Refresh sessions in {@code agent.refresh_tokens} (see {@code V2__refresh_tokens.sql}). */
public class PostgresRefreshTokenStore implements RefreshTokenStore {

    private final JdbcTemplate jdbc;

    public PostgresRefreshTokenStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void insert(Entry entry, String tokenHash) {
        jdbc.update(
                "insert into agent.refresh_tokens (id, owner, token_hash, created_at, last_used_at) values (?, ?, ?, ?, ?)",
                UUID.fromString(entry.id()), entry.owner(), tokenHash,
                Timestamp.from(entry.createdAt()), Timestamp.from(entry.lastUsedAt()));
    }

    @Override
    public Optional<Entry> findByHash(String tokenHash) {
        return first("select id, owner, created_at, last_used_at from agent.refresh_tokens where token_hash = ?",
                tokenHash);
    }

    @Override
    public Optional<Entry> findById(String id) {
        return first("select id, owner, created_at, last_used_at from agent.refresh_tokens where id = ?",
                UUID.fromString(id));
    }

    @Override
    public boolean rotate(String id, String newTokenHash, Instant usedAt) {
        return jdbc.update(
                "update agent.refresh_tokens set token_hash = ?, last_used_at = ? where id = ?",
                newTokenHash, Timestamp.from(usedAt), UUID.fromString(id)) > 0;
    }

    @Override
    public boolean touch(String id, Instant usedAt) {
        return jdbc.update(
                "update agent.refresh_tokens set last_used_at = ? where id = ?",
                Timestamp.from(usedAt), UUID.fromString(id)) > 0;
    }

    @Override
    public void deleteById(String id) {
        jdbc.update("delete from agent.refresh_tokens where id = ?", UUID.fromString(id));
    }

    private Optional<Entry> first(String sql, Object arg) {
        List<Entry> rows = jdbc.query(sql,
                (rs, rowNum) -> new Entry(
                        rs.getObject("id", UUID.class).toString(),
                        rs.getString("owner"),
                        rs.getTimestamp("created_at").toInstant(),
                        rs.getTimestamp("last_used_at").toInstant()),
                arg);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }
}
