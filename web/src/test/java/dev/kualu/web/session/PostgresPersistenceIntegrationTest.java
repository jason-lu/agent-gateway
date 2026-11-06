package dev.kualu.web.session;

import tools.jackson.databind.ObjectMapper;
import dev.kualu.llm.ChatMessage;
import dev.kualu.web.auth.JdbcUserDetailsService;
import dev.kualu.web.auth.JdbcUserSeeder;
import dev.kualu.web.config.SecurityProperties;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.io.IOException;
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
 * (zonky embedded — no Docker needed). Each test starts from a freshly migrated
 * schema, seeded with the two accounts the fixtures use.
 */
class PostgresPersistenceIntegrationTest {

    private static final String SUMMARY = "【历史对话摘要】user is building a parser";

    private static EmbeddedPostgres postgres;
    private static JdbcTemplate jdbc;
    private static PostgresSessionStore store;
    private static PasswordEncoder encoder;

    @BeforeAll
    static void startDatabase() throws IOException {
        postgres = EmbeddedPostgres.start();
        jdbc = new JdbcTemplate(postgres.getPostgresDatabase());
        store = new PostgresSessionStore(jdbc, new ChatMessageCodec(new ObjectMapper()));
        encoder = PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    @AfterAll
    static void stopDatabase() throws IOException {
        if (postgres != null) {
            postgres.close();
        }
    }

    @BeforeEach
    void migrateFresh() {
        jdbc.execute("drop schema if exists agent cascade");
        Flyway.configure()
                .dataSource(postgres.getPostgresDatabase())
                .schemas("agent")
                .defaultSchema("agent")
                .locations("classpath:db/migration")
                .load()
                .migrate();
        new JdbcUserSeeder(jdbc, encoder).seed(List.of(
                new SecurityProperties.User("ada", "ada-pw", List.of("USER")),
                new SecurityProperties.User("bob", "bob-pw", List.of("USER"))));
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
        store.update(created);

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
    void imagesAreExternalizedAndResolved() {
        Session created = store.create("ada", null);
        created.memory().replaceAll(List.of(
                ChatMessage.user("look at this", List.of("data:image/png;base64,AAAA"))));
        store.update(created);

        String json = jdbc.queryForObject(
                "select messages::text from agent.sessions where id = ?::uuid", String.class, created.id());
        assertTrue(json.contains("ref:" + created.id() + "/"), json);
        assertFalse(json.contains("AAAA"), "pixels must not live in the jsonb: " + json);

        Session reloaded = store.find("ada", created.id()).orElseThrow();
        String ref = reloaded.memory().messages().get(0).images().get(0);
        assertTrue(ImageRef.isRef(ref), ref);
        assertEquals("data:image/png;base64,AAAA",
                store.findImageContent(created.id(), ImageRef.imageIdOf(ref)).orElseThrow());

        // Re-persisting the same image must not duplicate the row.
        store.update(reloaded);
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
    void backfillMigration_externalizesLegacyInlineImages() throws Exception {
        String sessionId = UUID.randomUUID().toString();
        String legacyImage = "data:image/png;base64,AAAA";
        jdbc.update(
                "insert into agent.sessions (id, owner, created_at, messages) values (?::uuid, ?, now(), ?::jsonb)",
                sessionId, "ada",
                "[{\"role\":\"user\",\"content\":\"look\",\"images\":[\"" + legacyImage + "\"]}]");

        // Replaying the shipped script (Flyway already ran it on an empty table)
        // must migrate legacy rows and stay idempotent.
        String script = new String(
                getClass().getClassLoader()
                        .getResourceAsStream("db/migration/V5__externalize_session_images.sql")
                        .readAllBytes(),
                StandardCharsets.UTF_8);
        jdbc.execute(script);

        String json = jdbc.queryForObject(
                "select messages::text from agent.sessions where id = ?::uuid", String.class, sessionId);
        assertTrue(json.contains("ref:" + sessionId + "/"), json);
        assertFalse(json.contains("AAAA"), "pixels must be gone from the jsonb: " + json);

        Session reloaded = store.find("ada", sessionId).orElseThrow();
        String ref = reloaded.memory().messages().get(0).images().get(0);
        assertEquals(sha256Hex(legacyImage), ImageRef.imageIdOf(ref),
                "the migration must hash like the Java-side externalisation");
        assertEquals(legacyImage,
                store.findImageContent(sessionId, ImageRef.imageIdOf(ref)).orElseThrow());
    }

    private static String sha256Hex(String content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    @Test
    void historySurvivesRestart() {
        Session created = store.create("ada", null);
        created.memory().addUser("remember me").addAssistant("I will");
        store.update(created);

        // A brand new store = the process restarted; the row must still be readable.
        PostgresSessionStore afterRestart = new PostgresSessionStore(jdbc, new ChatMessageCodec(new ObjectMapper()));
        Session reloaded = afterRestart.find("ada", created.id()).orElseThrow();

        assertEquals("remember me", reloaded.memory().messages().get(0).content());
        assertEquals("I will", reloaded.memory().messages().get(1).content());
    }

    @Test
    void compressedSummarySurvivesVerbatim() {
        Session created = store.create("ada", "sys");
        created.memory().replaceAll(List.of(
                ChatMessage.system("sys"),
                ChatMessage.system(SUMMARY),
                ChatMessage.user("go on"),
                ChatMessage.assistant("sure")));
        store.update(created);

        Session reloaded = store.find("ada", created.id()).orElseThrow();
        assertEquals("sys", reloaded.memory().messages().get(0).content());
        assertEquals(SUMMARY, reloaded.memory().messages().get(1).content());
    }

    @Test
    void updateDoesNotResurrectADeletedSession() {
        Session created = store.create("ada", null);
        created.memory().addUser("hi");
        assertTrue(store.delete("ada", created.id()));

        store.update(created);

        assertFalse(store.find("ada", created.id()).isPresent());
    }

    @Test
    void blankSystemPromptIsNotStored() {
        Session created = store.create("ada", "   ");
        assertEquals(0, created.messageCount());
    }

    @Test
    void memoryWithToolTurnsRoundTrips() {
        Session created = store.create("ada", null);
        created.memory().addUser("run ls").addTool("file1\nfile2", "call_abc");
        store.update(created);

        Session reloaded = store.find("ada", created.id()).orElseThrow();
        List<ChatMessage> messages = reloaded.memory().messages();
        assertEquals(2, messages.size());
        assertEquals("call_abc", messages.get(1).toolCallId());
        assertEquals("tool", messages.get(1).role());
    }
}
