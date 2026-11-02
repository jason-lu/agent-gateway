package dev.kualu.web.api;

import dev.kualu.web.api.ApiDtos.LoginResponse;
import dev.kualu.web.auth.JwtService;
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
    private final JwtService jwtService;
    private final PasswordEncoder passwordEncoder;
    private final UserPasswordStore passwordStore;

    public AuthController(
            AuthenticationManager authenticationManager,
            UserDetailsService userDetailsService,
            JwtService jwtService,
            PasswordEncoder passwordEncoder,
            UserPasswordStore passwordStore) {
        this.authenticationManager = authenticationManager;
        this.userDetailsService = userDetailsService;
        this.jwtService = jwtService;
        this.passwordEncoder = passwordEncoder;
        this.passwordStore = passwordStore;
    }

    @PostMapping("/login")
    public LoginResponse login(@Valid @RequestBody LoginBody body) {
        try {
            authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(body.username(), body.password()));
        } catch (AuthenticationException ex) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid credentials");
        }
        UserDetails user = userDetailsService.loadUserByUsername(body.username());
        List<String> roles = user.getAuthorities().stream()
                .map(authority -> authority.getAuthority().replaceFirst("^ROLE_", ""))
                .toList();
        String token = jwtService.issueToken(user.getUsername(), roles);
        return new LoginResponse(token, "Bearer", jwtService.expiresInSeconds());
    }

    /**
     * Replaces the caller's own password. Issued JWTs are stateless and stay valid
     * until expiry — only future logins use the new password.
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

    public record LoginBody(
            @NotBlank String username,
            @NotBlank String password) {
    }

    public record ChangePasswordBody(
            @NotBlank String currentPassword,
            @NotBlank @Size(min = 8, max = 128) String newPassword) {
    }
}
