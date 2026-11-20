package dev.kualu.web.config;

import tools.jackson.databind.ObjectMapper;
import dev.kualu.llm.tools.ExecTool;
import dev.kualu.llm.tools.WebSearchTool;
import dev.kualu.llm.client.ImageSource;
import dev.kualu.llm.client.LlmClient;
import dev.kualu.llm.memory.MemoryCompressor;
import dev.kualu.mcp.McpConnectionManager;
import dev.kualu.mcp.McpServerConfig;
import dev.kualu.mcp.config.McpJsonConfig;
import dev.kualu.web.api.ApiDtos.ErrorResponse;
import dev.kualu.web.auth.InMemoryRefreshTokenStore;
import dev.kualu.web.auth.JwtAuthenticationFilter;
import dev.kualu.web.auth.UserPasswordStore;
import dev.kualu.web.files.ArtifactStore;
import dev.kualu.web.files.LocalArtifactStore;
import dev.kualu.web.files.OssArtifactStore;
import dev.kualu.web.session.ImageRef;
import dev.kualu.web.session.SessionStore;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
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
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;
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
    public dev.kualu.web.tools.UserToolEnvStore inMemoryUserToolEnvStore() {
        return new dev.kualu.web.tools.InMemoryUserToolEnvStore();
    }

    @Bean
    @ConditionalOnProperty(name = "agent.store", havingValue = "memory")
    public dev.kualu.web.llm.LlmProviderStore inMemoryLlmProviderStore() {
        return new dev.kualu.web.llm.InMemoryLlmProviderStore();
    }

    @Bean
    @ConditionalOnProperty(name = "agent.store", havingValue = "memory")
    public dev.kualu.web.llm.UserLlmPreferenceStore inMemoryUserLlmPreferenceStore() {
        return new dev.kualu.web.llm.InMemoryUserLlmPreferenceStore();
    }

    @Bean
    @ConditionalOnProperty(name = "agent.store", havingValue = "memory")
    public dev.kualu.web.email.EmailSettingsStore inMemoryEmailSettingsStore() {
        return new dev.kualu.web.email.InMemoryEmailSettingsStore();
    }

    @Bean
    @ConditionalOnProperty(name = "agent.store", havingValue = "memory")
    public dev.kualu.web.invite.InviteCodeStore inMemoryInviteCodeStore() {
        return new dev.kualu.web.invite.InMemoryInviteCodeStore();
    }

    @Bean
    @ConditionalOnProperty(name = "agent.store", havingValue = "memory")
    public dev.kualu.web.auth.UserAccountStore userAccountStore(InMemoryUserDetailsManager users) {
        return new dev.kualu.web.auth.InMemoryUserAccountStore(users);
    }

    @Bean
    @ConditionalOnProperty(name = "agent.store", havingValue = "memory")
    public dev.kualu.web.moderation.ModerationEventStore inMemoryModerationEventStore() {
        return new dev.kualu.web.moderation.InMemoryModerationEventStore();
    }

    @Bean
    @ConditionalOnProperty(name = "agent.store", havingValue = "memory")
    public dev.kualu.web.memory.MemoryStore inMemoryMemoryStore() {
        return new dev.kualu.web.memory.InMemoryMemoryStore();
    }

    @Bean
    public dev.kualu.web.memory.SemanticMemory semanticMemory(
            dev.kualu.web.memory.MemoryStore store, SemanticMemoryProperties properties) {
        log.info("semantic-memory configured: enabled={}, max-inject={}",
                properties.enabled(), properties.maxInject());
        return new dev.kualu.web.memory.SemanticMemory(store, properties.maxInject(), properties.enabled());
    }

    @Bean
    public dev.kualu.web.email.EmailService emailService(dev.kualu.web.email.EmailSettingsStore store) {
        return new dev.kualu.web.email.EmailService(store);
    }

    @Bean
    public dev.kualu.web.invite.InviteCodeService inviteCodeService(
            dev.kualu.web.invite.InviteCodeStore store, dev.kualu.web.email.EmailService emailService) {
        return new dev.kualu.web.invite.InviteCodeService(store, emailService);
    }

    /**
     * Seeds the primary provider from {@code llm.*} when the store is empty, so a fresh
     * deployment works before anyone opens the admin UI. Runs after Flyway (which is a
     * bean-lifecycle concern), hence as an {@link org.springframework.boot.ApplicationRunner}.
     */
    @Bean
    public dev.kualu.web.llm.LlmProviderSeeder llmProviderSeeder(
            dev.kualu.web.llm.LlmProviderStore store, LlmProperties properties) {
        return new dev.kualu.web.llm.LlmProviderSeeder(store, properties);
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration configuration) throws Exception {
        return configuration.getAuthenticationManager();
    }

    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            JwtAuthenticationFilter jwtFilter,
            ObjectMapper objectMapper,
            @Value("${agent.metrics.scrape-token:}") String metricsScrapeToken) throws Exception {
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
                                "/api/v1/auth/register",
                                "/api/v1/health",
                                "/actuator/health")
                        .permitAll()
                        .requestMatchers("/api/v1/admin/**").hasRole("ADMIN")
                        .requestMatchers("/api/v1/sessions/*/messages/raw").hasRole("ADMIN")
                        .requestMatchers("/actuator/prometheus").access(metricsScrape(metricsScrapeToken))
                        .requestMatchers("/actuator/**").hasRole("ADMIN")
                        .requestMatchers("/api/v1/**").hasRole("USER")
                        .anyRequest().permitAll())
                .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    /**
     * Prometheus scraping of {@code /actuator/prometheus}: granted when the request
     * carries the configured token — as {@code X-Scrape-Token} or {@code Authorization:
     * Bearer} (distro Prometheus before 2.50 can only send the latter) — or when the
     * caller is an ADMIN, denied otherwise. The comparison is constant-time and an
     * unset token lets only admins in. A scrape token is not a JWT, so the JWT filter
     * simply skips such a Bearer header.
     */
    private static AuthorizationManager<RequestAuthorizationContext> metricsScrape(String scrapeToken) {
        byte[] expected = scrapeToken == null ? new byte[0] : scrapeToken.getBytes(StandardCharsets.UTF_8);
        return (authentication, context) -> {
            String presented = context.getRequest().getHeader("X-Scrape-Token");
            if (presented == null) {
                String authorization = context.getRequest().getHeader("Authorization");
                if (authorization != null && authorization.startsWith("Bearer ")) {
                    presented = authorization.substring(7).trim();
                }
            }
            byte[] actual = presented == null ? new byte[0] : presented.getBytes(StandardCharsets.UTF_8);
            boolean byToken = expected.length > 0 && actual.length > 0 && MessageDigest.isEqual(expected, actual);
            boolean byAdmin = authentication.get().getAuthorities().stream()
                    .anyMatch(authority -> "ROLE_ADMIN".equals(authority.getAuthority()));
            return new AuthorizationDecision(byToken || byAdmin);
        };
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
     * The client is resolved per call so runtime provider changes are picked up
     * without a restart. When {@code agent.memory.summary-model} is set and still
     * on offer, summarisation runs on that model instead of the chat default —
     * a fast/cheap model keeps the 40-second summariser off the chat model.
     */
    @Bean
    public MemoryCompressor memoryCompressor(
            dev.kualu.web.llm.LlmSettingsService llmSettings, MemoryProperties properties) {
        if (!properties.enabled()) {
            log.info("memory-compress configured: enabled=false");
            return null;
        }
        log.info("memory-compress configured: enabled=true, max-messages={}, keep-messages={}, max-summary-chars={},"
                        + " summary-provider={}, summary-model={}",
                properties.maxMessages(), properties.keepMessages(), properties.maxSummaryChars(),
                properties.summaryProvider(), properties.summaryModel());
        return new MemoryCompressor(
                () -> {
                    LlmClient summary = llmSettings.clientForModel(properties.summaryProvider(), properties.summaryModel());
                    return summary != null ? summary : llmSettings.defaultClient();
                },
                properties.maxMessages(), properties.keepMessages(), properties.instruction(),
                properties.maxSummaryChars());
    }

    /**
     * The exec tool lets the model run system commands; it exists only when enabled.
     * {@code ChatService} picks up every {@code AgentTool} bean.
     */
    @Bean
    @ConditionalOnProperty(name = "agent.tools.exec.enabled", havingValue = "true", matchIfMissing = true)
    public ExecTool execTool(
            ExecToolProperties properties,
            dev.kualu.web.tools.UserToolEnvStore toolEnvStore) {
        String workdir = properties.workdir() == null || properties.workdir().isBlank()
                ? null
                : properties.workdir();
        ExecTool.Config config = new ExecTool.Config(
                Duration.ofSeconds(properties.timeoutSeconds()),
                properties.outputLimit(),
                workdir == null ? null : Path.of(workdir));
        String workspacesRoot = properties.workspacesRoot() == null || properties.workspacesRoot().isBlank()
                ? null
                : properties.workspacesRoot();
        if (workspacesRoot == null) {
            log.info("exec-tool configured: enabled=true, timeout={}s, output-limit={}, workdir={}",
                    properties.timeoutSeconds(), properties.outputLimit(),
                    workdir == null ? "(inherited)" : workdir);
            return new ExecTool(config);
        }
        log.info("exec-tool configured: enabled=true, timeout={}s, output-limit={}, workspaces-root={}, config-env={}",
                properties.timeoutSeconds(), properties.outputLimit(), workspacesRoot, properties.configEnv().keySet());
        return new ExecTool(config,
                new dev.kualu.web.tools.ExecScopeResolver(Path.of(workspacesRoot), toolEnvStore, properties.configEnv()));
    }

    /**
     * Web search through the Tavily API. Registered only when enabled and an API
     * key is configured: the model sees no search tool (rather than a broken one)
     * on deployments without credentials. {@code ChatService} picks up every
     * {@code AgentTool} bean.
     */
    @Bean
    @ConditionalOnExpression(
            "'${agent.tools.websearch.enabled:true}' == 'true'"
                    + " and '${agent.tools.websearch.api-key:}'.trim() != ''")
    public WebSearchTool webSearchTool(WebSearchToolProperties properties) {
        String baseUrl = properties.baseUrl() == null || properties.baseUrl().isBlank()
                ? WebSearchTool.Config.DEFAULT_BASE_URL
                : properties.baseUrl();
        log.info("websearch-tool configured: enabled=true, base-url={}, max-results={}, output-limit={},"
                        + " timeout={}s, include-answer={}",
                baseUrl, properties.maxResults(), properties.outputLimit(),
                properties.timeoutSeconds(), properties.includeAnswer());
        return new WebSearchTool(new WebSearchTool.Config(
                properties.apiKey().strip(),
                baseUrl,
                properties.maxResults(),
                properties.outputLimit(),
                Duration.ofSeconds(properties.timeoutSeconds()),
                properties.includeAnswer()));
    }

    /**
     * Where downloadable tool artifacts live. Local dir by default (must match
     * the office-mcp server's {@code OFFICE_OUTPUT_DIR}); {@code agent.files.store=oss}
     * proxies reads from the same Aliyun OSS bucket the office-mcp server uploads to.
     */
    @Bean(destroyMethod = "close")
    public ArtifactStore fileArtifactStore(FileProperties properties) {
        if ("oss".equals(properties.store())) {
            FileProperties.Oss oss = properties.oss();
            log.info("file-artifacts configured: store=oss endpoint={} bucket={} prefix={}",
                    oss.endpoint(), oss.bucket(), oss.prefix());
            return new OssArtifactStore(oss.endpoint(), oss.bucket(), oss.prefix(),
                    oss.accessKeyId(), oss.accessKeySecret());
        }
        Path root = Path.of(properties.dir());
        log.info("file-artifacts configured: store=local dir={}", root.toAbsolutePath().normalize());
        return new LocalArtifactStore(root);
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
