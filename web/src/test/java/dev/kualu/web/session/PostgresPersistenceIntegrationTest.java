package dev.kualu.web.session;

import tools.jackson.databind.ObjectMapper;
import dev.kualu.llm.ChatMessage;
import dev.kualu.llm.ToolCall;
import dev.kualu.llm.memory.MemoryCompressor;
import dev.kualu.web.SharedPostgres;
import dev.kualu.web.auth.JdbcUserDetailsService;
import dev.kualu.web.auth.JdbcUserSeeder;
import dev.kualu.web.config.SecurityProperties;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs the real migration and the persistence layer against a real PostgreSQL
 * (zonky embedded — no Docker needed). The schema is migrated once per class;
 * each test starts from truncated tables seeded with the two accounts the
 * fixtures use (the legacy-upgrade test rebuilds its own schema).
 */
class PostgresPersistenceIntegrationTest {

    private static final String SUMMARY = MemoryCompressor.SUMMARY_PREFIX + "user is building a parser";

    private static EmbeddedPostgres postgres;
    private static JdbcTemplate jdbc;
    private static PostgresSessionStore store;
    private static PasswordEncoder encoder;

    @BeforeAll
    static void startDatabase() {
        postgres = SharedPostgres.get();
        jdbc = new JdbcTemplate(postgres.getPostgresDatabase());
        store = new PostgresSessionStore(jdbc, new ChatMessageCodec(new ObjectMapper()));
        encoder = PasswordEncoderFactories.createDelegatingPasswordEncoder();
        migrateToLatest();
        seedUsers();
    }

    /** Data-only reset: cascades to sessions, messages, images and the other user-scoped tables. */
    @BeforeEach
    void resetData() {
        jdbc.execute("truncate agent.users cascade");
        seedUsers();
    }

    private static void seedUsers() {
        new JdbcUserSeeder(jdbc, encoder).seed(List.of(
                new SecurityProperties.User("ada", "ada-pw", List.of("USER")),
                new SecurityProperties.User("bob", "bob-pw", List.of("USER"))));
    }

    private static void migrateToLatest() {
        Flyway.configure()
                .dataSource(postgres.getPostgresDatabase())
                .schemas("agent")
                .defaultSchema("agent")
                .locations("classpath:db/migration")
                .load()
                .migrate();
    }

    // ---- users ----------------------------------------------------------

