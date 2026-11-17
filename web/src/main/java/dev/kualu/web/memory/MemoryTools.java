package dev.kualu.web.memory;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import dev.kualu.llm.AgentTool;
import dev.kualu.llm.memory.HardIdentifierDetector;
import dev.kualu.llm.memory.MemoryEntry;
import dev.kualu.llm.memory.UserProfile;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * The per-owner semantic-memory tools offered to the model: save an entry,
 * forget an entry, set a profile field. Constructed per owner per turn (the
 * {@link AgentTool} contract carries no owner). Every write runs the hard-
 * identifier gate first; a refusal is returned as result text so the model can
 * relay it — failures never throw.
 */
public final class MemoryTools {

    public static final int MAX_CONTENT_CHARS = 500;
    public static final int MAX_NICKNAME_CHARS = 32;
    public static final int MAX_CITY_CHARS = 32;

    public static final String REFUSAL_PII =
            "记忆未保存：内容含身份信息（手机号/邮箱/证件/住址等），按隐私策略不记录。请直接告知用户这一条没有被记录。";

    /** Spec S2.2 city blacklist: anything finer than city level is refused. */
    private static final java.util.regex.Pattern CITY_GRANULARITY = java.util.regex.Pattern.compile(
            "区|县|街道|镇|乡|路|街|号|室|村");

    /** True when a profile city value is finer than city level (district/street/door). */
    public static boolean cityTooGranular(String city) {
        return city != null && CITY_GRANULARITY.matcher(city).find();
    }

    private MemoryTools() {
    }

    public static List<AgentTool> forOwner(String owner, MemoryStore store) {
        HardIdentifierDetector detector = new HardIdentifierDetector();
        return List.of(
                new SaveTool(owner, store, detector),
                new ForgetTool(owner, store),
                new ProfileSetTool(owner, store, detector));
    }

    static final class SaveTool implements AgentTool {
        private final String owner;
        private final MemoryStore store;
        private final HardIdentifierDetector detector;

        SaveTool(String owner, MemoryStore store, HardIdentifierDetector detector) {
            this.owner = owner;
            this.store = store;
            this.detector = detector;
        }

        @Override
        public String name() {
            return "memory_save";
        }

        @Override
        public String description() {
            return "Save a stable fact or preference about the user to cross-session memory. "
                    + "Refuses content carrying hard identifiers (phone, email, ID, address…). "
                    + "Pass id to update an existing entry.";
        }

        @Override
        public String parametersJson() {
            return "{\"type\":\"object\",\"properties\":{"
                    + "\"kind\":{\"type\":\"string\",\"enum\":[\"fact\",\"preference\"]},"
                    + "\"content\":{\"type\":\"string\"},"
                    + "\"id\":{\"type\":\"string\"},"
                    + "\"source\":{\"type\":\"string\",\"enum\":[\"explicit\",\"auto\",\"confirm\"]}"
                    + "},\"required\":[\"kind\",\"content\"]}";
        }

        @Override
        public String execute(String argumentsJson) {
            try {
                JsonObject args = JsonParser.parseString(argumentsJson).getAsJsonObject();
                String kindText = args.has("kind") ? args.get("kind").getAsString() : "";
                String content = args.has("content") ? args.get("content").getAsString().strip() : "";
                String id = args.has("id") && !args.get("id").isJsonNull() ? args.get("id").getAsString().strip() : "";
                String sourceText = args.has("source") ? args.get("source").getAsString().strip() : "auto";

                MemoryEntry.Kind kind;
                try {
                    kind = MemoryEntry.Kind.valueOf(kindText.toUpperCase(Locale.ROOT));
                } catch (IllegalArgumentException ex) {
                    return "保存失败：kind 必须是 fact 或 preference。";
                }
                MemoryEntry.Source source;
                try {
                    source = MemoryEntry.Source.valueOf(sourceText.toUpperCase(Locale.ROOT));
                } catch (IllegalArgumentException ex) {
                    return "保存失败：source 必须是 explicit、auto 或 confirm。";
                }
                if (content.isEmpty()) {
                    return "保存失败：content 不能为空。";
                }
                if (content.length() > MAX_CONTENT_CHARS) {
                    return "保存失败：content 超过 " + MAX_CONTENT_CHARS + " 字符。";
                }
                if (detector.scan(content).isPresent()) {
                    return REFUSAL_PII;
                }
                UUID entryId = null;
                if (!id.isEmpty()) {
                    try {
                        entryId = UUID.fromString(id);
                    } catch (IllegalArgumentException ex) {
                        return "保存失败：id 不是合法的 UUID。";
                    }
                }
                store.saveEntry(new MemoryEntry(entryId, owner, kind, content, source, null, null, null));
                // The echo stays minimal: the content already sits in this turn,
                // and tool results are persisted — no need to duplicate it.
                return entryId == null ? "已记住 1 条" + (kind == MemoryEntry.Kind.FACT ? "事实" : "偏好") + "。"
                        : "已更新记忆。";
            } catch (JsonSyntaxException | IllegalStateException ex) {
                return "保存失败：参数不是合法的 JSON 对象。";
            } catch (RuntimeException ex) {
                return "保存失败：存储错误，本次未记录。";
            }
        }
    }

    static final class ForgetTool implements AgentTool {
        private final String owner;
        private final MemoryStore store;

        ForgetTool(String owner, MemoryStore store) {
            this.owner = owner;
            this.store = store;
        }

