package dev.kualu.web.files;

import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import com.aliyun.oss.model.ObjectMetadata;
import com.aliyun.oss.model.OSSObject;

import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;

/**
 * Artifacts in an Aliyun OSS bucket. The gateway proxies downloads (streams the
 * object through the authenticated {@code /api/v1/files/**} endpoint), so the
 * bucket stays private-read and the client contract is unchanged from the local
 * store. Keys are {@code prefix + relative name}; the office-mcp server uploads
 * under the same prefix.
 */
public final class OssArtifactStore implements ArtifactStore {

    private final OSS client;
    private final String bucket;
    private final String prefix;

    public OssArtifactStore(String endpoint, String bucket, String prefix,
                            String accessKeyId, String accessKeySecret) {
        this(new OSSClientBuilder().build(endpoint, accessKeyId, accessKeySecret), bucket, prefix);
    }

    /** Test seam: an {@link OSS} stub replaces the real client. */
    OssArtifactStore(OSS client, String bucket, String prefix) {
        this.client = Objects.requireNonNull(client, "client");
        this.bucket = Objects.requireNonNull(bucket, "bucket");
        String raw = prefix == null ? "" : prefix.trim();
        this.prefix = raw.isEmpty() || raw.endsWith("/") ? raw : raw + "/";
    }

    /** @return the bucket key for a relative name (package-visible for tests) */
    String keyOf(String relativeName) {
        return prefix + ArtifactNames.normalize(relativeName);
    }

    @Override
    public boolean exists(String relativeName) {
        return client.doesObjectExist(bucket, keyOf(relativeName));
    }

    @Override
    public long size(String relativeName) throws IOException {
        try {
            ObjectMetadata metadata = client.getObjectMetadata(bucket, keyOf(relativeName));
            return metadata.getContentLength();
        } catch (RuntimeException e) {
            throw new IOException("oss metadata failed for " + relativeName + ": " + e.getMessage(), e);
        }
    }

    @Override
    public InputStream open(String relativeName) throws IOException {
        try {
            OSSObject object = client.getObject(bucket, keyOf(relativeName));
            return object.getObjectContent();
        } catch (RuntimeException e) {
            throw new IOException("oss read failed for " + relativeName + ": " + e.getMessage(), e);
        }
    }

    @Override
    public void close() {
        client.shutdown();
    }
}
