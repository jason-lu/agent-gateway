package dev.kualu.web.memory;

import dev.kualu.llm.memory.MemoryEntry;
import dev.kualu.llm.memory.UserProfile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * {@link MemoryStore} backed by {@code agent.user_profile} + {@code agent.memory_entries}.
 * Memory survives restarts. Every statement is scoped by {@code owner}, so
 * isolation is enforced by SQL rather than by a post-filter; deletes are hard
 * deletes. {@link #clear} removes the profile and the entries in one
 * transaction so a cleared user never resurrects half a memory.
 */
public class PostgresMemoryStore implements MemoryStore {

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    public PostgresMemoryStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.tx = jdbc.getDataSource() == null ? null
                : new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource()));
    }

    private static final String ENTRY_COLUMNS =
            "id, owner, kind, content, source, source_session_id, created_at, updated_at";

    @Override
    public Optional<UserProfile> findProfile(String owner) {
        return jdbc.query(
                "select nickname, gender, city from agent.user_profile where owner = ?",
                (rs, rowNum) -> new UserProfile(
                        rs.getString("nickname"),
                        toGender(rs.getString("gender")),
                        rs.getString("city")),
                owner)
                .stream()
                .findFirst();
    }

    @Override
    public void saveProfile(String owner, UserProfile profile) {
        jdbc.update(
                "insert into agent.user_profile (owner, nickname, gender, city, updated_at) "
                        + "values (?, ?, ?, ?, now()) "
                        + "on conflict (owner) do update set "
                        + "nickname = excluded.nickname, gender = excluded.gender, "
                        + "city = excluded.city, updated_at = now()",
                owner, profile.nickname(), fromGender(profile.gender()), profile.city());
    }

    @Override
    public void deleteProfile(String owner) {
        jdbc.update("delete from agent.user_profile where owner = ?", owner);
    }

    @Override
    public List<MemoryEntry> listEntries(String owner) {
        return jdbc.query(
                "select " + ENTRY_COLUMNS + " from agent.memory_entries where owner = ? order by updated_at desc",
                (rs, rowNum) -> toEntry(rs), owner);
    }

    @Override
    public Optional<MemoryEntry> findEntry(String owner, UUID id) {
        return jdbc.query(
                "select " + ENTRY_COLUMNS + " from agent.memory_entries where id = ?::uuid and owner = ?",
                (rs, rowNum) -> toEntry(rs), id.toString(), owner)
                .stream()
                .findFirst();
    }

    @Override
    public MemoryEntry saveEntry(MemoryEntry entry) {
        Instant now = Timestamps.next();
        if (entry.id() != null) {
            Optional<MemoryEntry> existing = findEntry(entry.owner(), entry.id());
            if (existing.isPresent()) {
                MemoryEntry old = existing.get();
                jdbc.update(
                        "update agent.memory_entries set content = ?, updated_at = ? "
                                + "where id = ?::uuid and owner = ?",
                        entry.content(), Timestamp.from(now), old.id().toString(), entry.owner());
                return new MemoryEntry(old.id(), old.owner(), old.kind(), entry.content(), old.source(),
                        old.sourceSessionId(), old.createdAt(), now);
            }
        }
        // Inserts always take a fresh id (see MemoryStore): a foreign id must not
        // collide with another owner's row, a stale one must not resurrect a
        // hard-deleted entry.
        UUID id = UUID.randomUUID();
        Instant createdAt = entry.createdAt() != null ? entry.createdAt() : now;
        jdbc.update(
                "insert into agent.memory_entries "
                        + "(id, owner, kind, content, source, source_session_id, created_at, updated_at) "
                        + "values (?::uuid, ?, ?, ?, ?, ?, ?, ?)",
                id.toString(), entry.owner(), entry.kind().name().toLowerCase(),
                entry.content(), entry.source().name().toLowerCase(), entry.sourceSessionId(),
                Timestamp.from(createdAt), Timestamp.from(now));
        return new MemoryEntry(id, entry.owner(), entry.kind(), entry.content(), entry.source(),
                entry.sourceSessionId(), createdAt, now);
    }

    @Override
    public boolean deleteEntry(String owner, UUID id) {
        return jdbc.update(
                "delete from agent.memory_entries where id = ?::uuid and owner = ?",
                id.toString(), owner) > 0;
    }

    @Override
    public void clear(String owner) {
        if (tx == null) {
            throw new IllegalStateException("clear requires a DataSource-backed JdbcTemplate");
        }
        tx.executeWithoutResult(status -> {
            jdbc.update("delete from agent.memory_entries where owner = ?", owner);
            jdbc.update("delete from agent.user_profile where owner = ?", owner);
        });
    }

    private static MemoryEntry toEntry(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new MemoryEntry(
                rs.getObject("id", UUID.class),
                rs.getString("owner"),
                MemoryEntry.Kind.valueOf(rs.getString("kind").toUpperCase()),
                rs.getString("content"),
                MemoryEntry.Source.valueOf(rs.getString("source").toUpperCase()),
                rs.getString("source_session_id"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    }

    private static UserProfile.Gender toGender(String value) {
        return value == null ? null : UserProfile.Gender.valueOf(value.toUpperCase());
    }

    private static String fromGender(UserProfile.Gender gender) {
        return gender == null ? null : gender.name().toLowerCase();
    }
}
