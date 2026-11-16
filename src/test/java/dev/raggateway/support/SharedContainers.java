package dev.raggateway.support;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * One Postgres and one Redis container per test JVM, started on first use and stopped by Testcontainers'
 * reaper at JVM exit. Shared so the Spring context cache can reuse one application context across test
 * classes. Tests must clean up their own data (see {@link AbstractIntegrationTest#resetState()}).
 */
public final class SharedContainers {

    private static PostgreSQLContainer<?> postgres;
    private static GenericContainer<?> redis;

    private SharedContainers() {
    }

    public static synchronized PostgreSQLContainer<?> postgres() {
        if (postgres == null) {
            postgres = new PostgreSQLContainer<>(TestImages.POSTGRES)
                    .withDatabaseName("raggateway")
                    .withUsername("raggateway")
                    .withPassword("raggateway");
            postgres.start();
        }
        return postgres;
    }

    public static synchronized GenericContainer<?> redis() {
        if (redis == null) {
            redis = new GenericContainer<>(TestImages.REDIS).withExposedPorts(6379);
            redis.start();
        }
        return redis;
    }
}
