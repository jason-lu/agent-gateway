package dev.kualu.web.session;

import tools.jackson.databind.ObjectMapper;
import dev.kualu.llm.ChatMessage;
import dev.kualu.llm.memory.ConversationMemory;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Verifies the SQL this store issues (shape and scoping). Row mapping and real
 * round-trips need a live PostgreSQL — see {@code PostgresPersistenceIntegrationTest}.
 */
class PostgresSessionStoreTest {

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
    void findListAndDelete_areScopedToTheOwner() {
        when(jdbc.query(anyString(), mapper(), eq("sid-1"), eq("ada"))).thenReturn(List.of());
        when(jdbc.query(anyString(), any(RowMapper.class), eq("ada"))).thenReturn(List.of());
        when(jdbc.update(anyString(), eq("sid-1"), eq("ada"))).thenReturn(1);

        store.find("ada", "sid-1");
        store.listByOwner("ada");
        store.delete("ada", "sid-1");

        ArgumentCaptor<String> findSql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).query(findSql.capture(), mapper(), eq("sid-1"), eq("ada"));
        assertTrue(findSql.getValue().contains("owner = ?"), findSql.getValue());

        ArgumentCaptor<String> listSql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).query(listSql.capture(), any(RowMapper.class), eq("ada"));
        assertTrue(listSql.getValue().contains("where s.owner = ?"), listSql.getValue());
        assertTrue(listSql.getValue().contains("order by s.created_at desc"), listSql.getValue());
        assertTrue(listSql.getValue().contains("message_count"), listSql.getValue());
        assertFalse(listSql.getValue().contains("content"),
                "a listing must not fetch the payload: " + listSql.getValue());

        ArgumentCaptor<String> deleteSql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).update(deleteSql.capture(), eq("sid-1"), eq("ada"));
        assertTrue(deleteSql.getValue().contains("owner = ?"), deleteSql.getValue());
    }

    @Test
    void create_writesTheSessionRowWithTheOwner() {
        store.create("ada", "be brief");

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).update(sql.capture(), anyString(), eq("ada"), any());
        assertTrue(sql.getValue().contains("insert into agent.sessions"), sql.getValue());
    }

    @Test
    void create_skipsABlankSystemPrompt() {
        store.create("ada", "   ");

        List<String> updateSqls = updateCalls();
        assertEquals(1, updateSqls.size(), "only the session row insert may run: " + updateSqls);
        assertFalse(updateSqls.get(0).contains("session_messages"),
                "no message row for a blank system prompt: " + updateSqls.get(0));
    }

    @Test
    void appendMessages_readsTheNextSeqAndInsertsAnOwnerScopedSuffix() {
        Session session = new Session("sid-1", "ada", Instant.now(), new ConversationMemory().replaceAll(List.of(
                ChatMessage.system("sys"),
                ChatMessage.user("hi"))));
        when(jdbc.queryForObject(anyString(), eq(Integer.class), eq("sid-1"), eq("ada"))).thenReturn(1);

        store.appendMessages(session);

        ArgumentCaptor<String> seqSql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).queryForObject(seqSql.capture(), eq(Integer.class), eq("sid-1"), eq("ada"));
        assertTrue(seqSql.getValue().contains("max(m.seq)"), seqSql.getValue());
        assertTrue(seqSql.getValue().contains("s.owner = ?"), seqSql.getValue());

        List<String> updateSqls = updateCalls();
        assertEquals(1, updateSqls.size(), updateSqls::toString);
        String insertSql = updateSqls.get(0);
        assertTrue(insertSql.contains("insert into agent.session_messages"), insertSql);
        assertTrue(insertSql.contains("unnest"), insertSql);
        assertTrue(insertSql.contains("coalesce(title, ?)"), insertSql);
        assertTrue(insertSql.contains("where s.id = ?::uuid and s.owner = ?"), insertSql);
    }

    @Test
    void appendMessages_skipsTheInsertWhenNothingIsMissing() {
        Session session = new Session("sid-1", "ada", Instant.now(), new ConversationMemory().replaceAll(List.of(
                ChatMessage.user("hi"))));
        when(jdbc.queryForObject(anyString(), eq(Integer.class), eq("sid-1"), eq("ada"))).thenReturn(1);

        store.appendMessages(session);

        assertEquals(List.of(), updateCalls(), "nothing new, nothing written");
    }

    /** SQL of every {@code update} issued against the mocked template, in order. */
    private List<String> updateCalls() {
        return org.mockito.Mockito.mockingDetails(jdbc).getInvocations().stream()
                .filter(invocation -> "update".equals(invocation.getMethod().getName()))
                .map(invocation -> (String) invocation.getArguments()[0])
                .toList();
    }

    @Test
    void replaceMessages_requiresADataSourceBackedTemplate() {
        // The mock JdbcTemplate exposes no DataSource, so the rewrite path refuses
        // to run un-transactionally. Real rewrites are covered by the integration test.
        Session session = new Session("sid-1", "ada", Instant.now(), new ConversationMemory());
        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalStateException.class, () -> store.replaceMessages(session));
    }
}
