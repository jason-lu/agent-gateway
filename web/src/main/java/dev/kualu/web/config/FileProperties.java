package dev.kualu.web.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Downloadable artifact storage. {@code agent.files.store=local} serves from
 * {@code dir}; {@code oss} proxies reads from an Aliyun OSS bucket that the
 * office-mcp server uploads to under the same prefix. Both modes keep the
 * {@code /api/v1/files/**} contract — the switch is where the bytes live.
 */
@ConfigurationProperties(prefix = "agent.files")
public record FileProperties(
        @DefaultValue("local") String store,
        @DefaultValue("output/office") String dir,
        Oss oss) {

    public FileProperties {
        if (dir == null || dir.isBlank()) {
            throw new IllegalArgumentException("agent.files.dir must not be blank");
        }
        if (store == null || store.isBlank()) {
            store = "local";
        }
        if (!"local".equals(store) && !"oss".equals(store)) {
            throw new IllegalArgumentException("agent.files.store must be 'local' or 'oss', was '" + store + "'");
        }
        if ("oss".equals(store)) {
            require(oss == null || isBlank(oss.endpoint()), "agent.files.oss.endpoint");
            require(oss == null || isBlank(oss.bucket()), "agent.files.oss.bucket");
            require(oss == null || isBlank(oss.accessKeyId()), "agent.files.oss.access-key-id");
            require(oss == null || isBlank(oss.accessKeySecret()), "agent.files.oss.access-key-secret");
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static void require(boolean missing, String setting) {
        if (missing) {
            throw new IllegalArgumentException(setting + " is required when agent.files.store=oss");
        }
    }

    /** Aliyun OSS connection; {@code prefix} scopes keys, default {@code office/}. */
    public record Oss(
            String endpoint,
            String bucket,
            @DefaultValue("office/") String prefix,
            String accessKeyId,
            String accessKeySecret) {
    }
}
