package dev.kualu.web.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * PostgreSQL connection settings. The password has no default and must come
 * from the environment or {@code .env} — never from source.
 */
@ConfigurationProperties(prefix = "agent.db")
public record DbProperties(String url, String username, String password) {
}
