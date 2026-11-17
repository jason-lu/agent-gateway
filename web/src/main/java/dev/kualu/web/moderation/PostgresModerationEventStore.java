package dev.kualu.web.moderation;

import dev.kualu.llm.moderation.Action;
import dev.kualu.llm.moderation.Category;
import dev.kualu.llm.moderation.Direction;
import dev.kualu.llm.moderation.Severity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Audit rows in {@code agent.moderation_events} (see {@code V11__moderation_events.sql}). */
public class PostgresModerationEventStore implements ModerationEventStore {

    private static final String COLUMNS =
            "id, created_at, owner, session_id, direction, category, severity, action, "
                    + "detector, excerpt, text_sha256, needs_review, reviewed, review_note, review_override";

    private final JdbcTemplate jdbc;

    public PostgresModerationEventStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public ModerationEvent record(ModerationEvent.New event) {
        return jdbc.queryForObject("""
                insert into agent.moderation_events
                    (owner, session_id, direction, category, severity, action,
                     detector, excerpt, text_sha256, needs_review)
                values (?, ?::uuid, ?, ?, ?, ?, ?, ?, ?, ?)
                returning %s
                """.formatted(COLUMNS),
                MAPPER,
                event.owner(), event.sessionId(), event.direction().name(),
                event.category().name(), event.severity().name(), event.action().name(),
                event.detector(), event.excerpt(), event.textSha256(), event.needsReview());
    }

    @Override
    public List<ModerationEvent> list(ModerationQuery query) {
        StringBuilder sql = new StringBuilder("select ").append(COLUMNS)
                .append(" from agent.moderation_events where 1=1");
        List<Object> args = new ArrayList<>();
        if (query.unreviewedOnly()) {
            sql.append(" and not reviewed");
        }
        if (query.needsReviewOnly()) {
            sql.append(" and needs_review");
        }
        if (query.override() != null) {
            sql.append(" and review_override = ?");
            args.add(query.override());
        }
        sql.append(" order by created_at desc, id desc limit ? offset ?");
        args.add(query.limit());
        args.add(query.offset());
        return jdbc.query(sql.toString(), MAPPER, args.toArray());
    }

    @Override
    public Optional<ModerationEvent> find(long id) {
        List<ModerationEvent> rows = jdbc.query(
                "select " + COLUMNS + " from agent.moderation_events where id = ?", MAPPER, id);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    @Override
    public boolean review(long id, String note, String override) {
        int updated = jdbc.update("""
                update agent.moderation_events
                set reviewed = true, review_note = ?, review_override = ?
                where id = ?
                """, note, override, id);
        return updated > 0;
    }

    private static final RowMapper<ModerationEvent> MAPPER = (ResultSet rs, int rowNum) -> new ModerationEvent(
            rs.getLong("id"),
            toInstant(rs.getTimestamp("created_at")),
            rs.getString("owner"),
            rs.getString("session_id"),
            Direction.valueOf(rs.getString("direction")),
            Category.valueOf(rs.getString("category")),
            Severity.valueOf(rs.getString("severity")),
            Action.valueOf(rs.getString("action")),
            rs.getString("detector"),
            rs.getString("excerpt"),
            rs.getString("text_sha256"),
            rs.getBoolean("needs_review"),
            rs.getBoolean("reviewed"),
            rs.getString("review_note"),
            rs.getString("review_override"));

    private static Instant toInstant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
