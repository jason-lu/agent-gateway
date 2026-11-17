package dev.kualu.llm.moderation;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The ops-maintained lexicon: per category a severity and a term list.
 * Parsed from JSON — the schema is documented in the content-moderation spec
 * and exemplified by {@code moderation/lexicon.json}.
 */
final class Lexicon {

    enum TermType {
        /** Normalized substring match (the fast AC path). */
        EXACT,
        /** Regex against the detection view. */
        REGEX,
        /** {@code *}-separated literals; each {@code *} stands for 0–4 characters. */
        WILDCARD
    }

    record Term(String pattern, TermType type) {
    }

    record Entry(Severity severity, List<Term> terms) {
    }

    private final Map<Category, Entry> entries;

    private Lexicon(Map<Category, Entry> entries) {
        this.entries = entries;
    }

    static Lexicon parse(String json) {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        JsonObject categories = root.getAsJsonObject("categories");
        if (categories == null) {
            throw new IllegalArgumentException("lexicon: missing 'categories'");
        }
        Map<Category, Entry> entries = new EnumMap<>(Category.class);
        for (Map.Entry<String, JsonElement> categoryEntry : categories.entrySet()) {
            Category category = Category.parse(categoryEntry.getKey());
            if (category == Category.NONE) {
                throw new IllegalArgumentException("lexicon: unknown category '" + categoryEntry.getKey() + "'");
            }
            JsonObject body = categoryEntry.getValue().getAsJsonObject();
            Severity severity = parseSeverity(body.get("severity"), categoryEntry.getKey());
            List<Term> terms = new ArrayList<>();
            JsonArray termArray = body.getAsJsonArray("terms");
            if (termArray != null) {
                for (JsonElement termElement : termArray) {
                    JsonObject termObject = termElement.getAsJsonObject();
                    String pattern = termObject.get("pattern").getAsString();
                    if (pattern == null || pattern.isBlank()) {
                        continue;
                    }
                    terms.add(new Term(pattern, parseType(termObject.get("type"))));
                }
            }
            entries.put(category, new Entry(severity, List.copyOf(terms)));
        }
        return new Lexicon(Map.copyOf(entries));
    }

    private static Severity parseSeverity(JsonElement element, String category) {
        if (element == null || element.isJsonNull()) {
            throw new IllegalArgumentException("lexicon: category '" + category + "' is missing 'severity'");
        }
        try {
            return Severity.valueOf(element.getAsString().trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("lexicon: category '" + category + "' has bad severity '"
                    + element.getAsString() + "'");
        }
    }

    private static TermType parseType(JsonElement element) {
        if (element == null || element.isJsonNull()) {
            return TermType.EXACT;
        }
        return switch (element.getAsString().trim().toLowerCase(Locale.ROOT)) {
            case "regex" -> TermType.REGEX;
            case "wildcard" -> TermType.WILDCARD;
            default -> TermType.EXACT;
        };
    }

    Map<Category, Entry> entries() {
        return Collections.unmodifiableMap(entries);
    }
}
