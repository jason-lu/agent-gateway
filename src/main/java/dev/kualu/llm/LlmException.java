package dev.kualu.llm;

/** Thrown when an LLM request fails or the response cannot be decoded. */
public class LlmException extends RuntimeException {

    private final int statusCode;
    private final String responseBody;

    public LlmException(String message) {
        this(message, -1, null, null);
    }

    public LlmException(String message, Throwable cause) {
        this(message, -1, null, cause);
    }

    public LlmException(String message, int statusCode, String responseBody) {
        this(message, statusCode, responseBody, null);
    }

    private LlmException(String message, int statusCode, String responseBody, Throwable cause) {
        super(message, cause);
        this.statusCode = statusCode;
        this.responseBody = responseBody;
    }

    public int statusCode() {
        return statusCode;
    }

    public String responseBody() {
        return responseBody;
    }
}