    @Test
    void seededUsers_canAuthenticate() {
        var userDetails = new JdbcUserDetailsService(jdbc).loadUserByUsername("ada");

        assertEquals("ada", userDetails.getUsername());
        assertTrue(encoder.matches("ada-pw", userDetails.getPassword()));
        assertTrue(userDetails.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_USER")));
    }

    @Test
    void multiRoleAccounts_mapEveryRole() {
        new JdbcUserSeeder(jdbc, encoder).seed(List.of(
                new SecurityProperties.User("boss", "boss-pw", List.of("USER", "ADMIN"))));

        var userDetails = new JdbcUserDetailsService(jdbc).loadUserByUsername("boss");
        var roles = userDetails.getAuthorities().stream().map(Object::toString).toList();

        assertTrue(roles.contains("ROLE_USER"), roles::toString);
        assertTrue(roles.contains("ROLE_ADMIN"), roles::toString);
    }

    @Test
    void seed_doesNotOverwriteAnAccountChangedInTheDatabase() {
        jdbc.update("update agent.users set password_hash = ? where username = ?", "hand-edited", "ada");

        int created = new JdbcUserSeeder(jdbc, encoder).seed(List.of(
                new SecurityProperties.User("ada", "new-pw-from-config", List.of("ADMIN"))));

        assertEquals(0, created);
        assertEquals("hand-edited",
                jdbc.queryForObject("select password_hash from agent.users where username = ?", String.class, "ada"));
    }

    // ---- sessions -------------------------------------------------------

    @Test
    void crud_roundTripsThroughTheDatabase() {
        Session created = store.create("ada", "be brief");
        created.memory().addUser("hi").addAssistant("hello");
        store.appendMessages(created);

        Session found = store.find("ada", created.id()).orElseThrow();
        assertEquals(created.id(), found.id());
        assertEquals("ada", found.owner());
        assertEquals(3, found.messageCount());
        assertEquals("be brief", found.memory().messages().get(0).content());

        assertEquals(1, store.listByOwner("ada").size());
        assertTrue(store.delete("ada", created.id()));
        assertFalse(store.find("ada", created.id()).isPresent());
    }

    @Test
    void sessionsAreIsolatedByOwner() {
        Session ada = store.create("ada", null);
        store.create("bob", null);

        assertTrue(store.find("bob", ada.id()).isEmpty(), "bob must not read ada's session");
        assertFalse(store.delete("bob", ada.id()), "delete must be owner-scoped too");
        assertEquals(1, store.listByOwner("ada").size());
        assertEquals(1, store.listByOwner("bob").size());
    }

    @Test
    void appendMessages_writesOnlyTheNewRowsInOrder() {
        Session created = store.create("ada", null);
        created.memory().addUser("first question");
        store.appendMessages(created);
        created.memory().addAssistant("first reply").addUser("second question");
        store.appendMessages(created);

        List<String> contents = jdbc.queryForList(
                "select content from agent.session_messages where session_id = ?::uuid order by seq",
                String.class, created.id());
        assertEquals(List.of("first question", "first reply", "second question"), contents);

        List<Integer> seqs = jdbc.queryForList(
                "select seq from agent.session_messages where session_id = ?::uuid order by seq",
                Integer.class, created.id());
        assertEquals(List.of(0, 1, 2), seqs);
    }

    @Test
    void appendMessages_selfHealsAGapLeftByAFailedWrite() {
        Session created = store.create("ada", "sys");
        created.memory().addUser("u1").addAssistant("a1");
        store.appendMessages(created);
        // Simulate a turn whose rows never landed (e.g. a failed persist).
        jdbc.update("delete from agent.session_messages where session_id = ?::uuid and seq = 2",
                created.id());
        created.memory().addUser("u2");
        store.appendMessages(created);

        List<String> contents = jdbc.queryForList(
                "select content from agent.session_messages where session_id = ?::uuid order by seq",
                String.class, created.id());
        assertEquals(List.of("sys", "u1", "a1", "u2"), contents,
                "the missing suffix must be re-written, not skipped over");
    }

    @Test
    void replaceMessages_rewritesTheWholeList() {
        Session created = store.create("ada", "sys");
        created.memory().replaceAll(List.of(
                ChatMessage.system("sys"),
                ChatMessage.system(SUMMARY),
                ChatMessage.user("go on"),
                ChatMessage.assistant("sure")));
        store.replaceMessages(created);

        Session reloaded = store.find("ada", created.id()).orElseThrow();
        assertEquals(4, reloaded.messageCount());
        assertEquals("sys", reloaded.memory().messages().get(0).content());
        assertEquals(SUMMARY, reloaded.memory().messages().get(1).content());
    }

    @Test
    void imagesAreExternalizedAndResolved() {
        Session created = store.create("ada", null);
        created.memory().replaceAll(List.of(
                ChatMessage.user("look at this", List.of("data:image/png;base64,AAAA"))));
        store.replaceMessages(created);

        String json = jdbc.queryForObject(
                "select images::text from agent.session_messages where session_id = ?::uuid",
                String.class, created.id());
        assertTrue(json.contains("ref:" + created.id() + "/"), json);
        assertFalse(json.contains("AAAA"), "pixels must not live in the row: " + json);

        Session reloaded = store.find("ada", created.id()).orElseThrow();
        String ref = reloaded.memory().messages().get(0).images().get(0);
        assertTrue(ImageRef.isRef(ref), ref);
        assertEquals("data:image/png;base64,AAAA",
                store.findImageContent(created.id(), ImageRef.imageIdOf(ref)).orElseThrow());

        // Re-persisting the same image must not duplicate the row.
        store.replaceMessages(reloaded);
        Integer rows = jdbc.queryForObject(
                "select count(*) from agent.session_images where session_id = ?::uuid",
                Integer.class, created.id());
        assertEquals(1, rows);

        store.delete("ada", created.id());
        Integer afterDelete = jdbc.queryForObject(
                "select count(*) from agent.session_images where session_id = ?::uuid",
                Integer.class, created.id());
        assertEquals(0, afterDelete);
    }

    @Test
    void title_isFilledOnceAndCarriedIntoListings() {
        Session created = store.create("ada", null);
        created.memory().addUser("help me with the parser");
        created.setTitle("help me with the parser");
        store.appendMessages(created);

        created.setTitle("a later, worse title");
        created.memory().addAssistant("sure");
        store.appendMessages(created);

        Session reloaded = store.find("ada", created.id()).orElseThrow();
        assertEquals("help me with the parser", reloaded.title(), "the title is fill-once");

        var summaries = store.listByOwner("ada");
        assertEquals("help me with the parser", summaries.get(0).title());
        assertEquals(2, summaries.get(0).messageCount());
    }

    @Test
    void deletedSession_appendMessagesStaysANoOp() {
        Session created = store.create("ada", null);
        created.memory().addUser("hi");
        assertTrue(store.delete("ada", created.id()));

        store.appendMessages(created);

        assertFalse(store.find("ada", created.id()).isPresent());
        assertEquals(0, (int) jdbc.queryForObject(
                "select count(*) from agent.session_messages where session_id = ?::uuid",
                Integer.class, created.id()));
    }

    @Test
    void historySurvivesRestart() {
        Session created = store.create("ada", null);
        created.memory().addUser("remember me").addAssistant("I will");
        store.appendMessages(created);

        // A brand new store = the process restarted; the row must still be readable.
        PostgresSessionStore afterRestart = new PostgresSessionStore(jdbc, new ChatMessageCodec(new ObjectMapper()));
        Session reloaded = afterRestart.find("ada", created.id()).orElseThrow();

        assertEquals("remember me", reloaded.memory().messages().get(0).content());
        assertEquals("I will", reloaded.memory().messages().get(1).content());
    }

    @Test
    void blankSystemPromptIsNotStored() {
        Session created = store.create("ada", "   ");
        assertEquals(0, created.messageCount());
    }

    @Test
    void memoryWithToolTurnsRoundTrips() {
        Session created = store.create("ada", null);
        created.memory().addUser("run ls");
        created.memory().replaceAll(List.of(
                ChatMessage.user("run ls"),
                ChatMessage.assistantToolCalls("let me look",
                        List.of(new ToolCall("call_abc", "exec", "{\"command\":\"ls\"}"))),
                ChatMessage.tool("file1\nfile2", "call_abc")));
        store.replaceMessages(created);

        Session reloaded = store.find("ada", created.id()).orElseThrow();
        List<ChatMessage> messages = reloaded.memory().messages();
        assertEquals(3, messages.size());
        assertEquals("call_abc", messages.get(1).toolCalls().get(0).id());
        assertEquals("call_abc", messages.get(2).toolCallId());
        assertEquals("tool", messages.get(2).role());
    }

    // ---- migration ------------------------------------------------------

    @Test
    void legacyUpgrade_movesMessagesIntoRowsAndBackfillsTitles() {
        // Stand on the pre-split schema (V1..V3): messages still lives in one jsonb.
        jdbc.execute("drop schema if exists agent cascade");
        Flyway.configure()
                .dataSource(postgres.getPostgresDatabase())
                .schemas("agent")
                .defaultSchema("agent")
                .locations("classpath:db/migration")
                .target(org.flywaydb.core.api.MigrationVersion.fromVersion("3"))
                .load()
                .migrate();
        new JdbcUserSeeder(jdbc, encoder).seed(List.of(
                new SecurityProperties.User("ada", "ada-pw", List.of("USER"))));

        String sessionId = UUID.randomUUID().toString();
        String legacyImage = "data:image/png;base64,AAAA";
        jdbc.update(
                "insert into agent.sessions (id, owner, created_at, messages) values (?::uuid, ?, now(), ?::jsonb)",
                sessionId, "ada",
                "[" +
                        "{\"role\":\"system\",\"content\":\"be brief\"}," +
                        "{\"role\":\"user\",\"content\":\"look at this project layout\"," +
                        " \"images\":[\"" + legacyImage + "\"]}," +
                        "{\"role\":\"assistant\",\"content\":\"\",\"toolCalls\":" +
                        "[{\"id\":\"call_1\",\"name\":\"exec\",\"arguments\":\"{\\\"command\\\":\\\"ls\\\"}\"}]}," +
                        "{\"role\":\"tool\",\"content\":\"src\\ntest\",\"toolCallId\":\"call_1\"}" +
                        "]");

        // The real upgrade path: V4 + V5 externalise inline images, V6 splits the table.
        migrateToLatest();

        List<String> roles = jdbc.queryForList(
                "select role from agent.session_messages where session_id = ?::uuid order by seq",
                String.class, sessionId);
        assertEquals(List.of("system", "user", "assistant", "tool"), roles);

        String images = jdbc.queryForObject(
                "select images::text from agent.session_messages where session_id = ?::uuid and seq = 1",
                String.class, sessionId);
        assertTrue(images.contains("ref:" + sessionId + "/"), images);
        assertFalse(images.contains("AAAA"), "V5 must have externalised the pixels before V6 moved the row: " + images);

        String toolCalls = jdbc.queryForObject(
                "select tool_calls::text from agent.session_messages where session_id = ?::uuid and seq = 2",
                String.class, sessionId);
        assertTrue(toolCalls.contains("call_1"), toolCalls);

        String toolCallId = jdbc.queryForObject(
                "select tool_call_id from agent.session_messages where session_id = ?::uuid and seq = 3",
                String.class, sessionId);
        assertEquals("call_1", toolCallId);

        String title = jdbc.queryForObject(
                "select title from agent.sessions where id = ?::uuid", String.class, sessionId);
        assertEquals("look at this project layout", title, "title backfills from the first user message");

        assertFalse(tableExists("agent.sessions", "messages"), "the jsonb column must be gone");

        Session reloaded = store.find("ada", sessionId).orElseThrow();
        String ref = reloaded.memory().messages().get(1).images().get(0);
        assertEquals(sha256Hex(legacyImage), ImageRef.imageIdOf(ref),
                "the migration must hash like the Java-side externalisation");
        assertEquals(legacyImage,
                store.findImageContent(sessionId, ImageRef.imageIdOf(ref)).orElseThrow());
    }

    private static boolean tableExists(String table, String column) {
        Integer count = jdbc.queryForObject(
                "select count(*) from information_schema.columns where table_name = ? and column_name = ?",
                Integer.class, table.substring(table.indexOf('.') + 1), column);
        return count != null && count > 0;
    }

    private static String sha256Hex(String content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
