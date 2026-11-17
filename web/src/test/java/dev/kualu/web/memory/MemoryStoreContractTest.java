package dev.kualu.web.memory;

import dev.kualu.llm.memory.MemoryEntry;
import dev.kualu.llm.memory.MemoryEntry.Kind;
import dev.kualu.llm.memory.MemoryEntry.Source;
import dev.kualu.llm.memory.UserProfile;
import dev.kualu.llm.memory.UserProfile.Gender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Behaviour every {@link MemoryStore} implementation must satisfy. Runs against
 * the in-memory store and the Postgres store; owner isolation and hard-delete
 * semantics are contract, not implementation detail.
 */
abstract class MemoryStoreContractTest {

    protected MemoryStore store;

    @BeforeEach
    void createStore() {
        store = newStore();
    }

    protected abstract MemoryStore newStore();

    private static MemoryEntry entry(String owner, String content) {
        return new MemoryEntry(null, owner, Kind.FACT, content, Source.EXPLICIT, "session-1",
                null, null);
    }

    // ---- profile ----

    @Test
    void profile_roundTrips() {
        store.saveProfile("ada", new UserProfile("Jason", Gender.FEMALE, "杭州"));

        Optional<UserProfile> found = store.findProfile("ada");
        assertTrue(found.isPresent());
        assertEquals("Jason", found.get().nickname());
        assertEquals(Gender.FEMALE, found.get().gender());
        assertEquals("杭州", found.get().city());
    }

    @Test
    void profile_upsertReplacesFields() {
        store.saveProfile("ada", new UserProfile("Jason", Gender.MALE, "杭州"));
        store.saveProfile("ada", new UserProfile("老王", Gender.UNSPECIFIED, "上海"));

        UserProfile found = store.findProfile("ada").orElseThrow();
        assertEquals("老王", found.nickname());
        assertEquals(Gender.UNSPECIFIED, found.gender());
        assertEquals("上海", found.city());
    }

    @Test
    void profile_isPerOwner() {
        store.saveProfile("ada", new UserProfile("Jason", Gender.MALE, "杭州"));

        assertTrue(store.findProfile("bob").isEmpty());
    }

    @Test
    void deleteProfile_removesRow() {
        store.saveProfile("ada", new UserProfile("Jason", Gender.MALE, "杭州"));

        store.deleteProfile("ada");

        assertTrue(store.findProfile("ada").isEmpty());
    }

    // ---- entries ----

    @Test
    void entry_insertAssignsIdAndRoundTrips() {
        MemoryEntry saved = store.saveEntry(entry("ada", "服务部署在 K8s"));

        assertTrue(saved.id() != null);
        MemoryEntry found = store.findEntry("ada", saved.id()).orElseThrow();
        assertEquals("服务部署在 K8s", found.content());
        assertEquals(Kind.FACT, found.kind());
        assertEquals(Source.EXPLICIT, found.source());
        assertEquals("session-1", found.sourceSessionId());
    }

    @Test
    void entry_updateChangesContentOnly() throws InterruptedException {
        MemoryEntry saved = store.saveEntry(entry("ada", "旧内容"));
        Thread.sleep(5);
        MemoryEntry updated = store.saveEntry(new MemoryEntry(saved.id(), "ada", Kind.PREFERENCE,
                "新内容", Source.AUTO, "other-session", null, null));

        assertEquals("新内容", updated.content());
        assertEquals(saved.id(), updated.id());
        assertEquals(Kind.FACT, updated.kind(), "kind is immutable on update");
        assertEquals(Source.EXPLICIT, updated.source(), "source is immutable on update");
        assertEquals("session-1", updated.sourceSessionId());
        assertTrue(updated.updatedAt().isAfter(saved.updatedAt())
                || updated.updatedAt().equals(saved.updatedAt()));
    }

    @Test
    void list_isNewestFirst() {
        MemoryEntry first = store.saveEntry(entry("ada", "第一条"));
        MemoryEntry second = store.saveEntry(entry("ada", "第二条"));
        store.saveEntry(new MemoryEntry(first.id(), "ada", Kind.FACT, "第一条（已更新）",
                Source.EXPLICIT, null, null, null));

        List<MemoryEntry> listed = store.listEntries("ada");
        assertEquals(2, listed.size());
        assertEquals(first.id(), listed.get(0).id(), "the updated entry is newest");
        assertEquals(second.id(), listed.get(1).id());
    }

    @Test
    void owner_isolation() {
        MemoryEntry saved = store.saveEntry(entry("ada", "ada 的事实"));
        store.saveProfile("ada", new UserProfile("Jason", Gender.MALE, "杭州"));

        assertTrue(store.findEntry("bob", saved.id()).isEmpty());
        assertTrue(store.listEntries("bob").isEmpty());
        assertTrue(store.findProfile("bob").isEmpty());
        assertFalse(store.deleteEntry("bob", saved.id()), "another owner cannot delete");
        assertEquals(1, store.listEntries("ada").size());
    }

    @Test
    void saveEntry_foreignId_insertsFreshInsteadOfClobbering() {
        MemoryEntry mine = store.saveEntry(entry("ada", "ada 的"));
        MemoryEntry stolen = new MemoryEntry(mine.id(), "bob", Kind.FACT, "bob 的",
                Source.EXPLICIT, null, null, null);

        MemoryEntry saved = store.saveEntry(stolen);

        assertNotEquals(mine.id(), saved.id(), "a foreign id must not be reused");
        assertEquals("ada 的", store.findEntry("ada", mine.id()).orElseThrow().content(),
                "the other owner's entry is untouched");
        assertEquals("bob 的", store.findEntry("bob", saved.id()).orElseThrow().content());
    }

    // ---- deletes ----

    @Test
    void deleteEntry_hardDeletes() {
        MemoryEntry saved = store.saveEntry(entry("ada", "要删的"));

        assertTrue(store.deleteEntry("ada", saved.id()));
        assertTrue(store.findEntry("ada", saved.id()).isEmpty());
        assertTrue(store.listEntries("ada").isEmpty());
        assertFalse(store.deleteEntry("ada", saved.id()), "second delete reports false");
    }

    @Test
    void clear_removesProfileAndEveryEntry() {
        store.saveProfile("ada", new UserProfile("Jason", Gender.MALE, "杭州"));
        store.saveEntry(entry("ada", "第一条"));
        store.saveEntry(entry("ada", "第二条"));
        store.saveEntry(entry("bob", "别人的"));

        store.clear("ada");

        assertTrue(store.findProfile("ada").isEmpty());
        assertTrue(store.listEntries("ada").isEmpty());
        assertEquals(1, store.listEntries("bob").size(), "clear is owner-scoped");
    }

    @Test
    void entry_idsAreDistinct() {
        assertNotEquals(store.saveEntry(entry("ada", "a")).id(), store.saveEntry(entry("ada", "b")).id());
    }
}
