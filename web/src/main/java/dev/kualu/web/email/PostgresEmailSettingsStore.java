package dev.kualu.web.email;

import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;

/** The {@code agent.email_settings} singleton row (see {@code V10__email_settings_invite_codes.sql}). */
public class PostgresEmailSettingsStore implements EmailSettingsStore {

    private final JdbcTemplate jdbc;

    public PostgresEmailSettingsStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<EmailSettings> find() {
        return jdbc.query(
                "select host, port, username, password, from_address, use_ssl from agent.email_settings where id = 1",
                (rs, rowNum) -> new EmailSettings(
                        rs.getString("host"),
                        rs.getInt("port"),
                        rs.getString("username"),
                        rs.getString("password"),
                        rs.getString("from_address"),
                        rs.getBoolean("use_ssl")))
                .stream().findFirst();
    }

    @Override
    public void save(EmailSettings settings) {
        jdbc.update(
                "update agent.email_settings set host = ?, port = ?, username = ?, password = ?,"
                        + " from_address = ?, use_ssl = ?, updated_at = ? where id = 1",
                settings.host(), settings.port(), settings.username(), settings.password(),
                settings.fromAddress() == null ? "" : settings.fromAddress(), settings.useSsl(),
                Timestamp.from(Instant.now()));
    }
}
