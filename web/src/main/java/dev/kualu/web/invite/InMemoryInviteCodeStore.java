package dev.kualu.web.invite;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Process-local codes; only registered when {@code agent.store=memory}. */
public class InMemoryInviteCodeStore implements InviteCodeStore {

    private final Map<String, InviteCode> codes = new LinkedHashMap<>();

    @Override
    public synchronized void insert(InviteCode invite) {
        if (codes.putIfAbsent(invite.code(), invite) != null) {
            throw new IllegalArgumentException("duplicate invite code: " + invite.code());
        }
    }

    @Override
    public synchronized Optional<InviteCode> find(String code) {
        return Optional.ofNullable(codes.get(code));
    }

    @Override
    public synchronized List<InviteCode> findAll() {
        List<InviteCode> all = new ArrayList<>(codes.values());
        all.sort(Comparator.comparing(InviteCode::createdAt).reversed());
        return List.copyOf(all);
    }

    @Override
    public synchronized boolean delete(String code) {
        return codes.remove(code) != null;
    }

    @Override
    public synchronized void markSent(String code, Instant at) {
        InviteCode existing = codes.get(code);
        if (existing != null) {
            codes.put(code, withSent(existing, at));
        }
    }

    @Override
    public synchronized boolean markUsed(String code, String username, Instant at) {
        InviteCode existing = codes.get(code);
        if (existing == null || existing.used()) {
            return false;
        }
        codes.put(code, withUsed(existing, username, at));
        return true;
    }

    private static InviteCode withSent(InviteCode invite, Instant at) {
        return new InviteCode(
                invite.code(), invite.email(), invite.note(), invite.createdBy(), invite.createdAt(),
                invite.expiresAt(), at, invite.usedBy(), invite.usedAt());
    }

    private static InviteCode withUsed(InviteCode invite, String username, Instant at) {
        return new InviteCode(
                invite.code(), invite.email(), invite.note(), invite.createdBy(), invite.createdAt(),
                invite.expiresAt(), invite.sentAt(), username, at);
    }
}
