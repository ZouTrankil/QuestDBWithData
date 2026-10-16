package com.zoutrankil.data.bootstrap;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class ProjectConfigurationTest {
    @TempDir Path directory;

    @Test void commonSettingsAndAnEnvironmentProfileFeedDataConnectionsAndPaths() throws Exception {
        Path file = configuration("""
                ---
                spring.config.activate.on-profile: fixture
                app:
                  questdb:
                    host: fixture-db
                    pg-port: 18812
                    qwp-port: 19000
                    database: fixture
                  paths:
                    research-root: /fixture/research
                """);
        var env = ProjectConfiguration.load("data", Map.of("SPRING_CONFIG_LOCATION", file.toUri().toString(),
                "SPRING_PROFILES_ACTIVE", "fixture"));
        assertEquals("jdbc:postgresql://fixture-db:18812/fixture?sslmode=disable", env.getRequiredProperty("spring.datasource.url"));
        assertEquals("http://fixture-db:19000", env.getRequiredProperty("app.questdb.http-url"));
        assertEquals("/fixture/research/artifacts/quant_platform", env.getRequiredProperty("app.sync.batch.research-root"));
        assertEquals("/fixture/research/artifacts/level2_t0_dataset", env.getRequiredProperty("app.sync.l2-daily-features.dataset-root"));
        assertFalse(env.containsProperty("jdb.metadata-url"));
        assertFalse(env.containsProperty("l2.root"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"PGPORT", "PG_PORT"})
    void environmentOverridesYamlAndPropagatesToAllDerivedEndpoints(String portKey) throws Exception {
        Path file = configuration("");
        var env = ProjectConfiguration.load("data", Map.of("SPRING_CONFIG_LOCATION", file.toUri().toString(),
                "APP_QUESTDB_HOST", "env-db", "APP_QUESTDB_" + portKey, "28812", "APP_QUESTDB_QWPPORT", "29000"));
        assertEquals("jdbc:postgresql://env-db:28812/qdb?sslmode=disable", env.getRequiredProperty("spring.datasource.url"));
        assertEquals("http://env-db:29000", env.getRequiredProperty("app.questdb.http-url"));
        assertEquals(28812, org.springframework.boot.context.properties.bind.Binder.get(env)
                .bind("app.questdb", com.zoutrankil.data.config.QuestDbProperties.class).get().getPgPort());
    }

    private Path configuration(String suffix) throws Exception {
        try (var stream = getClass().getResourceAsStream("/application.yml")) {
            assertNotNull(stream);
            return Files.writeString(directory.resolve("application.yml"), new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8) + suffix);
        }
    }
}
