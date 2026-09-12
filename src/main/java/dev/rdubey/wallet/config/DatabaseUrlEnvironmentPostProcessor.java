package dev.rdubey.wallet.config;

import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.net.URI;
import java.util.HashMap;
import java.util.Map;

/**
 * Translates the {@code DATABASE_URL} that managed Postgres providers hand out
 * ({@code postgres://user:pass@host:port/db}) into the JDBC form Spring needs.
 * <p>
 * Without this the same deployment has to be configured twice, by hand, and a
 * typo surfaces only as a failure to start. Explicitly set
 * {@code SPRING_DATASOURCE_URL} still wins.
 */
public class DatabaseUrlEnvironmentPostProcessor implements EnvironmentPostProcessor
{
    private static final String PROPERTY_SOURCE_NAME = "databaseUrlTranslation";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application)
    {
        String databaseUrl = environment.getProperty("DATABASE_URL");
        if (databaseUrl == null || databaseUrl.isBlank())
        {
            return;
        }

        // An explicit SPRING_DATASOURCE_URL always wins. The resolved
        // spring.datasource.url is not consulted, because the shipped
        // configuration always supplies a local default for it and testing
        // that would make this translation dead code.
        String explicitOverride = environment.getProperty("SPRING_DATASOURCE_URL");
        if (explicitOverride != null && !explicitOverride.isBlank())
        {
            return;
        }

        if (databaseUrl.startsWith("jdbc:"))
        {
            environment.getPropertySources().addFirst(new MapPropertySource(
                    PROPERTY_SOURCE_NAME, Map.of("spring.datasource.url", databaseUrl)));
            return;
        }

        try
        {
            URI uri = URI.create(databaseUrl);
            Map<String, Object> translated = new HashMap<>();
            translated.put("spring.datasource.url", jdbcUrl(uri));

            String userInfo = uri.getUserInfo();
            if (userInfo != null && !userInfo.isBlank())
            {
                String[] credentials = userInfo.split(":", 2);
                translated.put("spring.datasource.username", credentials[0]);
                if (credentials.length == 2)
                {
                    translated.put("spring.datasource.password", credentials[1]);
                }
            }
            environment.getPropertySources()
                       .addFirst(new MapPropertySource(PROPERTY_SOURCE_NAME, translated));
        }
        catch (IllegalArgumentException e)
        {
            throw new IllegalStateException("DATABASE_URL is not a valid URL", e);
        }
    }

    private static String jdbcUrl(URI uri)
    {
        StringBuilder url = new StringBuilder("jdbc:postgresql://").append(uri.getHost());
        if (uri.getPort() > 0)
        {
            url.append(':').append(uri.getPort());
        }
        url.append(uri.getPath());
        if (uri.getQuery() != null && !uri.getQuery().isBlank())
        {
            url.append('?').append(uri.getQuery());
        }
        return url.toString();
    }
}
