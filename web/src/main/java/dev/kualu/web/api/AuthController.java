package dev.kualu.web.api;

import dev.kualu.web.api.ApiDtos.LoginResponse;
import dev.kualu.web.auth.JwtService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthenticationManager authenticationManager;
    private final UserDetailsService userDetailsService;
    private final JwtService jwtService;

    public AuthController(
            AuthenticationManager authenticationManager,
            UserDetailsService userDetailsService,
            JwtService jwtService) {
        this.authenticationManager = authenticationManager;
        this.userDetailsService = userDetailsService;
        this.jwtService = jwtService;
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

    public record LoginBody(
            @NotBlank String username,
            @NotBlank String password) {
    }
}
