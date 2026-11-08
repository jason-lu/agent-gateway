package dev.kualu.web.config;

import tools.jackson.databind.ObjectMapper;
import dev.kualu.llm.tools.ExecTool;
import dev.kualu.llm.client.ImageSource;
import dev.kualu.llm.memory.MemoryCompressor;
import dev.kualu.mcp.McpConnectionManager;
import dev.kualu.mcp.McpServerConfig;
import dev.kualu.mcp.config.McpJsonConfig;
import dev.kualu.web.api.ApiDtos.ErrorResponse;
import dev.kualu.web.auth.InMemoryRefreshTokenStore;
import dev.kualu.web.auth.JwtAuthenticationFilter;
import dev.kualu.web.auth.UserPasswordStore;
import dev.kualu.web.session.ImageRef;
import dev.kualu.web.session.SessionStore;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableMethodSecurity
public class AppConfig {

    private static final Logger log = LoggerFactory.getLogger(AppConfig.class);

    @Bean
    public PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    /**
     * Config-backed users, used when {@code agent.store=memory}. The postgres profile
     * authenticates against {@code agent.users} instead (see {@link PostgresConfig}).
     * {@code InMemoryUserDetailsManager} is itself the {@link UserDetailsService}.
     */
    @Bean
    @ConditionalOnProperty(name = "agent.store", havingValue = "memory")
    public InMemoryUserDetailsManager inMemoryUsers(SecurityProperties properties, PasswordEncoder encoder) {
        var manager = new InMemoryUserDetailsManager();
        for (SecurityProperties.User user : properties.users()) {
            manager.createUser(User.builder()
                    .username(user.username())
                    .password(encoder.encode(user.password()))
                    .roles(user.roles().toArray(String[]::new))
                    .build());
        }
        return manager;
    }

    /** In-memory password changes last until restart — the store is rebuilt from config. */
    @Bean
    @ConditionalOnProperty(name = "agent.store", havingValue = "memory")
    public UserPasswordStore userPasswordStore(InMemoryUserDetailsManager users) {
        return (username, encodedPassword) ->
                users.updatePassword(users.loadUserByUsername(username), encodedPassword);
    }

    @Bean
    @ConditionalOnProperty(name = "agent.store", havingValue = "memory")
    public InMemoryRefreshTokenStore inMemoryRefreshTokenStore() {
        return new InMemoryRefreshTokenStore();
    }

    @Bean
    @ConditionalOnProperty(name = "agent.store", havingValue = "memory")
    public dev.kualu.web.mcp.UserMcpServerStore inMemoryUserMcpServerStore() {
        return new dev.kualu.web.mcp.InMemoryUserMcpServerStore();
    }

