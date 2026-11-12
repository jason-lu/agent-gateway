package dev.kualu.web.invite;

import java.time.Instant;

/**
 * One registration invite code. The code is the credential; {@code email} is
 * where it was (or will be) delivered. {@code usedBy} is null while unused.
 */
public record InviteCode(
        String code,
        String email,
        String note,
        String createdBy,
        Instant createdAt,
        Instant expiresAt,
        Instant sentAt,
        String usedBy,
        Instant usedAt) {

    public boolean used() {
        return usedBy != null;
    }

    public boolean expired(Instant now) {
        return now.isAfter(expiresAt);
    }
}
