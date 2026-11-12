package dev.kualu.web.invite;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Invite codes backing invite-based registration. */
public interface InviteCodeStore {

    void insert(InviteCode invite);

    Optional<InviteCode> find(String code);

    /** Newest first. */
    List<InviteCode> findAll();

    boolean delete(String code);

    /** Records a successful delivery. */
    void markSent(String code, Instant at);

    /**
     * Atomically claims the code for {@code username}.
     *
     * @return {@code false} when the code is already used (the claim lost a race)
     */
    boolean markUsed(String code, String username, Instant at);
}
