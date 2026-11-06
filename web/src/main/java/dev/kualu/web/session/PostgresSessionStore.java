package dev.kualu.web.session;

import dev.kualu.llm.ChatMessage;
import dev.kualu.llm.memory.ConversationMemory;
import dev.kualu.llm.metrics.TurnTimings;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
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
 *
 * <p>SQL fetch and JSON decode are timed separately (see {@link TurnTimings}) so
 * payload-size-driven costs are visible per request; raw rows are read first and
 * decoded after the statement returns.
 */
public class PostgresSessionStore implements SessionStore {

    private final JdbcTemplate jdbc;
    private final ChatMessageCodec codec;

    public PostgresSessionStore(JdbcTemplate jdbc, ChatMessageCodec codec) {
        this.jdbc = jdbc;
        this.codec = codec;
    }

    /** Raw row as read from SQL; decoded after the statement returns so the two costs split. */
    private record RawSession(String id, String owner, Instant createdAt, String messagesJson) {
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
        long sqlStart = System.nanoTime();
        List<RawSession> rows = jdbc.query(
                "select id, owner, created_at, messages from agent.sessions where id = ?::uuid and owner = ?",
                (rs, rowNum) -> toRaw(rs),
                sessionId, owner);
        long sqlMs = elapsedMs(sqlStart);
        if (rows.isEmpty()) {
            TurnTimings.recordLoad(sqlMs, 0, 0, 0);
            return Optional.empty();
        }
        RawSession raw = rows.get(0);
        long decodeStart = System.nanoTime();
        Session session = toSession(raw);
        TurnTimings.recordLoad(sqlMs, elapsedMs(decodeStart), raw.messagesJson().length(),
                session.messageCount());
        return Optional.of(session);
    }

    @Override
    public List<SessionSummary> listByOwner(String owner) {
        // Never fetch the payload for a listing: message_count comes from the
        // jsonb length, so first access does not transfer the whole history.
        long sqlStart = System.nanoTime();
        List<SessionSummary> summaries = jdbc.query(
                "select id, owner, created_at, jsonb_array_length(messages) as message_count from agent.sessions "
                        + "where owner = ? order by created_at desc",
                (rs, rowNum) -> new SessionSummary(
                        rs.getString("id"),
                        rs.getString("owner"),
                        rs.getTimestamp("created_at").toInstant(),
                        rs.getInt("message_count")),
                owner);
        TurnTimings.recordList(elapsedMs(sqlStart), 0, 0);
        return summaries;
    }

    @Override
    public boolean delete(String owner, String sessionId) {
        if (jdbc.update("delete from agent.sessions where id = ?::uuid and owner = ?", sessionId, owner) > 0) {
            jdbc.update("delete from agent.session_images where session_id = ?::uuid", sessionId);
            return true;
        }
        return false;
    }

    @Override
    public Session update(Session session) {
        // Scoped by owner and id, so a session deleted mid-turn is not resurrected:
        // the UPDATE simply matches zero rows.
        long encodeStart = System.nanoTime();
        String json = codec.encode(externalizeImages(session));
        long encodeMs = elapsedMs(encodeStart);
        long writeStart = System.nanoTime();
        jdbc.update(
                "update agent.sessions set messages = ?::jsonb where id = ?::uuid and owner = ?",
                json, session.id(), session.owner());
        TurnTimings.recordPersist(encodeMs, elapsedMs(writeStart), json.length());
        return session;
    }

    @Override
    public Optional<String> findImageContent(String sessionId, String imageId) {
        return jdbc.query(
                "select content from agent.session_images where session_id = ?::uuid and image_id = ?",
                (rs, rowNum) -> rs.getString("content"),
                sessionId, imageId)
                .stream()
                .findFirst();
    }

    /**
     * Replaces inline {@code data:} images with {@link ImageRef}s and stores the
     * content once per session (content-addressed, so repeat persists are no-ops).
     * The in-memory messages are left untouched — live turns keep their data URIs.
     */
    private List<ChatMessage> externalizeImages(Session session) {
        List<ChatMessage> all = session.memory().messages();
        List<ChatMessage> out = new ArrayList<>(all.size());
        for (ChatMessage message : all) {
            List<String> images = message.images();
            if (images == null || images.isEmpty()) {
                out.add(message);
                continue;
            }
            List<String> replaced = new ArrayList<>(images.size());
            for (String image : images) {
                if (image.startsWith("data:")) {
                    String imageId = sha256Hex(image);
                    jdbc.update(
                            "insert into agent.session_images (session_id, image_id, content) "
                                    + "values (?::uuid, ?, ?) on conflict do nothing",
                            session.id(), imageId, image);
                    replaced.add(ImageRef.format(session.id(), imageId));
                } else {
                    replaced.add(image);
                }
            }
            out.add(new ChatMessage(message.role(), message.content(), message.toolCallId(),
                    message.toolCalls(), replaced));
        }
        return out;
    }

    private static String sha256Hex(String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    private static RawSession toRaw(ResultSet rs) throws SQLException {
        return new RawSession(
                rs.getString("id"),
                rs.getString("owner"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getString("messages"));
    }

    private Session toSession(RawSession raw) {
        ConversationMemory memory = new ConversationMemory().replaceAll(codec.decode(raw.messagesJson()));
        return new Session(raw.id(), raw.owner(), raw.createdAt(), memory);
    }

    private static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }
}
