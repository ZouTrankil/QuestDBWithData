package com.zoutrankil.data.bootstrap;

import com.zoutrankil.runtime.ProjectConfiguration;

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
    private java.util.Map<String,String> loggingProperties;
    @org.junit.jupiter.api.BeforeEach void isolateBootLoggingSystemProperties() {
        loggingProperties=new java.util.HashMap<>();
        for(String key:java.util.List.of("LOG_FILE","LOG_PATH","http.nonProxyHosts","socksNonProxyHosts","socksProxyHost","socksProxyPort")) {
            String value=System.getProperty(key);if(value!=null)loggingProperties.put(key,value);System.clearProperty(key);
        }
    }
    @org.junit.jupiter.api.AfterEach void restoreBootLoggingSystemProperties() {
        for(String key:java.util.List.of("LOG_FILE","LOG_PATH","http.nonProxyHosts","socksNonProxyHosts","socksProxyHost","socksProxyPort"))System.clearProperty(key);
        loggingProperties.forEach(System::setProperty);
    }


    @ParameterizedTest
    @ValueSource(strings = {"data", "l2"})
    void sharedLoggingUsesEachProcessNameAndAllowsDeploymentOverrides(String profile) throws Exception {
        var file=configuration("");
        var defaults=ProjectConfiguration.load(profile,Map.of("SPRING_CONFIG_LOCATION",file.toUri().toString()));
        assertEquals("logs/questdb-"+profile+"/application.log",defaults.getRequiredProperty("logging.file.name"));
        var custom=ProjectConfiguration.load(profile,Map.of("SPRING_CONFIG_LOCATION",file.toUri().toString(),
                "LOGGING_FILE_NAME","/fixture/collector.log","LOGGING_LOGBACK_ROLLINGPOLICY_MAX_HISTORY","7"));
        assertEquals("/fixture/collector.log",custom.getRequiredProperty("logging.file.name"));
        assertEquals("7",custom.getRequiredProperty("logging.logback.rollingpolicy.max-history"));
    }

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

    @Test void configuredDatabaseBypassesProxyWhileOtherSocketDestinationsKeepUsingIt() throws Exception {
        System.setProperty("socksProxyHost", "127.0.0.1");
        System.setProperty("socksProxyPort", "9");
        System.setProperty("http.nonProxyHosts", "localhost|*.existing.invalid");
        System.setProperty("socksNonProxyHosts", "localhost|127.*");
        var file=configuration("");
        ProjectConfiguration.load("data",Map.of("SPRING_CONFIG_LOCATION",file.toUri().toString(),
                "APP_QUESTDB_HOST","100.64.153.106"));
        assertEquals("localhost|*.existing.invalid|100.64.153.106",System.getProperty("http.nonProxyHosts"));
        assertEquals("localhost|127.*|100.64.153.106",System.getProperty("socksNonProxyHosts"));
        var selector=java.net.ProxySelector.getDefault();
        assertEquals(java.net.Proxy.Type.DIRECT,selector.select(java.net.URI.create("socket://100.64.153.106:8812")).getFirst().type());
        assertEquals(java.net.Proxy.Type.SOCKS,selector.select(java.net.URI.create("socket://unrelated.invalid:8812")).getFirst().type());
    }

    private Path configuration(String suffix) throws Exception {
        try (var stream = getClass().getResourceAsStream("/application.yml")) {
            assertNotNull(stream);
            return Files.writeString(directory.resolve("application.yml"), new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8) + suffix);
        }
    }
}
