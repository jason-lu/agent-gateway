package dev.kualu.web.api;

import dev.kualu.web.api.ApiDtos.EmailSettingsBody;
import dev.kualu.web.api.ApiDtos.EmailSettingsResponse;
import dev.kualu.web.api.ApiDtos.EmailTestBody;
import dev.kualu.web.api.ApiDtos.InviteCodeBody;
import dev.kualu.web.api.ApiDtos.InviteCodeResponse;
import dev.kualu.web.email.EmailSendException;
import dev.kualu.web.email.EmailService;
import dev.kualu.web.email.EmailSettings;
import dev.kualu.web.email.EmailSettingsStore;
import dev.kualu.web.invite.InviteCode;
import dev.kualu.web.invite.InviteCodeException;
import dev.kualu.web.invite.InviteCodeService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;

/**
 * Outbound mail (SMTP) configuration plus the invite-code queue that uses it.
 * The password is masked like every other secret here: {@code SettingsController.MASK}
 * round-trips as "keep the stored value".
 */
@RestController
@RequestMapping("/api/v1/admin")
public class AdminEmailController {

    /** Shipped Aliyun enterprise-mail defaults (implicit SSL on 465). */
    private static final EmailSettings DEFAULTS =
            new EmailSettings("smtp.qiye.aliyun.com", 465, "", "", "", true);

    private final EmailSettingsStore settingsStore;
    private final EmailService emailService;
    private final InviteCodeService inviteCodes;

    public AdminEmailController(
            EmailSettingsStore settingsStore, EmailService emailService, InviteCodeService inviteCodes) {
        this.settingsStore = settingsStore;
        this.emailService = emailService;
        this.inviteCodes = inviteCodes;
    }

    @GetMapping("/email-settings")
    @PreAuthorize("hasRole('ADMIN')")
    public EmailSettingsResponse emailSettings() {
        return toResponse(settingsStore.find().orElse(DEFAULTS));
    }

    @PutMapping("/email-settings")
    @PreAuthorize("hasRole('ADMIN')")
    public EmailSettingsResponse updateEmailSettings(@RequestBody EmailSettingsBody body) {
        if (body.host() == null || body.host().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "服务器地址不能为空");
        }
        if (body.port() < 1 || body.port() > 65535) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "端口需在 1–65535 之间");
        }
        if (body.username() == null || body.username().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "邮箱用户名不能为空");
        }
        String storedPassword = settingsStore.find().map(EmailSettings::password).orElse("");
        EmailSettings settings = new EmailSettings(
                body.host().trim(),
                body.port(),
                body.username().trim(),
                mergeSecret(body.password(), storedPassword),
                body.fromAddress() == null ? "" : body.fromAddress().trim(),
                body.useSsl());
        settingsStore.save(settings);
        return toResponse(settings);
    }

    /** Proves the stored account by delivering a short test mail. */
    @PostMapping("/email-settings/test")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasRole('ADMIN')")
    public void testEmailSettings(@Valid @RequestBody EmailTestBody body) {
        String to = body.to() == null ? "" : body.to().trim();
        if (to.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请填写测试收件地址");
        }
        try {
            emailService.send(to, "测试邮件", "<p>这是一封测试邮件，SMTP 配置工作正常。</p>");
        } catch (EmailSendException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, ex.getMessage());
        }
    }

    @GetMapping("/invite-codes")
    @PreAuthorize("hasRole('ADMIN')")
    public List<InviteCodeResponse> inviteCodes() {
        return inviteCodes.list().stream().map(AdminEmailController::toResponse).toList();
    }

    @PostMapping("/invite-codes")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('ADMIN')")
    public InviteCodeResponse createInviteCode(Authentication authentication, @Valid @RequestBody InviteCodeBody body) {
        try {
            return toResponse(inviteCodes.create(
                    body.email(), body.note(), authentication.getName(), body.validDays()));
        } catch (InviteCodeException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage());
        }
    }

    /** Emails the code to its target address and stamps {@code sentAt}. */
    @PostMapping("/invite-codes/{code}/send")
    @PreAuthorize("hasRole('ADMIN')")
    public InviteCodeResponse sendInviteCode(@PathVariable String code) {
        try {
            return toResponse(inviteCodes.send(code));
        } catch (EmailSendException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, ex.getMessage());
        } catch (InviteCodeException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage());
        }
    }

    @DeleteMapping("/invite-codes/{code}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasRole('ADMIN')")
    public void deleteInviteCode(@PathVariable String code) {
        try {
            inviteCodes.delete(code);
        } catch (InviteCodeException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, ex.getMessage());
        }
    }

    /** The mask (or a blank, or {@code null}) keeps the stored password; anything else replaces it. */
    private static String mergeSecret(String incoming, String stored) {
        if (incoming == null || incoming.isBlank() || SettingsController.MASK.equals(incoming)) {
            return stored;
        }
        return incoming.trim();
    }

    private static EmailSettingsResponse toResponse(EmailSettings settings) {
        return new EmailSettingsResponse(
                settings.host(),
                settings.port(),
                settings.username(),
                settings.password().isEmpty() ? "" : SettingsController.MASK,
                settings.fromAddress(),
                settings.useSsl());
    }

    private static InviteCodeResponse toResponse(InviteCode invite) {
        return new InviteCodeResponse(
                invite.code(),
                invite.email(),
                invite.note(),
                invite.createdBy(),
                iso(invite.createdAt()),
                iso(invite.expiresAt()),
                iso(invite.sentAt()),
                invite.usedBy(),
                iso(invite.usedAt()));
    }

    private static String iso(Instant instant) {
        return instant == null ? null : instant.toString();
    }
}
