package dev.kualu.web.asr;

import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.util.Locale;

/**
 * Opens a fresh {@link StreamingAsrProvider} per WebSocket session from
 * {@link AsrProperties}. {@code mock} is always available; {@code iflytek} needs
 * its credentials before a stream can be opened.
 */
@Component
public class AsrProviders {

    private final AsrProperties properties;
    private final ObjectMapper mapper;

    public AsrProviders(AsrProperties properties, ObjectMapper mapper) {
        this.properties = properties;
        this.mapper = mapper;
    }

    /** False when the configured provider cannot open a stream (e.g. mimo without an API key). */
    public boolean isConfigured() {
        AsrProperties.Mimo mimo = properties.mimo();
        AsrProperties.Iflytek iflytek = properties.iflytek();
        return switch (provider()) {
            case "mock" -> true;
            case "mimo" -> hasText(mimo.apiKey());
            case "iflytek" -> hasText(iflytek.appId()) && hasText(iflytek.apiKey()) && hasText(iflytek.apiSecret());
            default -> false;
        };
    }

    /**
     * A new provider bound to {@code language} (BCP 47, e.g. {@code zh-CN});
     * blank falls back to {@code agent.asr.language}.
     */
    public StreamingAsrProvider open(String language) {
        String effective = hasText(language) ? language : properties.language();
        return switch (provider()) {
            case "mock" -> new MockAsrProvider();
            case "mimo" -> new MimoAsrProvider(
                    mapper,
                    properties.mimo().apiKey(),
                    properties.mimo().baseUrl(),
                    properties.mimo().model(),
                    effective);
            case "iflytek" -> new IflytekAsrProvider(
                    mapper,
                    properties.iflytek().appId(),
                    properties.iflytek().apiKey(),
                    properties.iflytek().apiSecret(),
                    effective,
                    properties.vadEosMs());
            default -> throw new IllegalArgumentException("unknown agent.asr.provider: " + properties.provider());
        };
    }

    private String provider() {
        return properties.provider() == null ? "" : properties.provider().toLowerCase(Locale.ROOT);
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
