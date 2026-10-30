package dev.kualu.web.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

@ConfigurationProperties(prefix = "agent.security")
public record SecurityProperties(
        String jwtSecret,
        Duration jwtTtl,
        List<User> users
) {

    public SecurityProperties {
        users = users == null ? new ArrayList<>() : List.copyOf(users);
    }

    public record User(String username, String password, List<String> roles) {
        public User {
            roles = roles == null || roles.isEmpty() ? List.of("USER") : List.copyOf(roles);
        }
    }
}