    @Bean
    @ConditionalOnProperty(name = "agent.store", havingValue = "memory")
    public dev.kualu.web.llm.AdminLlmSettingsStore inMemoryAdminLlmSettingsStore() {
        return new dev.kualu.web.llm.InMemoryAdminLlmSettingsStore();
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration configuration) throws Exception {
        return configuration.getAuthenticationManager();
    }

    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            JwtAuthenticationFilter jwtFilter,
            ObjectMapper objectMapper) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint((request, response, authException) ->
                                writeError(response, objectMapper, HttpStatus.UNAUTHORIZED, "unauthorized"))
                        .accessDeniedHandler(accessDeniedHandler(objectMapper)))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(
                                "/api/v1/auth/login",
                                "/api/v1/auth/refresh",
                                "/api/v1/auth/logout",
                                "/api/v1/health",
                                "/actuator/health")
                        .permitAll()
                        .requestMatchers("/api/v1/admin/**").hasRole("ADMIN")
                        .requestMatchers("/actuator/**").hasRole("ADMIN")
                        .requestMatchers("/api/v1/**").hasRole("USER")
                        .anyRequest().permitAll())
                .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    @Bean
    public ImageSource imageSource(ObjectProvider<SessionStore> stores) {
        SessionStore store = stores.getIfAvailable();
        return image -> {
            if (store != null && ImageRef.isRef(image)) {
                return store.findImageContent(ImageRef.sessionIdOf(image), ImageRef.imageIdOf(image))
                        .orElse(image);
            }
            return image;
        };
    }

    /**
     * The client itself comes from {@link dev.kualu.web.llm.LlmSettingsService}
     * (admin overrides win over {@code llm.*}); it is supplied per call so runtime
     * config changes are picked up without a restart.
     */
    @Bean
    public MemoryCompressor memoryCompressor(
            dev.kualu.web.llm.LlmSettingsService llmSettings, MemoryProperties properties) {
        if (!properties.enabled()) {
            log.info("memory-compress configured: enabled=false");
            return null;
        }
        log.info("memory-compress configured: enabled=true, max-messages={}, keep-messages={}, max-summary-chars={}",
                properties.maxMessages(), properties.keepMessages(), properties.maxSummaryChars());
        return new MemoryCompressor(
                llmSettings::client, properties.maxMessages(), properties.keepMessages(), properties.instruction(),
                properties.maxSummaryChars());
    }

    /**
     * The exec tool lets the model run system commands; it exists only when enabled.
     * {@code ChatService} picks up every {@code AgentTool} bean.
     */
    @Bean
    @ConditionalOnProperty(name = "agent.tools.exec.enabled", havingValue = "true", matchIfMissing = true)
    public ExecTool execTool(ExecToolProperties properties) {
        String workdir = properties.workdir() == null || properties.workdir().isBlank()
                ? null
                : properties.workdir();
        log.info("exec-tool configured: enabled=true, timeout={}s, output-limit={}, workdir={}",
                properties.timeoutSeconds(), properties.outputLimit(), workdir == null ? "(inherited)" : workdir);
        return new ExecTool(new ExecTool.Config(
                Duration.ofSeconds(properties.timeoutSeconds()),
                properties.outputLimit(),
                workdir == null ? null : Path.of(workdir)));
    }

    /**
     * MCP client: server list comes from {@code mcp.json} (primary) plus the inline
     * {@code agent.tools.mcp.servers} override layer. Started with the context,
     * closed with it.
     */
    @Bean(initMethod = "start", destroyMethod = "close")
    @ConditionalOnProperty(name = "agent.tools.mcp.enabled", havingValue = "true", matchIfMissing = true)
    public McpConnectionManager mcpConnectionManager(
            McpProperties properties, ObjectProvider<dev.kualu.web.auth.PostgresTokenStore> tokenStores) {
        Map<String, McpServerConfig> servers = new LinkedHashMap<>();
        Path configPath = Path.of(properties.config());
        if (java.nio.file.Files.exists(configPath)) {
            for (McpServerConfig server : McpJsonConfig.load(configPath)) {
                servers.put(server.name(), server);
            }
            log.info("mcp configured: {} server(s) from {}", servers.size(), configPath);
        } else {
            log.info("mcp configured: no config file at {}", configPath);
        }
        for (McpProperties.Server inline : properties.servers()) {
            McpServerConfig server = inlineServer(inline);
            servers.put(server.name(), server);
        }
        return new McpConnectionManager(
                List.copyOf(servers.values()),
                McpConnectionManager.DEFAULT_HANDSHAKE_TIMEOUT,
                Duration.ofSeconds(properties.defaultTimeoutSeconds()),
                properties.outputLimit(),
                Duration.ofSeconds(properties.refreshSeconds()),
                null,
                tokenStores.getIfAvailable());
    }

    /**
     * Per-user MCP managers: each user's own {@code agent.user_mcp_servers} entries
     * become their own connections, so personal credentials never leak across users.
     * Same feature flag as the shared layer — {@code agent.tools.mcp.enabled=false}
     * turns MCP off entirely.
     */
    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(name = "agent.tools.mcp.enabled", havingValue = "true", matchIfMissing = true)
    public dev.kualu.web.mcp.UserMcpRegistry userMcpRegistry(
            dev.kualu.web.mcp.UserMcpServerStore store, McpProperties properties) {
        return new dev.kualu.web.mcp.UserMcpRegistry(store, properties);
    }

    /** Inline yml entry; values are already placeholder-resolved by Spring. */
    private static McpServerConfig inlineServer(McpProperties.Server inline) {
        if (inline.name() == null || inline.name().isBlank()) {
            throw new IllegalArgumentException("agent.tools.mcp.servers entries need a name");
        }
        McpServerConfig.AuthMode authMode = switch (inline.auth() == null ? "" : inline.auth().toLowerCase()) {
            case "headers" -> McpServerConfig.AuthMode.HEADERS;
            case "oauth" -> McpServerConfig.AuthMode.OAUTH;
            case "" -> null;
            default -> throw new IllegalArgumentException("unknown auth mode: " + inline.auth());
        };
        McpServerConfig config;
        if (inline.url() != null && !inline.url().isBlank() && inline.command() == null) {
            config = McpServerConfig.http(inline.name(), inline.url(), inline.headers());
        } else if (inline.command() != null && !inline.command().isBlank()) {
            List<String> argv = new ArrayList<>();
            argv.add(inline.command());
            argv.addAll(inline.args());
            config = McpServerConfig.stdio(inline.name(), argv, inline.env());
        } else {
            throw new IllegalArgumentException(
                    "agent.tools.mcp.servers entry '" + inline.name() + "' needs command or url");
        }
        if (authMode != null) {
            config = config.withAuthMode(authMode);
        }
        return config.withOAuthClient(inline.clientId(), inline.scopes());
    }

    private static AccessDeniedHandler accessDeniedHandler(ObjectMapper objectMapper) {
        return (HttpServletRequest request, HttpServletResponse response, AccessDeniedException ex) ->
                writeError(response, objectMapper, HttpStatus.FORBIDDEN, "forbidden");
    }

    private static void writeError(
            HttpServletResponse response,
            ObjectMapper objectMapper,
            HttpStatus status,
            String message) throws java.io.IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(),
                new ErrorResponse(status.value(), status.getReasonPhrase(), message));
    }
}
