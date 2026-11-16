package dev.raggateway.support;

import org.testcontainers.utility.DockerImageName;

/** Image tags used by tests. Same pins as docker-compose.yml (D-20). */
public final class TestImages {

    public static final DockerImageName POSTGRES =
            DockerImageName.parse("pgvector/pgvector:0.8.7-pg16").asCompatibleSubstituteFor("postgres");
    public static final DockerImageName REDIS = DockerImageName.parse("redis:7.4.12-alpine");

    private TestImages() {
    }
}
