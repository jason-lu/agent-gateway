package dev.kualu.web.auth;

import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Reset tokens in {@code agent.password_reset_tokens} (see {@code V14__user_email_password_reset.sql}). */
public class PostgresPasswordResetTokenStore implements PasswordResetTokenStore {

    private final JdbcTemplate jdbc;

    public PostgresPasswordResetTokenStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void insert(Token token) {
        jdbc.update(
                "insert into agent.password_reset_tokens (token_hash, username, created_at, expires_at, used_at)"
                        + " values (?, ?, ?, ?, ?)",
                token.tokenHash(), token.username(),
                Timestamp.from(token.createdAt()), Timestamp.from(token.expiresAt()),
                token.usedAt() == null ? null : Timestamp.from(token.usedAt()));
    }

    @Override
    public Optional<Token> find(String tokenHash) {
        List<Token> rows = jdbc.query(
                "select token_hash, username, created_at, expires_at, used_at"
                        + " from agent.password_reset_tokens where token_hash = ?",
                (rs, rowNum) -> new Token(
                        rs.getString("token_hash"),
                        rs.getString("username"),
                        rs.getTimestamp("created_at").toInstant(),
                        rs.getTimestamp("expires_at").toInstant(),
                        rs.getTimestamp("used_at") == null ? null : rs.getTimestamp("used_at").toInstant()),
                tokenHash);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    @Override
    public boolean markUsed(String tokenHash, Instant at) {
        return jdbc.update(
                "update agent.password_reset_tokens set used_at = ?"
                        + " where token_hash = ? and used_at is null",
                Timestamp.from(at), tokenHash) > 0;
    }

    @Override
    public void deleteAllFor(String username) {
        jdbc.update("delete from agent.password_reset_tokens where username = ?", username);
    }
}
