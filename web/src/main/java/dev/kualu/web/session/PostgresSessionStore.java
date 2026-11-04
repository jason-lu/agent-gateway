package dev.kualu.web.session;

import dev.kualu.llm.ChatMessage;
import dev.kualu.llm.memory.ConversationMemory;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * {@link SessionStore} backed by {@code agent.sessions}. Sessions survive restarts;
 * the conversation (including any compression summary) lives in the {@code messages}
 * jsonb column as a single ordered array.
 *
 * <p>Every statement is scoped by {@code owner}, so isolation is enforced by SQL
 * rather than by a post-filter.
 */
public class PostgresSessionStore implements SessionStore {

    private final JdbcTemplate jdbc;
    private final ChatMessageCodec codec;

    public PostgresSessionStore(JdbcTemplate jdbc, ChatMessageCodec codec) {
        this.jdbc = jdbc;
        this.codec = codec;
    }

    @Override
    public Session create(String owner, String systemPrompt) {
        List<ChatMessage> messages = new ArrayList<>();
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            messages.add(ChatMessage.system(systemPrompt));
        }
        String id = UUID.randomUUID().toString();
        Instant createdAt = Instant.now();
        jdbc.update(
                "insert into agent.sessions (id, owner, created_at, messages) values (?::uuid, ?, ?, ?::jsonb)",
                id, owner, Timestamp.from(createdAt), codec.encode(messages));
        return new Session(id, owner, createdAt, new ConversationMemory().replaceAll(messages));
    }

    @Override
    public Optional<Session> find(String owner, String sessionId) {
        return jdbc.query(
                "select id, owner, created_at, messages from agent.sessions where id = ?::uuid and owner = ?",
                (rs, rowNum) -> toSession(rs),
                sessionId, owner)
                .stream()
                .findFirst();
    }

    @Override
    public List<Session> listByOwner(String owner) {
        return jdbc.query(
                "select id, owner, created_at, messages from agent.sessions "
                        + "where owner = ? order by created_at desc",
                (rs, rowNum) -> toSession(rs),
                owner);
    }

    @Override
    public boolean delete(String owner, String sessionId) {
        return jdbc.update("delete from agent.sessions where id = ?::uuid and owner = ?", sessionId, owner) > 0;
    }

    @Override
    public Session update(Session session) {
        // Scoped by owner and id, so a session deleted mid-turn is not resurrected:
        // the UPDATE simply matches zero rows.
        jdbc.update(
                "update agent.sessions set messages = ?::jsonb where id = ?::uuid and owner = ?",
                codec.encode(session.memory().messages()), session.id(), session.owner());
        return session;
    }

    private Session toSession(ResultSet rs) throws SQLException {
        String id = rs.getString("id");
        String owner = rs.getString("owner");
        Instant createdAt = rs.getTimestamp("created_at").toInstant();
        ConversationMemory memory = new ConversationMemory().replaceAll(codec.decode(rs.getString("messages")));
        return new Session(id, owner, createdAt, memory);
    }
}
