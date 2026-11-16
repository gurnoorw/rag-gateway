package dev.raggateway.support;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.simple.JdbcClient;

import javax.sql.DataSource;

/**
 * Base class for repository and SQL tests without a Spring context: a DataSource on the shared pgvector
 * container with all Flyway migrations applied. Faster than {@link AbstractIntegrationTest}; tests clean
 * their own rows.
 */
public abstract class AbstractPostgresTest {

    protected static DataSource dataSource;
    protected static JdbcClient jdbc;

    @BeforeAll
    static void migrate() {
        if (dataSource != null) return;
        var pg = SharedContainers.postgres();
        var ds = new PGSimpleDataSource();
        ds.setUrl(pg.getJdbcUrl());
        ds.setUser(pg.getUsername());
        ds.setPassword(pg.getPassword());
        Flyway.configure().dataSource(ds).load().migrate();
        dataSource = ds;
        jdbc = JdbcClient.create(ds);
    }
}
