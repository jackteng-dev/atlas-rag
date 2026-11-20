package dev.knowledge.platform.core;

import java.util.Map;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.*;

/**
 * Keeps the legacy single-instance mode compatible: unless the {@code platform} profile is active, the database,
 * migration, session and actuator auto-configuration added for the platform stay switched off.
 */
public class ProfileDefaults implements EnvironmentPostProcessor, Ordered {
    static final String EXCLUDED = String.join(",",
            "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration",
            "org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration",
            "org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration",
            "org.springframework.boot.autoconfigure.jdbc.JdbcClientAutoConfiguration",
            "org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration",
            "org.springframework.boot.autoconfigure.session.SessionAutoConfiguration");

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        if (environment.acceptsProfiles(Profiles.of("platform"))) return;
        environment.getPropertySources().addLast(new MapPropertySource("atlasLegacyDefaults", Map.of(
                "spring.autoconfigure.exclude", EXCLUDED,
                "management.endpoints.web.exposure.exclude", "*",
                "management.endpoints.access.default", "none")));
    }

    @Override
    public int getOrder() { return Ordered.LOWEST_PRECEDENCE; }
}
