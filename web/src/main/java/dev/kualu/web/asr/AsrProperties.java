package dev.kualu.web.asr;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Streaming speech-to-text config for the {@code /api/v1/asr/stream} endpoint.
 * Defaults live in {@code application.yml}; {@code mock} exists so development
 * and tests work without cloud credentials.
 */
@ConfigurationProperties(prefix = "agent.asr")
public record AsrProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("mimo") String provider,
        @DefaultValue("zh-CN") String language,
        @DefaultValue("1000") long vadEosMs,
        Mimo mimo,
        Iflytek iflytek) {

    public AsrProperties {
        mimo = mimo == null ? new Mimo(null, null, null) : mimo;
        iflytek = iflytek == null ? new Iflytek(null, null, null) : iflytek;
    }

    /**
     * Xiaomi MiMo ASR ({@code chat.completions} with an {@code input_audio} part);
     * blank api key means "not configured" (the endpoint then refuses).
     */
    public record Mimo(String apiKey, String baseUrl, String model) {
    }

    /** 讯飞 IAT credentials; blank values mean "not configured" (the endpoint then refuses). */
    public record Iflytek(String appId, String apiKey, String apiSecret) {
    }
}
