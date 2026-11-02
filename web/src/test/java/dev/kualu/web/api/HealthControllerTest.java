package dev.kualu.web.api;

import dev.kualu.web.chat.ChatService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class HealthControllerTest {

    @Test
    void dbUp_isTrueWhenTheDatabaseAnswers() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), eq(Integer.class))).thenReturn(1);

        assertTrue(new HealthController(mock(ChatService.class), provider(jdbc)).health().dbUp());
    }

    @Test
    void dbUp_isFalse_whenTheDatabaseIsDown_butTheEndpointStillAnswers() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), eq(Integer.class)))
                .thenThrow(new DataAccessResourceFailureException("connection refused"));

        // A down database is exactly when this endpoint must keep answering.
        assertFalse(new HealthController(mock(ChatService.class), provider(jdbc)).health().dbUp());
    }

    @Test
    void dbUp_isTrueInMemoryMode_whereThereIsNoDatabaseToProbe() {
        assertTrue(new HealthController(mock(ChatService.class), provider(null)).health().dbUp());
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<JdbcTemplate> provider(JdbcTemplate jdbc) {
        ObjectProvider<JdbcTemplate> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(jdbc);
        return provider;
    }
}
