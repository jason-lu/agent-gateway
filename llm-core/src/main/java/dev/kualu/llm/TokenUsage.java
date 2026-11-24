package dev.kualu.llm;

/**
 * Token accounting for one model call, as reported by an OpenAI-style upstream
 * ({@code usage} on the response / final stream chunk).
 *
 * <p>{@code null} means "not reported" — a provider may omit the block entirely.
 * {@code promptTokens} is the whole request context (it grows every round of a
 * tool-call loop), so summing it across rounds counts billed input, while the
 * last round's value is the context size at end of turn.
 *
 * @param promptTokens     input tokens billed for the call
 * @param completionTokens output tokens produced, reasoning included
 * @param totalTokens      {@code promptTokens + completionTokens} as reported
 * @param reasoningTokens  thinking tokens inside {@code completionTokens} ({@code completion_tokens_details})
 * @param cachedTokens     prompt tokens served from cache ({@code prompt_tokens_details})
 */
public record TokenUsage(int promptTokens, int completionTokens, int totalTokens,
                         int reasoningTokens, int cachedTokens) {

    /** Adds two usages field by field (tool-loop rounds roll up into a turn total). */
    public TokenUsage plus(TokenUsage other) {
        if (other == null) {
            return this;
        }
        return new TokenUsage(
                promptTokens + other.promptTokens,
                completionTokens + other.completionTokens,
                totalTokens + other.totalTokens,
                reasoningTokens + other.reasoningTokens,
                cachedTokens + other.cachedTokens);
    }

    /** True when the upstream reported nothing but zeroes. */
    public boolean isEmpty() {
        return promptTokens == 0 && completionTokens == 0 && totalTokens == 0
                && reasoningTokens == 0 && cachedTokens == 0;
    }
}
