package dev.kualu.web.files;

import java.io.IOException;
import java.io.InputStream;

/**
 * Where downloadable tool artifacts live: local disk today, object storage when
 * the deployment grows past one machine. The controller only ever sees names,
 * sizes and streams — so swapping the backend is a bean decision, not a code
 * change.
 */
public interface ArtifactStore extends AutoCloseable {

    /** @throws IllegalArgumentException when the name fails {@link ArtifactNames#normalize} */
    boolean exists(String relativeName);

    /** @throws IllegalArgumentException when the name fails {@link ArtifactNames#normalize} */
    long size(String relativeName) throws IOException;

    /** @throws IllegalArgumentException when the name fails {@link ArtifactNames#normalize} */
    InputStream open(String relativeName) throws IOException;

    @Override
    default void close() {
        // most stores hold nothing closeable
    }
}
