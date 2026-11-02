package dev.kualu.web.auth;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class JdbcUserDetailsServiceTest {

    @Test
    @SuppressWarnings("unchecked")
    void unknownUser_throwsUsernameNotFound() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.query(anyString(), any(RowMapper.class), eq("ghost")))
                .thenReturn(List.of());

        assertThrows(UsernameNotFoundException.class,
                () -> new JdbcUserDetailsService(jdbc).loadUserByUsername("ghost"));
    }
}
