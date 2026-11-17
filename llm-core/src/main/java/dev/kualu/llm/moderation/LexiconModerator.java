package dev.kualu.llm.moderation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * L1 rule layer: lexicon matching over the {@link NormalizedText} detection
 * view. Synchronous and fast (one AC pass plus a small regex list).
 *
 * <p>The lexicon source is either a classpath resource ({@code classpath:...},
 * loaded once) or a file path (hot-reloaded whenever the mtime changes — the
 * old compiled instance keeps serving until the new one is ready).
 */
public final class LexiconModerator implements TextModerator {

    private static final Logger log = LoggerFactory.getLogger(LexiconModerator.class);

    /** {@code *} in a wildcard term stands for 0–4 arbitrary characters. */
    private static final String WILDCARD_GAP = ".{0,4}";

    private final ModerationPolicy policy;
    private final String source;
    private final boolean hotReload;
    private volatile Compiled compiled;

    /**
     * @param policy disposition matrix
     * @param source {@code classpath:...} resource name, or a filesystem path
     */
    public LexiconModerator(ModerationPolicy policy, String source) {
        this.policy = Objects.requireNonNull(policy, "policy");
        this.source = Objects.requireNonNull(source, "source");
        this.hotReload = !source.startsWith("classpath:");
        this.compiled = compile(load().json());
    }

    @Override
    public Optional<ModerationHit> check(String text, Direction direction) {
        Objects.requireNonNull(text, "text");
        reloadIfNeeded();
        return checkCompiled(compiled, text);
    }

    /** Every matched span in original-text coordinates — what {@link ModerationHit#redact} needs. */
    public List<ModerationHit.Span> matchSpans(String text) {
        Objects.requireNonNull(text, "text");
        reloadIfNeeded();
        return collect(compiled, text).spans();
    }

    // ---------------------------------------------------------------- matching

    private record Finding(Category category, Severity severity, ModerationHit.Span span) {
    }

    private record Collected(List<Finding> findings, List<ModerationHit.Span> spans) {
    }

    private Optional<ModerationHit> checkCompiled(Compiled compiled, String text) {
        Collected collected = collect(compiled, text);
        if (collected.findings().isEmpty()) {
            return Optional.empty();
        }
        // Severity ordinal: HIGH=0 — the smallest ordinal wins, ties go to the earliest span.
        Finding primary = collected.findings().stream()
                .min(java.util.Comparator
                        .comparingInt((Finding f) -> f.severity().ordinal())
                        .thenComparingInt(f -> f.span().start()))
                .orElseThrow();
        Action action = policy.actionFor(primary.severity());
        String excerpt = ModerationHit.excerptOf(text, primary.span());
        return Optional.of(new ModerationHit(primary.category(), primary.severity(), action,
                excerpt, collected.spans()));
    }

    private Collected collect(Compiled compiled, String text) {
        NormalizedText normalized = NormalizedText.of(text);
        String view = normalized.text();
        List<Finding> findings = new ArrayList<>();
        List<ModerationHit.Span> spans = new ArrayList<>();

        for (AhoCorasick.Match match : compiled.ac().match(view)) {
            CompiledTerm term = compiled.exactTerms().get(match.patternIndex());
            ModerationHit.Span span = normalized.toOriginalSpan(match.start(), match.end());
            findings.add(new Finding(term.category(), term.severity(), span));
            spans.add(span);
        }
        for (CompiledTerm term : compiled.regexTerms()) {
            Matcher matcher = term.pattern().matcher(view);
            while (matcher.find()) {
                ModerationHit.Span span = normalized.toOriginalSpan(matcher.start(), matcher.end());
                findings.add(new Finding(term.category(), term.severity(), span));
                spans.add(span);
            }
        }
        return new Collected(findings, mergeOverlapping(spans));
    }

    /**
     * Exact and wildcard terms often cover the same region twice; merged spans
     * keep {@link ModerationHit#redact} from stacking masks on one location.
     */
    private static List<ModerationHit.Span> mergeOverlapping(List<ModerationHit.Span> spans) {
        if (spans.size() <= 1) {
            return spans;
        }
        List<ModerationHit.Span> sorted = spans.stream()
                .sorted(java.util.Comparator.comparingInt(ModerationHit.Span::start)
                        .thenComparingInt(ModerationHit.Span::end))
                .toList();
        List<ModerationHit.Span> merged = new ArrayList<>();
        ModerationHit.Span current = sorted.get(0);
        for (int i = 1; i < sorted.size(); i++) {
            ModerationHit.Span next = sorted.get(i);
            if (next.start() <= current.end()) {
                current = new ModerationHit.Span(current.start(), Math.max(current.end(), next.end()));
            } else {
                merged.add(current);
                current = next;
            }
        }
        merged.add(current);
        return merged;
    }

