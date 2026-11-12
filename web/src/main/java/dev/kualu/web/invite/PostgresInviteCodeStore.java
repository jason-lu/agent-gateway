package dev.kualu.web.invite;

import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Invite codes in {@code agent.invite_codes} (see {@code V10__email_settings_invite_codes.sql}). */
public class PostgresInviteCodeStore implements InviteCodeStore {

    private static final String COLUMNS =
            "code, email, note, created_by, created_at, expires_at, sent_at, used_by, used_at";

    private final JdbcTemplate jdbc;

    public PostgresInviteCodeStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void insert(InviteCode invite) {
        jdbc.update(
                "insert into agent.invite_codes"
                        + " (code, email, note, created_by, created_at, expires_at, sent_at, used_by, used_at)"
                        + " values (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                invite.code(), invite.email(), invite.note(), invite.createdBy(),
                Timestamp.from(invite.createdAt()), Timestamp.from(invite.expiresAt()),
                invite.sentAt() == null ? null : Timestamp.from(invite.sentAt()),
                invite.usedBy(),
                invite.usedAt() == null ? null : Timestamp.from(invite.usedAt()));
    }

    @Override
    public Optional<InviteCode> find(String code) {
        return jdbc.query(
                "select " + COLUMNS + " from agent.invite_codes where code = ?",
                (rs, rowNum) -> read(rs), code).stream().findFirst();
    }

    @Override
    public List<InviteCode> findAll() {
        return jdbc.query(
                "select " + COLUMNS + " from agent.invite_codes order by created_at desc",
                (rs, rowNum) -> read(rs));
    }

    @Override
    public boolean delete(String code) {
        return jdbc.update("delete from agent.invite_codes where code = ?", code) > 0;
    }

    @Override
    public void markSent(String code, Instant at) {
        jdbc.update("update agent.invite_codes set sent_at = ? where code = ?", Timestamp.from(at), code);
    }

    /** The {@code used_by is null} guard makes the claim atomic under concurrent registration. */
    @Override
    public boolean markUsed(String code, String username, Instant at) {
        return jdbc.update(
                "update agent.invite_codes set used_by = ?, used_at = ? where code = ? and used_by is null",
                username, Timestamp.from(at), code) > 0;
    }

    private static InviteCode read(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new InviteCode(
                rs.getString("code"),
                rs.getString("email"),
                rs.getString("note"),
                rs.getString("created_by"),
                instant(rs.getTimestamp("created_at")),
                instant(rs.getTimestamp("expires_at")),
                instant(rs.getTimestamp("sent_at")),
                rs.getString("used_by"),
                instant(rs.getTimestamp("used_at")));
    }

    private static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
