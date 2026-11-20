package dev.kualu.web.auth;

/** A password-reset token lifecycle violation (unknown, used, expired). */
public class PasswordResetException extends RuntimeException {

    public PasswordResetException(String message) {
        super(message);
    }
}
