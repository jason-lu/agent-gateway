package dev.kualu.web.chat;

public class UpstreamLlmException extends RuntimeException {

    public UpstreamLlmException(String message, Throwable cause) {
        super(message, cause);
    }
}
