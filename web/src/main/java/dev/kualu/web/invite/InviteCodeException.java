package dev.kualu.web.invite;

/** An invite-code lifecycle violation (unknown, used, expired, duplicate). */
public class InviteCodeException extends RuntimeException {

    public InviteCodeException(String message) {
        super(message);
    }
}
