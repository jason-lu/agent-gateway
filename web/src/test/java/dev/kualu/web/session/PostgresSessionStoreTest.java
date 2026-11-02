package dev.kualu.web.session;

import tools.jackson.databind.ObjectMapper;
import dev.kualu.llm.ChatMessage;
import dev.kualu.llm.ConversationMemory;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Verifies the SQL this store issues. Row mapping and real round-trips need a live
 * PostgreSQL — see the spec's T7 for the integration suite.
 */
class PostgresSessionStoreTest {

    private static final String SUMMARY = "【历史对话摘要】user is building a parser";

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final PostgresSessionStore store =
            new PostgresSessionStore(jdbc, new ChatMessageCodec(new ObjectMapper()));

    /** Disambiguates {@code JdbcTemplate.query}'s many overloads. */
    @SuppressWarnings("unchecked")
    private static RowMapper<Session> mapper() {
        return (RowMapper<Session>) any(RowMapper.class);
    }

    /** Everything the store reads or writes must be scoped to a single owner. */
    @Test
    void everyStatement_isScopedToTheOwner() {
        // SQL placeholders run (id, owner), so the bind order is sessionId first.
        when(jdbc.query(anyString(), mapper(), eq("sid-1"), eq("ada"))).thenReturn(List.of());
        when(jdbc.query(anyString(), mapper(), eq("ada"))).thenReturn(List.of());
        when(jdbc.update(anyString(), eq("sid-1"), eq("ada"))).thenReturn(1);
        when(jdbc.update(anyString(), any(), eq("sid-1"), eq("ada"))).thenReturn(1);

        store.find("ada", "sid-1");
        store.listByOwner("ada");
        store.delete("ada", "sid-1");
        store.update(new Session("sid-1", "ada", Instant.now(), new ConversationMemory()));

        ArgumentCaptor<String> findSql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).query(findSql.capture(), mapper(), eq("sid-1"), eq("ada"));
        assertTrue(findSql.getValue().contains("owner = ?"), findSql.getValue());

        ArgumentCaptor<String> listSql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).query(listSql.capture(), mapper(), eq("ada"));
        assertTrue(listSql.getValue().contains("where owner = ?"), listSql.getValue());
        assertTrue(listSql.getValue().contains("order by created_at desc"), listSql.getValue());

        ArgumentCaptor<String> deleteSql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).update(deleteSql.capture(), eq("sid-1"), eq("ada"));
        assertTrue(deleteSql.getValue().contains("owner = ?"), deleteSql.getValue());

        ArgumentCaptor<String> updateSql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).update(updateSql.capture(), any(), eq("sid-1"), eq("ada"));
        assertTrue(updateSql.getValue().contains("where id = ?::uuid and owner = ?"), updateSql.getValue());
    }

    @Test
    void create_writesTheSystemPromptAsTheFirstMessage() {
        store.create("ada", "be brief");

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object> values = ArgumentCaptor.forClass(Object.class);
        verify(jdbc).update(sql.capture(), values.capture(), values.capture(), values.capture(), values.capture());
        assertTrue(sql.getValue().contains("insert into agent.sessions"), sql.getValue());

        List<Object> args = values.getAllValues();
        assertEquals("ada", args.get(1));
        assertTrue(args.get(3).toString().contains("be brief"),
                "the system prompt must land in the messages array: " + args.get(3));
    }

    @Test
    void create_skipsABlankSystemPrompt() {
        store.create("ada", "   ");

        ArgumentCaptor<Object> values = ArgumentCaptor.forClass(Object.class);
        verify(jdbc).update(anyString(), values.capture(), values.capture(), values.capture(), values.capture());
        assertEquals("[]", values.getAllValues().get(3));
    }

    @Test
    void update_persistsTheCompressedSummaryVerbatim() {
        Session session = new Session("sid-1", "ada", Instant.now(),
                new ConversationMemory().replaceAll(List.of(
                        ChatMessage.system("sys"),
                        ChatMessage.system(SUMMARY),
                        ChatMessage.user("go on"))));
        when(jdbc.update(anyString(), any(), any(), any())).thenReturn(1);

        store.update(session);

        ArgumentCaptor<Object> messages = ArgumentCaptor.forClass(Object.class);
        verify(jdbc).update(anyString(), messages.capture(), any(), any());
        assertTrue(messages.getValue().toString().contains(SUMMARY),
                "the summary must round-trip untouched: " + messages.getValue());
    }
}
