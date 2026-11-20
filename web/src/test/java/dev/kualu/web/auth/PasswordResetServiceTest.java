package dev.kualu.web.auth;

import dev.kualu.web.email.EmailService;
import dev.kualu.web.email.InMemoryEmailSettingsStore;
import org.junit.jupiter.api.Test;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Forgot-password rules that do not need a web layer or a real SMTP connection. */
class PasswordResetServiceTest {

    private static final class CapturedMail {
        final String to;
        final String body;

        CapturedMail(String to, String body) {
            this.to = to;
            this.body = body;
        }
    }

    private static final class CapturingEmailService extends EmailService {
        final List<CapturedMail> sent = new ArrayList<>();

        CapturingEmailService() {
            super(new InMemoryEmailSettingsStore());
        }

        @Override
        public void send(String to, String subject, String htmlBody) {
            sent.add(new CapturedMail(to, htmlBody));
        }
    }

    private final InMemoryUserDetailsManager users = new InMemoryUserDetailsManager();
    private final UserAccountStore accounts = new InMemoryUserAccountStore(users);
    private final UserPasswordStore passwords =
            (username, encoded) -> users.updatePassword(users.loadUserByUsername(username), encoded);
    private final InMemoryPasswordResetTokenStore tokens = new InMemoryPasswordResetTokenStore();
    private final InMemoryRefreshTokenStore sessions = new InMemoryRefreshTokenStore();
    private final CapturingEmailService mail = new CapturingEmailService();
    private final PasswordResetService service =
            new PasswordResetService(accounts, passwords, tokens, sessions, mail, "https://gw.example.com/");

    @Test
    void requestResetMailsTheBoundAddressWithALink() {
        accounts.create("carol", "{noop}password123", "carol@example.com");

        Optional<String> raw = service.requestReset("carol");

        assertThat(raw).isPresent();
        assertThat(mail.sent).hasSize(1);
        assertThat(mail.sent.get(0).to).isEqualTo("carol@example.com");
        assertThat(mail.sent.get(0).body).contains(raw.orElseThrow());
        assertThat(mail.sent.get(0).body).contains("https://gw.example.com/?reset=" + raw.orElseThrow());
        assertThat(tokens.find(PasswordResetService.hash(raw.orElseThrow()))).isPresent();
    }

    @Test
    void requestResetIsSilentForUnknownAndUnboundAccounts() {
        accounts.create("dave", "{noop}password123", null);

        assertThat(service.requestReset("ghost")).isEmpty();
        assertThat(service.requestReset("dave")).isEmpty();
        assertThat(mail.sent).isEmpty();
    }

    @Test
    void newRequestInvalidatesThePreviousToken() {
        accounts.create("carol", "{noop}password123", "carol@example.com");
        String first = service.requestReset("carol").orElseThrow();
        String second = service.requestReset("carol").orElseThrow();

        assertThatThrownBy(() -> service.resetPassword(first, "{noop}new-password1"))
                .isInstanceOf(PasswordResetException.class);
        service.resetPassword(second, "{noop}new-password1");
    }

    @Test
    void resetPasswordReplacesPasswordAndRevokesSessions() {
        accounts.create("carol", "{noop}password123", "carol@example.com");
        sessions.insert(new RefreshTokenStore.Entry("s1", "carol", Instant.now(), Instant.now()), "hash-1");
        sessions.insert(new RefreshTokenStore.Entry("s2", "erin", Instant.now(), Instant.now()), "hash-2");
        String raw = service.requestReset("carol").orElseThrow();

        service.resetPassword(raw, "{noop}new-password1");

        assertThat(users.loadUserByUsername("carol").getPassword()).isEqualTo("{noop}new-password1");
        assertThat(sessions.findById("s1")).isEmpty();
        assertThat(sessions.findById("s2")).isPresent();
    }

    @Test
    void resetPasswordRejectsUsedUnknownAndExpiredTokens() {
        accounts.create("carol", "{noop}password123", "carol@example.com");
        String raw = service.requestReset("carol").orElseThrow();

        assertThatThrownBy(() -> service.resetPassword("", "{noop}new-password1"))
                .isInstanceOf(PasswordResetException.class);
        assertThatThrownBy(() -> service.resetPassword("NOWHERE", "{noop}new-password1"))
                .isInstanceOf(PasswordResetException.class);

        service.resetPassword(raw, "{noop}new-password1");
        assertThatThrownBy(() -> service.resetPassword(raw, "{noop}again2222"))
                .isInstanceOf(PasswordResetException.class)
                .hasMessageContaining("已使用");

        Instant past = Instant.now().minusSeconds(3600);
        String stale = "stale-token";
        tokens.insert(new PasswordResetTokenStore.Token(
                PasswordResetService.hash(stale), "carol", past, past.plusSeconds(60), null));
        assertThatThrownBy(() -> service.resetPassword(stale, "{noop}again2222"))
                .isInstanceOf(PasswordResetException.class)
                .hasMessageContaining("已过期");
    }
}
