package dev.kualu.web.chat;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

public class SessionNotFoundException extends ResponseStatusException {

    public SessionNotFoundException() {
        super(HttpStatus.NOT_FOUND, "session not found");
    }
}
