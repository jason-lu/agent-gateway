package dev.kualu.web.llm;

/**
 * Admin-configured LLM upstream overrides. Each field is independent: a
 * {@code null} or blank value means "no override" and the effective config falls
 * back to the environment ({@code LLM_BASE_URL} / {@code LLM_API_KEY} / {@code LLM_MODEL}).
 * Admin values win per field.
 */
public record AdminLlmSettings(String baseUrl, String apiKey, String model) {

    /** True when no field overrides the environment configuration. */
    public boolean isEmpty() {
        return isBlank(baseUrl) && isBlank(apiKey) && isBlank(model);
    }

    public static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
