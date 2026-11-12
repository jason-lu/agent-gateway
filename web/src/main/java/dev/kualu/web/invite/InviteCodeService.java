package dev.kualu.web.invite;

import dev.kualu.web.email.EmailSendException;
import dev.kualu.web.email.EmailService;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Invite-code lifecycle: admins mint codes for target addresses, the code gets
 * emailed, and registration consumes it exactly once. Codes use an alphabet
 * without look-alike glyphs so they survive copy-paste and retyping.
 */
public class InviteCodeService {

    private static final char[] ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789".toCharArray();
    private static final int CODE_LENGTH = 8;
    private static final int DEFAULT_VALID_DAYS = 7;
    private static final DateTimeFormatter EXPIRY_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    private static final SecureRandom RANDOM = new SecureRandom();

    private final InviteCodeStore store;
    private final EmailService emailService;

    public InviteCodeService(InviteCodeStore store, EmailService emailService) {
        this.store = store;
        this.emailService = emailService;
    }

    /** @param validDays null uses the default window */
    public InviteCode create(String email, String note, String createdBy, Integer validDays) {
        if (email == null || email.isBlank()) {
            throw new InviteCodeException("收件邮箱不能为空");
        }
        int days = validDays == null ? DEFAULT_VALID_DAYS : validDays;
        if (days < 1 || days > 365) {
            throw new InviteCodeException("有效期需在 1–365 天之间");
        }
        Instant now = Instant.now();
        InviteCode invite = new InviteCode(
                newCode(), email.trim(), note == null ? "" : note.trim(), createdBy, now,
                now.plusSeconds(days * 24L * 3600L), null, null, null);
        store.insert(invite);
        return invite;
    }

    public List<InviteCode> list() {
        return store.findAll();
    }

    public void delete(String code) {
        if (!store.delete(code)) {
            throw new InviteCodeException("邀请码不存在：" + code);
        }
    }

    /** Emails the code to its address and records the delivery time. */
    public InviteCode send(String code) {
        InviteCode invite = store.find(code)
                .orElseThrow(() -> new InviteCodeException("邀请码不存在：" + code));
        if (invite.used()) {
            throw new InviteCodeException("邀请码已被使用，无法再发送");
        }
        try {
            emailService.send(invite.email(), "注册邀请码", inviteMail(invite));
        } catch (EmailSendException ex) {
            throw ex;
        }
        Instant now = Instant.now();
        store.markSent(invite.code(), now);
        return withSent(invite, now);
    }

    /**
     * Validates and claims the code for {@code username}.
     *
     * @throws InviteCodeException when unknown, used or expired
     */
    public InviteCode consume(String code, String username) {
        InviteCode invite = store.find(code == null ? "" : code.trim())
                .orElseThrow(() -> new InviteCodeException("邀请码无效"));
        Instant now = Instant.now();
        if (invite.used()) {
            throw new InviteCodeException("邀请码已被使用");
        }
        if (invite.expired(now)) {
            throw new InviteCodeException("邀请码已过期");
        }
        if (!store.markUsed(invite.code(), username, now)) {
            throw new InviteCodeException("邀请码已被使用");
        }
        return invite;
    }

    private String newCode() {
        for (int attempt = 0; attempt < 5; attempt++) {
            StringBuilder code = new StringBuilder(CODE_LENGTH);
            for (int i = 0; i < CODE_LENGTH; i++) {
                code.append(ALPHABET[RANDOM.nextInt(ALPHABET.length)]);
            }
            String candidate = code.toString();
            if (store.find(candidate).isEmpty()) {
                return candidate;
            }
        }
        throw new IllegalStateException("could not allocate a unique invite code");
    }

    private static InviteCode withSent(InviteCode invite, Instant at) {
        return new InviteCode(
                invite.code(), invite.email(), invite.note(), invite.createdBy(), invite.createdAt(),
                invite.expiresAt(), at, invite.usedBy(), invite.usedAt());
    }

    private static String inviteMail(InviteCode invite) {
        StringBuilder body = new StringBuilder();
        body.append("<p>您好，</p>")
                .append("<p>您收到一枚注册邀请码：</p>")
                .append("<p style=\"font-size:26px;font-weight:700;letter-spacing:6px;margin:18px 0\">")
                .append(invite.code())
                .append("</p>")
                .append("<p>打开控制台注册页，输入该邀请码并设置用户名与密码即可完成注册。</p>")
                .append("<p>有效期至 ").append(EXPIRY_FORMAT.format(invite.expiresAt()))
                .append("，仅可使用一次。</p>");
        if (invite.note() != null && !invite.note().isBlank()) {
            body.append("<p>备注：").append(invite.note()).append("</p>");
        }
        return body.toString();
    }
}
