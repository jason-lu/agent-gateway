package dev.kualu.llm.memory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Assembles the request-only {@code 【用户记忆】} system block from a user's
 * profile and memory entries. Pure: the caller supplies the data and applies
 * whatever the returned purge flags mark for deletion.
 *
 * <p>Selection: profile lines first (they are few and always relevant), then at
 * most {@code maxInject} entries ranked by character-bigram overlap with the
 * current user message (newest first on ties). Every injected line is rescanned
 * for hard identifiers (the US-15 safety net): a hit excludes the line and
 * flags it for purging.
 */
public final class MemoryAssembler {

    /** Block marker; also lets consumers recognise the injected message. */
    public static final String BLOCK_PREFIX = "【用户记忆】";

    /** Entry ids the rescan flagged: exclude from the block and hard-delete. */
    public record Block(String text, List<UUID> purgeEntries, boolean purgeProfile) {
    }

    private final HardIdentifierDetector detector;
    private final int maxInject;

    public MemoryAssembler(int maxInject) {
        this(new HardIdentifierDetector(), maxInject);
    }

    MemoryAssembler(HardIdentifierDetector detector, int maxInject) {
        if (maxInject < 1) {
            throw new IllegalArgumentException("maxInject must be >= 1 (got " + maxInject + ")");
        }
        this.detector = detector;
        this.maxInject = maxInject;
    }

    public Block assemble(UserProfile profile, List<MemoryEntry> entries, String userContent) {
        List<UUID> purgeEntries = new ArrayList<>();
        List<String> lines = new ArrayList<>();

        boolean purgeProfile = !addProfileLines(profile, lines);

        for (MemoryEntry entry : select(entries, userContent)) {
            if (detector.scan(entry.content()).isPresent()) {
                purgeEntries.add(entry.id());
                continue;
            }
            lines.add("- " + label(entry.kind()) + "：" + entry.content());
        }

        if (lines.isEmpty()) {
            return new Block("", purgeEntries, purgeProfile);
        }
        String text = BLOCK_PREFIX + "\n" + String.join("\n", lines);
        return new Block(text, purgeEntries, purgeProfile);
    }

    /** False when the profile itself carried a hard identifier and must be purged. */
    private boolean addProfileLines(UserProfile profile, List<String> lines) {
        if (profile == null) {
            return true;
        }
        String nickname = profile.nickname() == null ? "" : profile.nickname().strip();
        String city = profile.city() == null ? "" : profile.city().strip();
        String gender = profile.gender() == null ? "" : profile.gender().name();
        String combined = nickname + " " + city + " " + gender;
        if (!combined.isBlank() && detector.scan(combined).isPresent()) {
            return false;
        }
        if (!nickname.isBlank()) {
            lines.add("- 称呼：" + nickname);
        }
        if (profile.gender() == UserProfile.Gender.MALE) {
            lines.add("- 性别：男");
        } else if (profile.gender() == UserProfile.Gender.FEMALE) {
            lines.add("- 性别：女");
        }
        if (!city.isBlank()) {
            lines.add("- 生活城市：" + city);
        }
        return true;
    }

    private List<MemoryEntry> select(List<MemoryEntry> entries, String userContent) {
        Set<String> wanted = bigrams(userContent);
        List<MemoryEntry> ranked = new ArrayList<>(entries);
        Map<MemoryEntry, Integer> scores = new java.util.IdentityHashMap<>();
        for (MemoryEntry entry : ranked) {
            scores.put(entry, overlap(wanted, bigrams(entry.content())));
        }
        ranked.sort(Comparator
                .comparingInt((MemoryEntry e) -> scores.get(e)).reversed()
                .thenComparing(MemoryEntry::updatedAt, Comparator.reverseOrder()));
        return ranked.size() <= maxInject ? ranked : List.copyOf(ranked.subList(0, maxInject));
    }

    private static int overlap(Set<String> a, Set<String> b) {
        int count = 0;
        for (String gram : b) {
            if (a.contains(gram)) {
                count++;
            }
        }
        return count;
    }

    /** Character bigrams of the lower-cased text; enough lexical signal without a tokenizer. */
    private static Set<String> bigrams(String text) {
        Set<String> grams = new HashSet<>();
        if (text == null || text.length() < 2) {
            return grams;
        }
        String normalized = text.toLowerCase(java.util.Locale.ROOT).replaceAll("\\s+", "");
        for (int i = 0; i + 1 < normalized.length(); i++) {
            grams.add(normalized.substring(i, i + 2));
        }
        return grams;
    }

    private static String label(MemoryEntry.Kind kind) {
        return kind == MemoryEntry.Kind.PREFERENCE ? "偏好" : "事实";
    }
}
