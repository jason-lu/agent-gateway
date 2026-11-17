package dev.kualu.llm.moderation;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.text.Normalizer;
import java.util.HashMap;
import java.util.Map;

/**
 * L0 detection view of a text: Unicode NFKC, confusable folding, traditional →
 * simplified, lower-casing, and separator stripping — with an index map back to
 * the original so matched spans can be excerpted and redacted accurately.
 *
 * <p>The view exists only for matching. Nothing is ever persisted in normalized
 * form; audit rows keep a masked excerpt of the original.
 */
public final class NormalizedText {

    private static final String TRAD_SIMPLIFIED_RESOURCE = "traditional-simplified.txt";
    private static final String CONFUSABLES_RESOURCE = "confusables.txt";

    /** Traditional → simplified, one {@code 傳=传} pair per line. */
    private static final Map<Character, Character> TRAD_SIMPLIFIED = loadCharMap(TRAD_SIMPLIFIED_RESOURCE);

    /** Confusable folding (Cyrillic/Greek look-alikes → Latin). */
    private static final Map<Character, Character> CONFUSABLES = loadCharMap(CONFUSABLES_RESOURCE);

    private final String original;
    private final String text;
    private final int[] originStart;
    private final int[] originEnd;

    private NormalizedText(String original, String text, int[] originStart, int[] originEnd) {
        this.original = original;
        this.text = text;
        this.originStart = originStart;
        this.originEnd = originEnd;
    }

    /** Builds the detection view for {@code original}. */
    public static NormalizedText of(String original) {
        StringBuilder out = new StringBuilder(original.length());
        int[] starts = new int[original.length() + 1];
        int[] ends = new int[original.length() + 1];
        int n = 0;
        int i = 0;
        while (i < original.length()) {
            int cp = original.codePointAt(i);
            int charCount = Character.charCount(cp);
            int originFrom = i;
            int originTo = i + charCount;
            i = originTo;
            if (isStripped(cp)) {
                continue;
            }
            String folded = Normalizer.normalize(new String(Character.toChars(cp)), Normalizer.Form.NFKC);
            for (int f = 0; f < folded.length(); f++) {
                char mapped = mapChar(folded.charAt(f));
                if (isStripped(mapped)) {
                    continue;
                }
                out.append(mapped);
                starts[n] = originFrom;
                ends[n] = originTo;
                n++;
            }
        }
        int[] startView = new int[n];
        int[] endView = new int[n];
        System.arraycopy(starts, 0, startView, 0, n);
        System.arraycopy(ends, 0, endView, 0, n);
        return new NormalizedText(original, out.toString(), startView, endView);
    }

    /** The original text as given. */
    public String original() {
        return original;
    }

    /** The detection view: what lexicon matching runs against. */
    public String text() {
        return text;
    }

    /**
     * Maps a half-open span in the detection view back to the original,
     * covering every original character the span touches.
     */
    public ModerationHit.Span toOriginalSpan(int normStart, int normEnd) {
        if (normStart < 0 || normEnd < normStart || normEnd > text.length()) {
            throw new IllegalArgumentException("bad normalized span [" + normStart + "," + normEnd + ")");
        }
        if (normStart == normEnd) {
            int at = Math.min(normStart, original.length());
            return new ModerationHit.Span(at, at);
        }
        return new ModerationHit.Span(originStart[normStart], originEnd[normEnd - 1]);
    }

    /** The original substring covering a normalized span (separators included). */
    public String originalSubstring(int normStart, int normEnd) {
        ModerationHit.Span span = toOriginalSpan(normStart, normEnd);
        return original.substring(span.start(), span.end());
    }

    private static char mapChar(char c) {
        char conf = CONFUSABLES.getOrDefault(c, c);
        char mapped = TRAD_SIMPLIFIED.getOrDefault(conf, conf);
        return Character.toLowerCase(mapped);
    }

    /** Zero-width/format controls and the punctuation/symbol classes evasion relies on. */
    private static boolean isStripped(int cp) {
        int type = Character.getType(cp);
        return switch (type) {
            case Character.CONTROL, Character.FORMAT,
                 Character.SPACE_SEPARATOR, Character.LINE_SEPARATOR, Character.PARAGRAPH_SEPARATOR,
                 Character.DASH_PUNCTUATION, Character.START_PUNCTUATION, Character.END_PUNCTUATION,
                 Character.CONNECTOR_PUNCTUATION, Character.OTHER_PUNCTUATION,
                 Character.INITIAL_QUOTE_PUNCTUATION, Character.FINAL_QUOTE_PUNCTUATION,
                 Character.MATH_SYMBOL, Character.CURRENCY_SYMBOL,
                 Character.MODIFIER_SYMBOL, Character.OTHER_SYMBOL -> true;
            default -> false;
        };
    }

    private static Map<Character, Character> loadCharMap(String resourceName) {
        Map<Character, Character> map = new HashMap<>();
        try (InputStream in = NormalizedText.class.getResourceAsStream(resourceName)) {
            if (in == null) {
                throw new IllegalStateException("missing resource "
                        + NormalizedText.class.getPackageName().replace('.', '/') + "/" + resourceName);
            }
            String content = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            for (String line : content.split("\n")) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    continue;
                }
                int eq = trimmed.indexOf('=');
                if (eq <= 0 || eq + 1 >= trimmed.length()) {
                    continue;
                }
                char from = trimmed.charAt(0);
                char to = trimmed.charAt(eq + 1);
                map.put(from, to);
            }
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
        return Map.copyOf(map);
    }
}
