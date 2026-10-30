package dev.kualu.web.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.kualu.llm.LlmClient;
import dev.kualu.llm.LlmConfig;
import dev.kualu.web.api.ApiDtos.ErrorResponse;
import dev.kualu.web.auth.JwtAuthenticationFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
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
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableMethodSecurity
public class AppConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    @Bean
    public UserDetailsService userDetailsService(SecurityProperties properties, PasswordEncoder encoder) {
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
        return new LlmClient(new LlmConfig(properties.baseUrl(), properties.apiKey(), properties.model()));
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
