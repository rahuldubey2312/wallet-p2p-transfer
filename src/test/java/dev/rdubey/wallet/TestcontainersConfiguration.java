package dev.rdubey.wallet;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Tests run against a real Postgres, not an in-memory substitute: the
 * invariants under test depend on row locking, ON CONFLICT and the exact
 * blocking behaviour of a unique index, none of which H2 reproduces
 * faithfully.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration
{
    @Bean
    @ServiceConnection
    PostgreSQLContainer postgresContainer()
    {
        return new PostgreSQLContainer(DockerImageName.parse("postgres:16-alpine"));
    }
}
