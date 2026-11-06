package dev.kualu.web.session;

import dev.kualu.llm.ChatMessage;
import dev.kualu.llm.memory.ConversationMemory;
import dev.kualu.llm.metrics.TurnTimings;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

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
 * {@link SessionStore} backed by {@code agent.sessions} + {@code agent.session_messages}.
 * Sessions survive restarts; the conversation is one row per message ordered by
 * {@code seq} (including any compression summary).
 *
 * <p>Every statement is scoped by {@code owner}, so isolation is enforced by SQL
 * rather than by a post-filter.
 *
 * <p>Turn persistence is append-only: {@link #appendMessages} writes only the
 * rows the store has not seen (driven by {@code max(seq)}), so a long history
 * with fat tool results is not rewritten on every turn. {@link #replaceMessages}
 * is the full-rewrite path for memory compression, which reshuffles the fold
 * marker mid-history and therefore invalidates positional appends.
 *
 * <p>SQL fetch and row decode are timed separately (see {@link TurnTimings}) so
 * payload-size-driven costs are visible per request; raw rows are read first and
 * decoded after the statement returns.
 */
public class PostgresSessionStore implements SessionStore {

    private final JdbcTemplate jdbc;
    private final ChatMessageCodec codec;
    private final TransactionTemplate tx;

    public PostgresSessionStore(JdbcTemplate jdbc, ChatMessageCodec codec) {
        this.jdbc = jdbc;
        this.codec = codec;
        // replaceMessages must delete and re-insert same primary keys, which is
        // only safe inside one transaction.
        this.tx = jdbc.getDataSource() == null ? null
                : new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource()));
    }

    /** Raw rows as read from SQL; decoded after the statement returns so the two costs split. */
    private record RawSession(String id, String owner, Instant createdAt, String title) {
    }

    private record RawMessage(int seq, String role, String content, String toolCallId,
                              String toolCalls, String images) {
    }

    @Override
    public Session create(String owner, String systemPrompt) {
        String id = UUID.randomUUID().toString();
        Instant createdAt = Instant.now();
        jdbc.update(
                "insert into agent.sessions (id, owner, created_at, title) values (?::uuid, ?, ?, null)",
                id, owner, Timestamp.from(createdAt));
        List<ChatMessage> messages = new ArrayList<>();
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            messages.add(ChatMessage.system(systemPrompt));
        }
        if (!messages.isEmpty()) {
            insertRows(id, owner, messages, 0, null);
        }
        return new Session(id, owner, createdAt, null, new ConversationMemory().replaceAll(messages));
    }

    @Override
    public Optional<Session> find(String owner, String sessionId) {
        long sqlStart = System.nanoTime();
        List<RawSession> rows = jdbc.query(
                "select id, owner, created_at, title from agent.sessions where id = ?::uuid and owner = ?",
                (rs, rowNum) -> toRaw(rs),
                sessionId, owner);
        long sqlMs = elapsedMs(sqlStart);
        if (rows.isEmpty()) {
            TurnTimings.recordLoad(sqlMs, 0, 0, 0);
            return Optional.empty();
        }
        RawSession raw = rows.get(0);
        long decodeStart = System.nanoTime();
        List<RawMessage> messages = jdbc.query(
                "select seq, role, content, tool_call_id, tool_calls, images "
                        + "from agent.session_messages where session_id = ?::uuid order by seq",
                (rs, rowNum) -> new RawMessage(
                        rs.getInt("seq"),
                        rs.getString("role"),
                        rs.getString("content"),
                        rs.getString("tool_call_id"),
                        rs.getString("tool_calls"),
                        rs.getString("images")),
                sessionId);
        List<ChatMessage> decoded = new ArrayList<>(messages.size());
        int chars = 0;
        for (RawMessage message : messages) {
            decoded.add(codec.decode(new ChatMessageCodec.Encoded(
                    message.role(), message.content(), message.toolCallId(),
                    message.toolCalls(), message.images())));
            chars += message.content().length();
        }
        Session session = new Session(raw.id(), raw.owner(), raw.createdAt(), raw.title(),
                new ConversationMemory().replaceAll(decoded));
        TurnTimings.recordLoad(sqlMs, elapsedMs(decodeStart), chars, session.messageCount());
        return Optional.of(session);
    }

    @Override
    public List<SessionSummary> listByOwner(String owner) {
        // Never fetch the payload for a listing: message_count comes from the
        // row count, so first access does not transfer any message content.
        long sqlStart = System.nanoTime();
        List<SessionSummary> summaries = jdbc.query(
                "select s.id, s.owner, s.created_at, s.title, "
                        + "(select count(*) from agent.session_messages m where m.session_id = s.id) as message_count "
                        + "from agent.sessions s where s.owner = ? order by s.created_at desc",
                (rs, rowNum) -> new SessionSummary(
                        rs.getString("id"),
                        rs.getString("owner"),
                        rs.getTimestamp("created_at").toInstant(),
                        rs.getString("title"),
                        rs.getInt("message_count")),
                owner);
        TurnTimings.recordList(elapsedMs(sqlStart), 0, 0);
        return summaries;
    }

    @Override
    public boolean delete(String owner, String sessionId) {
        // session_messages hangs off a cascade; images are cleaned up explicitly.
        if (jdbc.update("delete from agent.sessions where id = ?::uuid and owner = ?", sessionId, owner) > 0) {
            jdbc.update("delete from agent.session_images where session_id = ?::uuid", sessionId);
            return true;
        }
        return false;
    }

    @Override
    public void appendMessages(Session session) {
        List<ChatMessage> all = session.memory().messages();
        insertRows(session.id(), session.owner(), all, nextSeq(session), session.title());
    }

    @Override
    public void replaceMessages(Session session) {
        if (tx == null) {
            throw new IllegalStateException("replaceMessages requires a DataSource-backed JdbcTemplate");
        }
        List<ChatMessage> all = externalizeImages(session, session.memory().messages());
        String title = session.title();
        long writeStart = System.nanoTime();
        tx.executeWithoutResult(status -> {
            jdbc.update(
                    "delete from agent.session_messages where session_id = "
                            + "(select id from agent.sessions where id = ?::uuid and owner = ?)",
                    session.id(), session.owner());
            insertRows(session.id(), session.owner(), all, 0, title);
        });
        int chars = all.stream().mapToInt(m -> m.content().length()).sum();
        TurnTimings.recordPersist(0, elapsedMs(writeStart), chars);
    }

    /** First free {@code seq} = one past the highest stored row; gap-filling on retry. */
    private int nextSeq(Session session) {
        Integer next = jdbc.queryForObject(
                "select coalesce(max(m.seq), -1) + 1 from agent.sessions s "
                        + "left join agent.session_messages m on m.session_id = s.id "
                        + "where s.id = ?::uuid and s.owner = ?",
                Integer.class, session.id(), session.owner());
        return next == null ? 0 : next;
    }

    /**
     * Writes messages {@code [fromIndex, end)} as rows. Owner-scoped: the insert
     * joins {@code agent.sessions}, so a session deleted mid-turn makes this a
     * silent no-op instead of resurrecting rows. The title is fill-once
     * ({@code coalesce(?, title)}) — a derived title is never overwritten.
     * Runs even for an empty tail when a title is pending.
     */
    private void insertRows(String sessionId, String owner, List<ChatMessage> messages,
                            int fromIndex, String title) {
        List<ChatMessage> tail = fromIndex >= messages.size()
                ? List.of()
                : externalizeImages(messages, fromIndex, sessionId);
        if (tail.isEmpty() && title == null) {
            return;
        }
        int size = tail.size();
        int[] seqs = new int[size];
        String[] roles = new String[size];
        String[] contents = new String[size];
        String[] toolCallIds = new String[size];
        String[] toolCalls = new String[size];
        String[] images = new String[size];
        long encodeStart = System.nanoTime();
        int chars = 0;
        for (int i = 0; i < size; i++) {
            ChatMessageCodec.Encoded encoded = codec.encode(tail.get(i));
            seqs[i] = fromIndex + i;
            roles[i] = encoded.role();
            contents[i] = encoded.content();
            toolCallIds[i] = encoded.toolCallId();
            toolCalls[i] = encoded.toolCalls();
            images[i] = encoded.images();
            chars += encoded.content().length();
        }
        long encodeMs = elapsedMs(encodeStart);
        long writeStart = System.nanoTime();
        jdbc.update(
                        "with titled as ("
                        + "update agent.sessions set title = coalesce(title, ?) "
                        + "where id = ?::uuid and owner = ?"
                        + ") "
                        + "insert into agent.session_messages "
                        + "(session_id, seq, role, content, tool_call_id, tool_calls, images) "
                        + "select s.id, u.seq, u.role, u.content, u.tool_call_id, "
                        + "u.tool_calls::jsonb, u.images::jsonb "
                        + "from agent.sessions s "
                        + "cross join unnest(?::int[], ?::text[], ?::text[], ?::text[], ?::text[], ?::text[]) "
                        + "as u(seq, role, content, tool_call_id, tool_calls, images) "
                        + "where s.id = ?::uuid and s.owner = ?",
                new Object[]{title, sessionId, owner, seqs, roles, contents, toolCallIds, toolCalls, images,
                        sessionId, owner});
        TurnTimings.recordPersist(encodeMs, elapsedMs(writeStart), chars);
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

    private List<ChatMessage> externalizeImages(Session session, List<ChatMessage> all) {
        return externalizeImages(all, 0, session.id());
    }

    /**
     * Replaces inline {@code data:} images with {@link ImageRef}s and stores the
     * content once per session (content-addressed, so repeat persists are no-ops).
     * The in-memory messages are left untouched — live turns keep their data URIs.
     */
    private List<ChatMessage> externalizeImages(List<ChatMessage> messages, int fromIndex, String sessionId) {
        List<ChatMessage> out = new ArrayList<>(messages.size() - fromIndex);
        for (ChatMessage message : messages.subList(fromIndex, messages.size())) {
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
                            sessionId, imageId, image);
                    replaced.add(ImageRef.format(sessionId, imageId));
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
                rs.getString("title"));
    }

    private static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }
}
