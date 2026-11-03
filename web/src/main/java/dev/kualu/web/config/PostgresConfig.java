package dev.kualu.web.config;

import tools.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import dev.kualu.web.auth.JdbcUserDetailsService;
import dev.kualu.web.auth.JdbcUserSeeder;
import dev.kualu.web.auth.PostgresRefreshTokenStore;
import dev.kualu.web.auth.UserPasswordStore;
import dev.kualu.web.session.ChatMessageCodec;
import dev.kualu.web.session.PostgresSessionStore;
import dev.kualu.web.session.SessionStore;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.password.PasswordEncoder;

import javax.sql.DataSource;

/**
 * The PostgreSQL {@link DataSource}. Only registered when {@code agent.store=postgres};
 * with {@code agent.store=memory} no DataSource exists at all, so Flyway and every
 * JDBC auto-configuration back off and the app runs without a database.
 *
 * <p>{@code DataSourceAutoConfiguration} is excluded in {@code application.yml} so this
 * bean is the only way a DataSource comes into existence — the memory profile therefore
 * never attempts a connection.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "agent.store", havingValue = "postgres")
public class PostgresConfig {

    @Bean
    public DataSource dataSource(DbProperties properties) {
        requireText(properties.url(), "agent.db.url (AGENT_DB_URL)");
        requireText(properties.username(), "agent.db.username (AGENT_DB_USERNAME)");
        if (properties.password() == null || properties.password().isBlank()) {
            throw new IllegalStateException(
                    "agent.db.password (AGENT_DB_PASSWORD) must be set when agent.store=postgres; "
                            + "it has no default and must not live in source. Put it in .env or the environment.");
        }
        requireExplicitSslMode(properties.url());

        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(properties.url());
        config.setUsername(properties.username());
        config.setPassword(properties.password());
        config.setPoolName("agent-db");
        // Startup connectivity is Flyway's gate: it runs first and fails the boot with a
        // clear migration error. Letting Hikari also dial out here only adds pool noise.
        config.setInitializationFailTimeout(0);
        return new HikariDataSource(config);
    }

    @Bean
    public JdbcTemplate jdbcTemplate(DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }

    @Bean
    public UserDetailsService userDetailsService(JdbcTemplate jdbc) {
        return new JdbcUserDetailsService(jdbc);
    }

    @Bean
    public UserPasswordStore userPasswordStore(JdbcTemplate jdbc) {
        return (username, encodedPassword) -> jdbc.update(
                "update agent.users set password_hash = ? where username = ?",
                encodedPassword, username);
    }

    /** Runs after Flyway so the tables exist. See {@link JdbcUserSeeder} for the semantics. */
    @Bean
    public ApplicationRunner userSeeder(JdbcTemplate jdbc, PasswordEncoder encoder, SecurityProperties properties) {
        return args -> new JdbcUserSeeder(jdbc, encoder).seed(properties.users());
    }

    @Bean
    public ChatMessageCodec chatMessageCodec(ObjectMapper objectMapper) {
        return new ChatMessageCodec(objectMapper);
    }

    @Bean
    public SessionStore sessionStore(JdbcTemplate jdbc, ChatMessageCodec codec) {
        return new PostgresSessionStore(jdbc, codec);
    }

    @Bean
    public PostgresRefreshTokenStore refreshTokenStore(JdbcTemplate jdbc) {
        return new PostgresRefreshTokenStore(jdbc);
    }

    /**
     * The database is often reachable over the public internet, so the URL must select a
     * TLS mode that cannot silently fall back to plaintext password auth. A deny-list is
     * not enough: {@code prefer} and {@code allow} still permit plaintext, so this is a
     * whitelist of the modes that guarantee encryption. The single exception is a loopback
     * host, where plaintext cannot leave the machine — that is what makes a local or
     * embedded database usable in tests.
     */
    private static void requireExplicitSslMode(String url) {
        String sslMode = queryParam(url, "sslmode");
        if (sslMode == null) {
            throw new IllegalStateException("agent.db.url must set sslmode explicitly (use sslmode=require): " + url);
        }
        String normalised = sslMode.toLowerCase(java.util.Locale.ROOT);
        if (normalised.equals("require") || normalised.equals("verify-ca") || normalised.equals("verify-full")) {
            return;
        }
        if (normalised.equals("disable") && isLoopback(hostOf(url))) {
            return;
        }
        throw new IllegalStateException("agent.db.url must use sslmode=require (or verify-ca / verify-full); "
                + "'" + sslMode + "' can fall back to plaintext and would send credentials unencrypted: " + url);
    }

    /** @return the host part of {@code jdbc:postgresql://host:port/db}, or {@code null} */
    private static String hostOf(String url) {
        int scheme = url.indexOf("://");
        if (scheme < 0) {
            return null;
        }
        String rest = url.substring(scheme + 3);
        int end = rest.indexOf('/');
        return end < 0 ? rest : rest.substring(0, end);
    }

    private static boolean isLoopback(String host) {
        if (host == null) {
            return false;
        }
        int portSeparator = host.indexOf(':');
        String name = (portSeparator < 0 ? host : host.substring(0, portSeparator)).toLowerCase(java.util.Locale.ROOT);
        return name.equals("localhost") || name.equals("127.0.0.1") || name.equals("::1") || name.equals("[::1]");
    }

    private static String queryParam(String url, String name) {
        int query = url.indexOf('?');
        if (query < 0) {
            return null;
        }
        for (String pair : url.substring(query + 1).split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0 && name.equals(pair.substring(0, eq))) {
                return pair.substring(eq + 1);
            }
        }
        return null;
    }

    private static void requireText(String value, String key) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(key + " must be set when agent.store=postgres");
        }
    }
}
