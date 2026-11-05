package dev.kualu.mcp.auth;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileTokenStoreTest {

    @Test
    void saveLoadRoundTrip(@TempDir Path dir) {
        FileTokenStore store = new FileTokenStore(dir.resolve("tokens.json"));
        store.save("demo", new StoredTokens("acc", "ref", 12345L));

        StoredTokens loaded = store.load("demo").orElseThrow();
        assertEquals("acc", loaded.accessToken());
        assertEquals("ref", loaded.refreshToken());
        assertEquals(12345L, loaded.expiresAt());
    }

    @Test
    void deleteRemovesTheEntry(@TempDir Path dir) {
        FileTokenStore store = new FileTokenStore(dir.resolve("tokens.json"));
        store.save("demo", new StoredTokens("acc", null, 1L));
        store.delete("demo");
        assertTrue(store.load("demo").isEmpty());
    }

    @Test
    void corruptFileIsIgnored(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("tokens.json");
        Files.writeString(file, "not json at all");
        FileTokenStore store = new FileTokenStore(file);
        assertTrue(store.load("demo").isEmpty());
        // a save must still work afterwards
        store.save("demo", new StoredTokens("acc", null, 1L));
        assertEquals("acc", store.load("demo").orElseThrow().accessToken());
    }

    @Test
    void expiryCheckAllowsClockSkew() {
        StoredTokens tokens = new StoredTokens("acc", null, 100L);
        assertTrue(tokens.isExpired(71));
        assertTrue(!tokens.isExpired(69));
    }
}
