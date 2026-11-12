package dev.kualu.web.email;

/**
 * Outbound SMTP connection. {@code fromAddress} falls back to {@code username}
 * when blank — Aliyun enterprise mail sends as the authenticated account.
 */
public record EmailSettings(
        String host,
        int port,
        String username,
        String password,
        String fromAddress,
        boolean useSsl) {

    public String effectiveFrom() {
        return fromAddress == null || fromAddress.isBlank() ? username : fromAddress;
    }

    public boolean configured() {
        return host != null && !host.isBlank() && username != null && !username.isBlank();
    }
}