    // ---------------------------------------------------------------- lexicon plumbing

    private record CompiledTerm(Category category, Severity severity, Pattern pattern) {
    }

    private record Compiled(AhoCorasick ac, List<CompiledTerm> exactTerms, List<CompiledTerm> regexTerms) {
    }

    private record LoadedImage(String json, long mtime) {
    }

    private long loadedMtime = -1;

    private LoadedImage load() {
        if (hotReload) {
            Path path = Path.of(source);
            try {
                String json = Files.readString(path, StandardCharsets.UTF_8);
                loadedMtime = Files.getLastModifiedTime(path).toMillis();
                return new LoadedImage(json, loadedMtime);
            } catch (IOException ex) {
                throw new UncheckedIOException("moderation lexicon unreadable: " + source, ex);
            }
        }
        String resource = source.substring("classpath:".length());
        try (InputStream in = LexiconModerator.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("missing moderation lexicon resource: " + resource);
            }
            return new LoadedImage(new String(in.readAllBytes(), StandardCharsets.UTF_8), -1);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    private void reloadIfNeeded() {
        if (!hotReload) {
            return;
        }
        Path path = Path.of(source);
        long mtime;
        try {
            mtime = Files.getLastModifiedTime(path).toMillis();
        } catch (IOException ex) {
            // File temporarily gone: keep serving the previous lexicon.
            return;
        }
        if (mtime == loadedMtime) {
            return;
        }
        synchronized (this) {
            if (mtime == loadedMtime) {
                return;
            }
            try {
                String json = Files.readString(path, StandardCharsets.UTF_8);
                compiled = compile(json);
                loadedMtime = mtime;
                log.info("moderation lexicon reloaded from {} (mtime {})", source, mtime);
            } catch (RuntimeException | IOException ex) {
                log.warn("moderation lexicon reload failed; keeping the previous lexicon", ex);
            }
        }
    }

    private Compiled compile(String json) {
        Lexicon lexicon = Lexicon.parse(json);
        List<String> exactPatterns = new ArrayList<>();
        List<CompiledTerm> exactTerms = new ArrayList<>();
        List<CompiledTerm> regexTerms = new ArrayList<>();
        for (var categoryEntry : lexicon.entries().entrySet()) {
            Category category = categoryEntry.getKey();
            Severity severity = categoryEntry.getValue().severity();
            for (Lexicon.Term term : categoryEntry.getValue().terms()) {
                switch (term.type()) {
                    case EXACT -> {
                        String normalizedTerm = NormalizedText.of(term.pattern()).text();
                        if (normalizedTerm.isEmpty()) {
                            continue;
                        }
                        exactPatterns.add(normalizedTerm);
                        exactTerms.add(new CompiledTerm(category, severity, null));
                    }
                    case WILDCARD -> regexTerms.add(new CompiledTerm(category, severity,
                            wildcardPattern(term.pattern())));
                    case REGEX -> regexTerms.add(new CompiledTerm(category, severity,
                            Pattern.compile(term.pattern(), Pattern.DOTALL)));
                }
            }
        }
        Compiled compiled = new Compiled(
                AhoCorasick.compile(exactPatterns), List.copyOf(exactTerms), List.copyOf(regexTerms));
        log.info("moderation lexicon compiled from {}: {} exact, {} regex/wildcard terms",
                source, exactTerms.size(), regexTerms.size());
        return compiled;
    }

    /**
     * {@code 法*轮*功} → {@code 法.{0,4}轮.{0,4}功} against the detection view.
     * Literal segments run through the same normalizer as the text, so
     * traditional forms and separators in the pattern behave like in the text.
     */
    private static Pattern wildcardPattern(String wildcard) {
        StringBuilder regex = new StringBuilder();
        String[] segments = wildcard.split("\\*", -1);
        for (int i = 0; i < segments.length; i++) {
            if (i > 0) {
                regex.append(WILDCARD_GAP);
            }
            String literal = NormalizedText.of(segments[i]).text();
            if (!literal.isEmpty()) {
                regex.append(Pattern.quote(literal));
            }
        }
        if (regex.isEmpty()) {
            throw new IllegalArgumentException("wildcard term is empty: '" + wildcard + "'");
        }
        return Pattern.compile(regex.toString(), Pattern.DOTALL);
    }
}
