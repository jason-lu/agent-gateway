package dev.kualu.web.api;

import dev.kualu.web.api.ApiDtos.InvitePreviewResponse;
import dev.kualu.web.api.ApiDtos.LoginResponse;
import dev.kualu.web.auth.PasswordResetException;
import dev.kualu.web.auth.PasswordResetService;
import dev.kualu.web.auth.RefreshTokenService;
import dev.kualu.web.auth.UserAccountStore;
import dev.kualu.web.auth.UserPasswordStore;
import dev.kualu.web.invite.InviteCode;
import dev.kualu.web.invite.InviteCodeException;
import dev.kualu.web.invite.InviteCodeService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthenticationManager authenticationManager;
    private final UserDetailsService userDetailsService;
    private final PasswordEncoder passwordEncoder;
    private final UserPasswordStore passwordStore;
    private final RefreshTokenService refreshTokenService;
    private final UserAccountStore accountStore;
    private final InviteCodeService inviteCodes;
    private final PasswordResetService passwordResets;

    public AuthController(
            AuthenticationManager authenticationManager,
            UserDetailsService userDetailsService,
            PasswordEncoder passwordEncoder,
            UserPasswordStore passwordStore,
            RefreshTokenService refreshTokenService,
            UserAccountStore accountStore,
            InviteCodeService inviteCodes,
            PasswordResetService passwordResets) {
        this.authenticationManager = authenticationManager;
        this.userDetailsService = userDetailsService;
        this.passwordEncoder = passwordEncoder;
        this.passwordStore = passwordStore;
        this.refreshTokenService = refreshTokenService;
        this.accountStore = accountStore;
        this.inviteCodes = inviteCodes;
        this.passwordResets = passwordResets;
    }

    @PostMapping("/login")
    public LoginResponse login(@Valid @RequestBody LoginBody body) {
        try {
            authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(body.username(), body.password()));
        } catch (AuthenticationException ex) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid credentials");
        }
        return issue(userDetailsService.loadUserByUsername(body.username()));
    }

    /**
     * Invite-based registration: the code is claimed exactly once, its email is
     * bound to the new account (the forgot-password flow mails there), and the
     * account starts with the {@code USER} role. The response is a full login
     * pair, so the client can sign in immediately.
     */
    @PostMapping("/register")
    public LoginResponse register(@Valid @RequestBody RegisterBody body) {
        String username = body.username().trim();
        if (accountStore.exists(username)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "用户名已存在");
        }
        InviteCode invite;
        try {
            invite = inviteCodes.consume(body.inviteCode(), username);
        } catch (InviteCodeException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage());
        }
        try {
            accountStore.create(username, passwordEncoder.encode(body.password()), invite.email());
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "用户名已存在");
        }
        return issue(userDetailsService.loadUserByUsername(username));
    }

    /**
     * The address an unused code would bind at registration, so the register form
     * can show it before the code is consumed. Unknown, used and expired codes
     * answer with the same message {@link #register} would return.
     */
    @PostMapping("/invite-preview")
    public InvitePreviewResponse invitePreview(@Valid @RequestBody InvitePreviewBody body) {
        try {
            return new InvitePreviewResponse(inviteCodes.preview(body.inviteCode()).email());
        } catch (InviteCodeException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage());
        }
    }

    /**
     * Exchanges a refresh token for a new access/refresh pair (rotation). The
     * session expires only after {@code agent.security.refresh-idle-timeout}
     * without activity; this call itself counts as activity.
     */
    @PostMapping("/refresh")
    public LoginResponse refresh(@Valid @RequestBody RefreshRequest body) {
        return refreshTokenService.refresh(body.refreshToken())
                .map(this::toResponse)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid refresh token"));
    }

    /** Revokes the session behind the refresh token. Idempotent; 204 either way. */
    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(@Valid @RequestBody LogoutRequest body) {
        refreshTokenService.logout(body.refreshToken());
    }

    /**
     * Replaces the caller's own password. The current session keeps working
     * until it goes idle or is revoked with /auth/logout; only future logins
     * use the new password.
     */
    @PostMapping("/change-password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void changePassword(Authentication authentication, @Valid @RequestBody ChangePasswordBody body) {
        String username = authentication.getName();
        UserDetails user = userDetailsService.loadUserByUsername(username);
        if (!passwordEncoder.matches(body.currentPassword(), user.getPassword())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "current password is incorrect");
        }
        passwordStore.updatePassword(username, passwordEncoder.encode(body.newPassword()));
    }

    /**
     * Starts the forgot-password flow: mails a single-use reset token to the
     * address bound to the account. Unknown accounts and accounts without a bound
     * address get the same {@code 204} as a delivered mail, so the endpoint cannot
     * be used to probe which usernames exist.
     */
    @PostMapping("/forgot-password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void forgotPassword(@Valid @RequestBody ForgotPasswordBody body) {
        passwordResets.requestReset(body.username().trim());
    }

    /** Replaces the password behind a reset token and revokes every login session. */
    @PostMapping("/reset-password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void resetPassword(@Valid @RequestBody ResetPasswordBody body) {
        try {
            passwordResets.resetPassword(body.token().trim(), passwordEncoder.encode(body.newPassword()));
        } catch (PasswordResetException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage());
        }
    }

    private LoginResponse issue(UserDetails user) {
        List<String> roles = user.getAuthorities().stream()
                .map(authority -> authority.getAuthority().replaceFirst("^ROLE_", ""))
                .toList();
        return toResponse(refreshTokenService.issue(user.getUsername(), roles));
    }

    private LoginResponse toResponse(RefreshTokenService.Issued issued) {
        return new LoginResponse(
                issued.accessToken(), "Bearer", issued.expiresInSeconds(), issued.refreshToken());
    }

    public record LoginBody(
            @NotBlank String username,
            @NotBlank String password) {
    }

    public record RegisterBody(
            @NotBlank String inviteCode,
            @NotBlank @Size(min = 2, max = 64) String username,
            @NotBlank @Size(min = 8, max = 128) String password) {
    }

    public record InvitePreviewBody(@NotBlank String inviteCode) {
    }

    public record ForgotPasswordBody(@NotBlank String username) {
    }

    public record ResetPasswordBody(
            @NotBlank String token,
            @NotBlank @Size(min = 8, max = 128) String newPassword) {
    }

    public record RefreshRequest(
            @NotBlank String refreshToken) {
    }

    public record LogoutRequest(
            @NotBlank String refreshToken) {
    }

    public record ChangePasswordBody(
            @NotBlank String currentPassword,
            @NotBlank @Size(min = 8, max = 128) String newPassword) {
    }
}
