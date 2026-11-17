package dev.kualu.llm.moderation;

/**
 * The standard user-facing strings. Deliberately generic: the client never
 * learns which rule or which term fired (that would help probing).
 */
public final class ModerationMessages {

    /** Error code in the HTTP error body. */
    public static final String ERROR_CODE = "content_blocked";

    /** HTTP/SSE message shown to the user when a turn is refused. */
    public static final String BLOCK_MESSAGE = "内容不符合平台规范，请调整后重试";

    /**
     * What replaces a blocked user turn in the conversation history, so the
     * turn structure stays intact and the model never sees the raw text.
     */
    public static final String BLOCKED_USER_PLACEHOLDER = "【该消息未通过内容安全检查】";

    /** What replaces a blocked model reply in history and in the response. */
    public static final String OUTPUT_REFUSAL = "该内容未通过平台安全检查，已停止输出。请调整表述后重试。";

    private ModerationMessages() {
    }
}
