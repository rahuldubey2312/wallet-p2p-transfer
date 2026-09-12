package dev.rdubey.wallet.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The translation runs once, at startup, on a host we cannot poke at
 * interactively; a mistake here shows up only as a service that will not boot.
 */
class DatabaseUrlEnvironmentPostProcessorTest
{
    private final DatabaseUrlEnvironmentPostProcessor processor = new DatabaseUrlEnvironmentPostProcessor();

    @Test
    @DisplayName("a provider postgres:// URL becomes a JDBC url plus credentials")
    void translatesProviderUrl()
    {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("DATABASE_URL", "postgres://wallet:s3cret@dpg-abc123:5432/wallet_db");

        processor.postProcessEnvironment(environment, null);

        assertThat(environment.getProperty("spring.datasource.url"))
                .isEqualTo("jdbc:postgresql://dpg-abc123:5432/wallet_db");
        assertThat(environment.getProperty("spring.datasource.username")).isEqualTo("wallet");
        assertThat(environment.getProperty("spring.datasource.password")).isEqualTo("s3cret");
    }

    @Test
    @DisplayName("query parameters such as sslmode are preserved")
    void preservesQueryParameters()
    {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("DATABASE_URL", "postgres://u:p@host/db?sslmode=require");

        processor.postProcessEnvironment(environment, null);

        assertThat(environment.getProperty("spring.datasource.url"))
                .isEqualTo("jdbc:postgresql://host/db?sslmode=require");
    }

    @Test
    @DisplayName("an explicit SPRING_DATASOURCE_URL wins over DATABASE_URL")
    void explicitOverrideWins()
    {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("DATABASE_URL", "postgres://u:p@ignored/db")
                .withProperty("SPRING_DATASOURCE_URL", "jdbc:postgresql://chosen:5432/wallet");

        processor.postProcessEnvironment(environment, null);

        // Nothing is translated, so Spring's own relaxed binding of the
        // SPRING_DATASOURCE_URL variable supplies spring.datasource.url
        // unopposed. The failure this guards against is DATABASE_URL silently
        // overriding a deliberately configured datasource.
        assertThat(environment.getProperty("spring.datasource.url")).isNull();
    }

    @Test
    @DisplayName("a DATABASE_URL already in JDBC form is passed through")
    void passesThroughJdbcForm()
    {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("DATABASE_URL", "jdbc:postgresql://host:5432/db");

        processor.postProcessEnvironment(environment, null);

        assertThat(environment.getProperty("spring.datasource.url"))
                .isEqualTo("jdbc:postgresql://host:5432/db");
    }

    @Test
    @DisplayName("no DATABASE_URL leaves the environment untouched")
    void doesNothingWithoutDatabaseUrl()
    {
        MockEnvironment environment = new MockEnvironment();

        processor.postProcessEnvironment(environment, null);

        assertThat(environment.getProperty("spring.datasource.url")).isNull();
    }
}
