package dev.kualu.web.config;

import tools.jackson.databind.ObjectMapper;
import dev.kualu.llm.LlmClient;
import dev.kualu.llm.LlmConfig;
import dev.kualu.llm.MemoryCompressor;
import dev.kualu.web.api.ApiDtos.ErrorResponse;
import dev.kualu.web.auth.JwtAuthenticationFilter;
import dev.kualu.web.auth.UserPasswordStore;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
                        .requestMatchers("/api/v1/auth/login", "/api/v1/health").permitAll()
                        .requestMatchers("/api/v1/admin/**").hasRole("ADMIN")
                        .requestMatchers("/api/v1/**").hasRole("USER")
                        .anyRequest().permitAll())
                .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    @Bean
    public LlmClient llmClient(LlmProperties properties) {
        if (!properties.isConfigured()) {
            return null;
        }
        return new LlmClient(new LlmConfig(
                properties.baseUrl(),
                properties.apiKey(),
                properties.model(),
                Duration.ofSeconds(properties.idleTimeoutSeconds())));
    }

    @Bean
    public MemoryCompressor memoryCompressor(LlmClient llmClient, MemoryProperties properties) {
        if (!properties.enabled()) {
            log.info("memory-compress configured: enabled=false");
            return null;
        }
        if (llmClient == null) {
            log.info("memory-compress configured: enabled=true but llm is not configured; compressor inactive");
            return null;
        }
        log.info("memory-compress configured: enabled=true, max-messages={}, keep-messages={}",
                properties.maxMessages(), properties.keepMessages());
        return new MemoryCompressor(
                llmClient, properties.maxMessages(), properties.keepMessages(), properties.instruction());
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
