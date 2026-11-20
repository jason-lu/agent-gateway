package dev.kualu.web.auth;

import dev.kualu.web.email.EmailSendException;
import dev.kualu.web.email.EmailService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;

/**
 * Forgot-password flow: mails a single-use, short-lived reset token to the
 * address bound to the account (invite registration copies it from the invite
 * code), then exchanges the token for a new password. A new request drops the
 * outstanding tokens for that account, and a successful reset revokes every
 * login session.
 */
public class PasswordResetService {

    private static final Logger log = LoggerFactory.getLogger(PasswordResetService.class);

    private static final Duration TOKEN_TTL = Duration.ofMinutes(30);
    private static final DateTimeFormatter EXPIRY_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    private static final SecureRandom RANDOM = new SecureRandom();

    private final UserAccountStore accounts;
    private final UserPasswordStore passwords;
    private final PasswordResetTokenStore tokens;
    private final RefreshTokenStore sessions;
    private final EmailService emailService;
    private final String resetBaseUrl;

    public PasswordResetService(
            UserAccountStore accounts,
            UserPasswordStore passwords,
            PasswordResetTokenStore tokens,
            RefreshTokenStore sessions,
            EmailService emailService,
            String resetBaseUrl) {
        this.accounts = accounts;
        this.passwords = passwords;
        this.tokens = tokens;
        this.sessions = sessions;
        this.emailService = emailService;
        this.resetBaseUrl = resetBaseUrl == null ? "" : resetBaseUrl.trim();
    }

    /**
     * Mails a reset token to the account's bound address. Unknown accounts and
     * accounts without a bound address return silently — callers must not be able
     * to tell them apart from a delivered mail — and a send failure is logged
     * rather than surfaced, for the same reason.
     *
     * @return the raw token placed in the mail; never expose it in an API response
     */
    public Optional<String> requestReset(String username) {
        Optional<String> email = accounts.findEmail(username);
        if (email.isEmpty()) {
            log.info("password-reset: no bound address for '{}'; ignoring", username);
            return Optional.empty();
        }
        Instant now = Instant.now();
        Instant expiresAt = now.plus(TOKEN_TTL);
        String raw = newRawToken();
        tokens.deleteAllFor(username);
        tokens.insert(new PasswordResetTokenStore.Token(hash(raw), username, now, expiresAt, null));
        try {
            emailService.send(email.get(), "重置密码", resetMail(raw, expiresAt));
        } catch (EmailSendException ex) {
            log.error("password-reset: failed to mail the token to {} for '{}'", email.get(), username, ex);
        }
        return Optional.of(raw);
    }

    /**
     * Replaces the password behind {@code rawToken} and kills every login session
     * of that account.
     *
     * @param encodedPassword the already-encoded new password hash
     * @throws PasswordResetException when the token is unknown, used or expired
     */
    public void resetPassword(String rawToken, String encodedPassword) {
        if (rawToken == null || rawToken.isBlank()) {
            throw new PasswordResetException("重置链接无效或已过期");
        }
        Instant now = Instant.now();
        PasswordResetTokenStore.Token token = tokens.find(hash(rawToken))
                .orElseThrow(() -> new PasswordResetException("重置链接无效或已过期"));
        if (token.used()) {
            throw new PasswordResetException("重置链接已使用，请重新申请");
        }
        if (token.expired(now)) {
            throw new PasswordResetException("重置链接已过期，请重新申请");
        }
        if (!tokens.markUsed(token.tokenHash(), now)) {
            throw new PasswordResetException("重置链接已使用，请重新申请");
        }
        passwords.updatePassword(token.username(), encodedPassword);
        sessions.deleteAllFor(token.username());
        log.info("password-reset: password replaced for '{}', all sessions revoked", token.username());
    }

    /** The reset page link when the deployment knows its public URL; the token is always shown for manual entry. */
    private String resetMail(String raw, Instant expiresAt) {
        StringBuilder body = new StringBuilder();
        body.append("<p>您好，</p>")
                .append("<p>我们收到了您账号的重置密码请求。</p>");
        if (!resetBaseUrl.isEmpty()) {
            String link = resetBaseUrl.replaceAll("/+$", "") + "/?reset=" + raw;
            body.append("<p><a href=\"").append(link).append("\">点此重置密码</a>。</p>")
                    .append("<p>如果链接无法点击，请打开控制台的重置页，输入下面的验证码：</p>");
        } else {
            body.append("<p>请打开控制台的重置页，输入下面的验证码：</p>");
        }
        body.append("<p style=\"font-size:20px;font-weight:700;letter-spacing:3px;margin:18px 0\">")
                .append(raw)
                .append("</p>")
                .append("<p>验证码 ").append(EXPIRY_FORMAT.format(expiresAt))
                .append(" 前有效，仅可使用一次。</p>")
                .append("<p>若非本人操作，可忽略本邮件，账号密码不会有任何变化。</p>");
        return body.toString();
    }

    private String newRawToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    static String hash(String rawToken) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(rawToken.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }
}
