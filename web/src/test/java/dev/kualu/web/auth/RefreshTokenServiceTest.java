package dev.kualu.web.auth;

import dev.kualu.web.config.SecurityProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class RefreshTokenServiceTest {

    private static final String SECRET = "test-secret-test-secret-test-secret-1234";

    private InMemoryRefreshTokenStore store;
    private JwtService jwtService;
    private RefreshTokenService service;

    @BeforeEach
    void setUp() {
        store = new InMemoryRefreshTokenStore();
        SecurityProperties properties =
                new SecurityProperties(SECRET, Duration.ofHours(2), Duration.ofMillis(120), List.of());
        jwtService = new JwtService(properties);
        UserDetails alice = User.withUsername("alice").password("x").roles("USER").build();
        UserDetailsService users = new UserDetailsService() {
            @Override
            public UserDetails loadUserByUsername(String username) {
                if (!username.equals("alice")) throw new UsernameNotFoundException(username);
                return alice;
            }
        };
        service = new RefreshTokenService(store, jwtService, users, properties);
    }

    @Test
    void issuedSessionIsAccepted() {
        RefreshTokenService.Issued issued = service.issue("alice", List.of("USER"));
        assertThat(issued.refreshToken()).isNotBlank();
        assertThat(service.accept(sessionIdOf(issued))).isTrue();
    }

    @Test
    void refreshRotatesTheRawTokenButKeepsTheSession() {
        RefreshTokenService.Issued issued = service.issue("alice", List.of("USER"));
        String sid = sessionIdOf(issued);

        Optional<RefreshTokenService.Issued> renewed = service.refresh(issued.refreshToken());
        assertThat(renewed).isPresent();
        assertThat(sessionIdOf(renewed.get())).isEqualTo(sid);
        assertThat(service.refresh(issued.refreshToken())).isEmpty();
        assertThat(service.refresh(renewed.get().refreshToken())).isPresent();
    }

    @Test
    void sessionGoesIdleWithoutActivity() throws Exception {
        RefreshTokenService.Issued issued = service.issue("alice", List.of("USER"));
        String sid = sessionIdOf(issued);
        Thread.sleep(200);

        assertThat(service.accept(sid)).isFalse();
        assertThat(store.findById(sid)).isEmpty();
    }

    @Test
    void activityPostponesIdleExpiry() throws Exception {
        RefreshTokenService.Issued issued = service.issue("alice", List.of("USER"));
        String sid = sessionIdOf(issued);
        Thread.sleep(80);
        assertThat(service.accept(sid)).isTrue();
        Thread.sleep(80);
        assertThat(service.accept(sid)).isTrue();
    }

    @Test
    void logoutRevokesTheSession() {
        RefreshTokenService.Issued issued = service.issue("alice", List.of("USER"));
        String sid = sessionIdOf(issued);

        service.logout(issued.refreshToken());

        assertThat(service.accept(sid)).isFalse();
        assertThat(service.refresh(issued.refreshToken())).isEmpty();
    }

    private String sessionIdOf(RefreshTokenService.Issued issued) {
        return jwtService.parse(issued.accessToken()).orElseThrow().get("sid", String.class);
    }
}
