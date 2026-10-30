package dev.kualu.web.chat;

public class LlmNotConfiguredException extends RuntimeException {

    public LlmNotConfiguredException(String message) {
        super(message);
    }
}
