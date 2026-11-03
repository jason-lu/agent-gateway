package dev.kualu.web.api;

import dev.kualu.web.api.ApiDtos.LoginResponse;
import dev.kualu.web.auth.RefreshTokenService;
import dev.kualu.web.auth.UserPasswordStore;
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

    public AuthController(
            AuthenticationManager authenticationManager,
            UserDetailsService userDetailsService,
            PasswordEncoder passwordEncoder,
            UserPasswordStore passwordStore,
            RefreshTokenService refreshTokenService) {
        this.authenticationManager = authenticationManager;
        this.userDetailsService = userDetailsService;
        this.passwordEncoder = passwordEncoder;
        this.passwordStore = passwordStore;
        this.refreshTokenService = refreshTokenService;
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