        @Override
        public String name() {
            return "memory_forget";
        }

        @Override
        public String description() {
            return "Hard-delete memory entries: by id, or every entry whose content contains query. "
                    + "Use when the user says to forget something.";
        }

        @Override
        public String parametersJson() {
            return "{\"type\":\"object\",\"properties\":{"
                    + "\"id\":{\"type\":\"string\"},"
                    + "\"query\":{\"type\":\"string\"}}}";
        }

        @Override
        public String execute(String argumentsJson) {
            try {
                JsonObject args = JsonParser.parseString(argumentsJson).getAsJsonObject();
                String id = args.has("id") && !args.get("id").isJsonNull() ? args.get("id").getAsString().strip() : "";
                String query = args.has("query") && !args.get("query").isJsonNull()
                        ? args.get("query").getAsString().strip() : "";
                if (!id.isEmpty()) {
                    UUID entryId;
                    try {
                        entryId = UUID.fromString(id);
                    } catch (IllegalArgumentException ex) {
                        return "删除失败：id 不是合法的 UUID。";
                    }
                    return store.deleteEntry(owner, entryId)
                            ? "已删除 1 条记忆。"
                            : "没有找到该记忆。";
                }
                if (query.isEmpty()) {
                    return "删除失败：需要提供 id 或 query。";
                }
                String needle = query.toLowerCase(Locale.ROOT);
                int deleted = 0;
                for (MemoryEntry entry : store.listEntries(owner)) {
                    if (entry.content().toLowerCase(Locale.ROOT).contains(needle)
                            && store.deleteEntry(owner, entry.id())) {
                        deleted++;
                    }
                }
                return deleted == 0
                        ? "没有找到与「" + query + "」相关的记忆。"
                        : "已删除 " + deleted + " 条与「" + query + "」相关的记忆。";
            } catch (JsonSyntaxException | IllegalStateException ex) {
                return "删除失败：参数不是合法的 JSON 对象。";
            } catch (RuntimeException ex) {
                return "删除失败：存储错误。";
            }
        }
    }

    static final class ProfileSetTool implements AgentTool {
        private final String owner;
        private final MemoryStore store;
        private final HardIdentifierDetector detector;

        ProfileSetTool(String owner, MemoryStore store, HardIdentifierDetector detector) {
            this.owner = owner;
            this.store = store;
            this.detector = detector;
        }

        @Override
        public String name() {
            return "profile_set";
        }

        @Override
        public String description() {
            return "Set one profile field (nickname / gender / city) the user explicitly stated. "
                    + "City stays at city level; values carrying hard identifiers are refused.";
        }

        @Override
        public String parametersJson() {
            return "{\"type\":\"object\",\"properties\":{"
                    + "\"field\":{\"type\":\"string\",\"enum\":[\"nickname\",\"gender\",\"city\"]},"
                    + "\"value\":{\"type\":\"string\"}"
                    + "},\"required\":[\"field\",\"value\"]}";
        }

        @Override
        public String execute(String argumentsJson) {
            try {
                JsonObject args = JsonParser.parseString(argumentsJson).getAsJsonObject();
                String field = args.has("field") ? args.get("field").getAsString().strip() : "";
                String value = args.has("value") ? args.get("value").getAsString().strip() : "";

                UserProfile existing = store.findProfile(owner)
                        .orElse(new UserProfile(null, null, null));
                String nickname = existing.nickname();
                UserProfile.Gender gender = existing.gender();
                String city = existing.city();

                switch (field) {
                    case "nickname" -> {
                        if (value.isEmpty() || value.length() > MAX_NICKNAME_CHARS) {
                            return "保存失败：称呼需为 1–" + MAX_NICKNAME_CHARS + " 字符。";
                        }
                        if (detector.scan(value).isPresent()) {
                            return REFUSAL_PII;
                        }
                        nickname = value;
                    }
                    case "city" -> {
                        if (value.isEmpty() || value.length() > MAX_CITY_CHARS) {
                            return "保存失败：城市需为 1–" + MAX_CITY_CHARS + " 字符。";
                        }
                        if (cityTooGranular(value) || detector.scan(value).isPresent()) {
                            return "保存失败：城市只写到市级（不含区/县/街道/镇/路/门牌）。" + REFUSAL_PII;
                        }
                        city = value;
                    }
                    case "gender" -> {
                        UserProfile.Gender parsed = parseGender(value);
                        if (parsed == null) {
                            return "保存失败：gender 只能是 男 / 女 / 不愿透露。";
                        }
                        gender = parsed;
                    }
                    default -> {
                        return "保存失败：field 必须是 nickname、gender 或 city。";
                    }
                }
                store.saveProfile(owner, new UserProfile(nickname, gender, city));
                return "已更新档案：" + field + "=" + value;
            } catch (JsonSyntaxException | IllegalStateException ex) {
                return "保存失败：参数不是合法的 JSON 对象。";
            } catch (RuntimeException ex) {
                return "保存失败：存储错误，本次未记录。";
            }
        }

        private static UserProfile.Gender parseGender(String value) {
            return switch (value.toLowerCase(Locale.ROOT)) {
                case "男", "male", "m" -> UserProfile.Gender.MALE;
                case "女", "female", "f" -> UserProfile.Gender.FEMALE;
                case "不愿透露", "unspecified", "unknown", "保密" -> UserProfile.Gender.UNSPECIFIED;
                default -> null;
            };
        }
    }
}
