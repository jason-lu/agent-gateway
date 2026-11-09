package dev.kualu.web;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;

/**
 * One embedded PostgreSQL per test JVM. initdb costs seconds on its own, so the
 * store/persistence classes share this instance instead of starting one each;
 * they still reset their data (or drop the schema) between tests. The instance
 * closes with the JVM.
 */
public final class SharedPostgres {

    private static final EmbeddedPostgres INSTANCE = start();

    private static EmbeddedPostgres start() {
        try {
            EmbeddedPostgres postgres = EmbeddedPostgres.start();
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try {
                    postgres.close();
                } catch (Exception ignored) {
                    // the JVM is going down anyway
                }
            }));
            return postgres;
        } catch (Exception ex) {
            throw new IllegalStateException("cannot start embedded PostgreSQL", ex);
        }
    }

    public static EmbeddedPostgres get() {
        return INSTANCE;
    }

    private SharedPostgres() {
    }
}
