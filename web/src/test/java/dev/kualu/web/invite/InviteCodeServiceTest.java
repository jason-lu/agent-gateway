package dev.kualu.web.invite;

import dev.kualu.web.email.EmailSendException;
import dev.kualu.web.email.EmailService;
import dev.kualu.web.email.InMemoryEmailSettingsStore;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Lifecycle rules that do not need a web layer or a real SMTP connection. */
class InviteCodeServiceTest {

    private final InMemoryInviteCodeStore store = new InMemoryInviteCodeStore();
    private final InviteCodeService service =
            new InviteCodeService(store, new EmailService(new InMemoryEmailSettingsStore()));

    @Test
    void codesAreEightLookalikeFreeCharacters() {
        InviteCode invite = service.create("newbie@example.com", null, "admin", null);
        assertThat(invite.code()).hasSize(8).matches("[A-HJ-NP-Z2-9]+");
        assertThat(invite.expiresAt()).isAfter(Instant.now().plusSeconds(6L * 24 * 3600));
    }

    @Test
    void createValidatesEmailAndWindow() {
        assertThatThrownBy(() -> service.create(" ", null, "admin", null))
                .isInstanceOf(InviteCodeException.class);
        assertThatThrownBy(() -> service.create("a@example.com", null, "admin", 0))
                .isInstanceOf(InviteCodeException.class);
        assertThatThrownBy(() -> service.create("a@example.com", null, "admin", 400))
                .isInstanceOf(InviteCodeException.class);
    }

    @Test
    void consumeClaimsOnce() {
        InviteCode invite = service.create("newbie@example.com", null, "admin", 7);
        service.consume(invite.code(), "newbie");
        assertThat(store.find(invite.code()).orElseThrow().usedBy()).isEqualTo("newbie");
        assertThatThrownBy(() -> service.consume(invite.code(), "impostor"))
                .isInstanceOf(InviteCodeException.class)
                .hasMessageContaining("已被使用");
    }

    @Test
    void consumeRejectsUnknownAndExpired() {
        assertThatThrownBy(() -> service.consume("NOWHERE1", "x"))
                .isInstanceOf(InviteCodeException.class);

        Instant now = Instant.now();
        store.insert(new InviteCode(
                "EXPIRED1", "old@example.com", "", "admin", now.minusSeconds(3600),
                now.minusSeconds(1), null, null, null));
        assertThatThrownBy(() -> service.consume("EXPIRED1", "x"))
                .isInstanceOf(InviteCodeException.class)
                .hasMessageContaining("已过期");
    }

    @Test
    void sendRequiresConfiguredMailbox() {
        InviteCode invite = service.create("newbie@example.com", null, "admin", 7);
        assertThatThrownBy(() -> service.send(invite.code()))
                .isInstanceOf(EmailSendException.class);
        // A failed delivery does not stamp sent_at.
        assertThat(store.find(invite.code()).orElseThrow().sentAt()).isNull();
    }

    @Test
    void sendRefusesUsedCodes() {
        InviteCode invite = service.create("newbie@example.com", null, "admin", 7);
        service.consume(invite.code(), "newbie");
        assertThatThrownBy(() -> service.send(invite.code()))
                .isInstanceOf(InviteCodeException.class);
    }

    @Test
    void deleteRemovesTheCode() {
        InviteCode invite = service.create("newbie@example.com", null, "admin", 7);
        service.delete(invite.code());
        assertThat(store.find(invite.code())).isEmpty();
        assertThatThrownBy(() -> service.delete(invite.code()))
                .isInstanceOf(InviteCodeException.class);
    }
}
