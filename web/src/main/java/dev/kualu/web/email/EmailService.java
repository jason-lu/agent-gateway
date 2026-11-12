package dev.kualu.web.email;

import jakarta.mail.Authenticator;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.PasswordAuthentication;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Properties;

/**
 * Sends HTML mail through the admin-configured SMTP account. The connection is
 * built per send so runtime setting changes apply without a restart — the
 * defaults (Aliyun enterprise mail, implicit SSL on 465) live in the store.
 */
public class EmailService {

    private static final Logger log = LoggerFactory.getLogger(EmailService.class);

    private static final int TIMEOUT_MILLIS = 15_000;

    private final EmailSettingsStore store;

    public EmailService(EmailSettingsStore store) {
        this.store = store;
    }

    /** @throws EmailSendException when nothing is configured or the SMTP exchange fails */
    public void send(String to, String subject, String htmlBody) {
        EmailSettings settings = store.find().filter(EmailSettings::configured)
                .orElseThrow(() -> new EmailSendException("邮箱尚未配置，请先在管理页面填写 SMTP 账号和密码"));
        Properties props = new Properties();
        props.put("mail.smtp.auth", "true");
        props.put("mail.smtp.host", settings.host());
        props.put("mail.smtp.port", String.valueOf(settings.port()));
        props.put("mail.smtp.ssl.enable", String.valueOf(settings.useSsl()));
        props.put("mail.smtp.connectiontimeout", String.valueOf(TIMEOUT_MILLIS));
        props.put("mail.smtp.timeout", String.valueOf(TIMEOUT_MILLIS));
        props.put("mail.smtp.writetimeout", String.valueOf(TIMEOUT_MILLIS));

        Session session = Session.getInstance(props, new Authenticator() {
            @Override
            protected PasswordAuthentication getPasswordAuthentication() {
                return new PasswordAuthentication(settings.username(), settings.password());
            }
        });

        try {
            MimeMessage message = new MimeMessage(session);
            message.setFrom(new InternetAddress(settings.effectiveFrom()));
            message.setRecipient(Message.RecipientType.TO, new InternetAddress(to));
            message.setSubject(subject, "UTF-8");
            message.setContent(htmlBody, "text/html; charset=UTF-8");
            Transport.send(message);
            log.info("email: sent '{}' to {}", subject, to);
        } catch (MessagingException ex) {
            throw new EmailSendException("邮件发送失败：" + ex.getMessage(), ex);
        }
    }
}
