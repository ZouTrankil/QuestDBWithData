package com.zoutrankil.data.bootstrap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.zoutrankil.data.config.QuestDbProperties;
import com.zoutrankil.runtime.ProjectConfiguration;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.bind.Binder;

import java.net.Proxy.Type;
import java.net.ProxySelector;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

class ProjectConfigurationTest {
    @TempDir Path directory;
    private Map<String, String> loggingProperties;

    @BeforeEach
    void isolateBootLoggingSystemProperties() {
        loggingProperties = new HashMap<>();
        for (String key :
                List.of(
                        "LOG_FILE",
                        "LOG_PATH",
                        "http.nonProxyHosts",
                        "socksNonProxyHosts",
                        "socksProxyHost",
                        "socksProxyPort")) {
            String value = System.getProperty(key);
            if (value != null) loggingProperties.put(key, value);
            System.clearProperty(key);
        }
    }

    @AfterEach
    void restoreBootLoggingSystemProperties() {
        for (String key :
                List.of(
                        "LOG_FILE",
                        "LOG_PATH",
                        "http.nonProxyHosts",
                        "socksNonProxyHosts",
                        "socksProxyHost",
                        "socksProxyPort")) System.clearProperty(key);
        loggingProperties.forEach(System::setProperty);
    }

    @ParameterizedTest
    @ValueSource(strings = {"data", "l2"})
    void sharedLoggingUsesEachProcessNameAndAllowsDeploymentOverrides(String profile)
            throws Exception {
        var file = configuration("");
        var defaults =
                ProjectConfiguration.load(
                        profile, Map.of("SPRING_CONFIG_LOCATION", file.toUri().toString()));
        assertEquals(
                "logs/questdb-" + profile + "/application.log",
                defaults.getRequiredProperty("logging.file.name"));
        var custom =
                ProjectConfiguration.load(
                        profile,
                        Map.of(
                                "SPRING_CONFIG_LOCATION",
                                file.toUri().toString(),
                                "LOGGING_FILE_NAME",
                                "/fixture/collector.log",
                                "LOGGING_LOGBACK_ROLLINGPOLICY_MAX_HISTORY",
                                "7"));
        assertEquals("/fixture/collector.log", custom.getRequiredProperty("logging.file.name"));
        assertEquals("7", custom.getRequiredProperty("logging.logback.rollingpolicy.max-history"));
    }

    @Test
    void commonSettingsAndAnEnvironmentProfileFeedDataConnectionsAndPaths() throws Exception {
        Path file =
                configuration(
                        """
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
        var env =
                ProjectConfiguration.load(
                        "data",
                        Map.of(
                                "SPRING_CONFIG_LOCATION",
                                file.toUri().toString(),
                                "SPRING_PROFILES_ACTIVE",
                                "fixture"));
        assertEquals(
                "jdbc:postgresql://fixture-db:18812/fixture?sslmode=disable",
                env.getRequiredProperty("spring.datasource.url"));
        assertEquals("http://fixture-db:19000", env.getRequiredProperty("app.questdb.http-url"));
        assertEquals(
                "/fixture/research/artifacts/quant_platform",
                env.getRequiredProperty("app.sync.batch.research-root"));
        assertEquals(
                "/fixture/research/artifacts/level2_t0_dataset",
                env.getRequiredProperty("app.sync.l2-daily-features.dataset-root"));
        assertEquals(
                "jdbc:sqlite:./var/jdb-metadata.sqlite",
                env.getRequiredProperty("jdb.metadata-url"));
        assertFalse(env.getProperty("jdb.enabled", Boolean.class));
        assertFalse(env.containsProperty("l2.root"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"PGPORT", "PG_PORT"})
    void environmentOverridesYamlAndPropagatesToAllDerivedEndpoints(String portKey)
            throws Exception {
        Path file = configuration("");
        var env =
                ProjectConfiguration.load(
                        "data",
                        Map.of(
                                "SPRING_CONFIG_LOCATION",
                                file.toUri().toString(),
                                "APP_QUESTDB_HOST",
                                "env-db",
                                "APP_QUESTDB_" + portKey,
                                "28812",
                                "APP_QUESTDB_QWPPORT",
                                "29000"));
        assertEquals(
                "jdbc:postgresql://env-db:28812/qdb?sslmode=disable",
                env.getRequiredProperty("spring.datasource.url"));
        assertEquals("http://env-db:29000", env.getRequiredProperty("app.questdb.http-url"));
        assertEquals(
                28812,
                Binder.get(env).bind("app.questdb", QuestDbProperties.class).get().getPgPort());
    }

    @Test
    void configuredDatabaseBypassesProxyWhileOtherSocketDestinationsKeepUsingIt() throws Exception {
        System.setProperty("socksProxyHost", "127.0.0.1");
        System.setProperty("socksProxyPort", "9");
        System.setProperty("http.nonProxyHosts", "localhost|*.existing.invalid");
        System.setProperty("socksNonProxyHosts", "localhost|127.*");
        var file = configuration("");
        ProjectConfiguration.load(
                "data",
                Map.of(
                        "SPRING_CONFIG_LOCATION",
                        file.toUri().toString(),
                        "APP_QUESTDB_HOST",
                        "100.64.153.106"));
        assertEquals(
                "localhost|*.existing.invalid|100.64.153.106",
                System.getProperty("http.nonProxyHosts"));
        assertEquals("localhost|127.*|100.64.153.106", System.getProperty("socksNonProxyHosts"));
        var selector = ProxySelector.getDefault();
        assertEquals(
                Type.DIRECT,
                selector.select(URI.create("socket://100.64.153.106:8812")).getFirst().type());
        assertEquals(
                Type.SOCKS,
                selector.select(URI.create("socket://unrelated.invalid:8812")).getFirst().type());
    }

    private Path configuration(String suffix) throws Exception {
        // 外部配置是一整棵目录；导入路径要在 jar 和部署目录里都能解析。
        for (String folder : List.of("markets", "apps")) {
            try (var files = Files.walk(Path.of("config", folder))) {
                for (Path source : files.filter(Files::isRegularFile).toList()) {
                    String relative =
                            Path.of("config").relativize(source).toString().replace('\\', '/');
                    try (var input = getClass().getResourceAsStream("/" + relative)) {
                        assertNotNull(input, relative);
                        Path target = directory.resolve(relative);
                        Files.createDirectories(target.getParent());
                        Files.copy(input, target, StandardCopyOption.REPLACE_EXISTING);
                    }
                }
            }
        }
        try (var stream = getClass().getResourceAsStream("/application.yml")) {
            assertNotNull(stream);
            return Files.writeString(
                    directory.resolve("application.yml"),
                    new String(stream.readAllBytes(), StandardCharsets.UTF_8) + suffix);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"data", "batch", "l2", "main-strategy"})
    void privateFileOverridesEveryProfileAndEnvironmentStillWins(String profile) throws Exception {
        Path file = configuration("");
        Path local =
                Files.writeString(
                        directory.resolve("private.yml"),
                        """
                app.tushare.token: fixture-source
                app.questdb.password: fixture-password
                app.web.api-token: fixture-data
                jdb.api-token: fixture-batch
                l2.username: fixture-l2
                """);
        var properties =
                Map.of(
                        "SPRING_CONFIG_LOCATION",
                        file.toUri().toString(),
                        "APP_LOCAL_CONFIG",
                        local.toUri().toString());
        var loaded = ProjectConfiguration.load(profile, properties);
        assertEquals("fixture-source", loaded.getRequiredProperty("app.tushare.token"));
        assertEquals("fixture-password", loaded.getRequiredProperty("app.questdb.password"));
        assertEquals("fixture-data", loaded.getRequiredProperty("app.web.api-token"));
        assertEquals("fixture-batch", loaded.getRequiredProperty("jdb.api-token"));
        assertEquals("fixture-l2", loaded.getRequiredProperty("l2.username"));
        var override = new HashMap<>(properties);
        override.put("APP_TUSHARE_TOKEN", "environment-source");
        assertEquals(
                "environment-source",
                ProjectConfiguration.load(profile, override)
                        .getRequiredProperty("app.tushare.token"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"market-us", "market-btc"})
    void otherMarketsDoNotInheritCnApplications(String profile) throws Exception {
        var file = configuration("");
        var env =
                ProjectConfiguration.load(
                        profile,
                        Map.of(
                                "SPRING_CONFIG_LOCATION",
                                file.toUri().toString(),
                                "APP_LOCAL_CONFIG",
                                "optional:file:" + directory.resolve("absent.yml")));
        String market = profile.substring("market-".length());
        assertEquals(
                market.equals("us") ? "America/New_York" : "UTC",
                env.getRequiredProperty("app.time.markets." + market));
        for (String key :
                List.of(
                        "app.paths.l2-dataset-root",
                        "l2.root",
                        "jdb.metadata-url",
                        "spring.datasource.url",
                        "app.sync.batch.enabled",
                        "app.sync.financial.enabled")) assertFalse(env.containsProperty(key), key);
    }

    @Test
    void mainStrategyProfileAloneLoadsItsDataBaseAndOverridesOnlyItsOwnSettings() throws Exception {
        var file = configuration("");
        var env =
                ProjectConfiguration.load(
                        "main-strategy",
                        Map.of(
                                "SPRING_CONFIG_LOCATION",
                                file.toUri().toString(),
                                "APP_LOCAL_CONFIG",
                                "optional:file:" + directory.resolve("absent.yml")));
        assertEquals("8081", env.getRequiredProperty("server.port"));
        assertEquals("true", env.getRequiredProperty("app.sync.batch.enabled"));
        assertEquals(
                "false", env.getRequiredProperty("app.materialized-views.etf-overview.enabled"));
        assertEquals("false", env.getRequiredProperty("app.sync.financial.enabled"));
        assertEquals("1000", env.getRequiredProperty("app.sync.financial.max-request-attempts"));
        assertEquals("PT4H", env.getRequiredProperty("app.sync.financial.execution-window"));
        assertEquals("Asia/Shanghai", env.getRequiredProperty("app.time.markets.cn"));
        assertNotNull(env.getProperty("spring.datasource.url"));
        assertFalse(env.containsProperty("l2.root"));
        assertEquals(
                "jdbc:sqlite:./var/jdb-metadata.sqlite",
                env.getRequiredProperty("jdb.metadata-url"));
        assertFalse(env.getProperty("jdb.enabled", Boolean.class));
    }

    @Test
    void coverageAssemblyDefaultsAndOverridesFollowTheMainStrategyAppConfig() throws Exception {
        var file = configuration("");
        var variables = new HashMap<String, String>();
        variables.put("SPRING_CONFIG_LOCATION", file.toUri().toString());
        variables.put("APP_LOCAL_CONFIG", "optional:file:" + directory.resolve("absent.yml"));
        var defaults = ProjectConfiguration.load("main-strategy", variables);
        assertEquals("v1", defaults.getRequiredProperty("app.sync.batch.input-schema"));
        assertEquals("", defaults.getRequiredProperty("app.sync.batch.coverage-inputs"));
        assertEquals("", defaults.getRequiredProperty("app.sync.batch.coverage-inputs-sha256"));
        variables.put("APP_SYNC_BATCH_INPUT_SCHEMA", "v2");
        variables.put("APP_SYNC_BATCH_COVERAGE_INPUTS", "/fixture/approved-inputs.json");
        variables.put("APP_SYNC_BATCH_COVERAGE_INPUTS_SHA256", "a".repeat(64));
        var custom = ProjectConfiguration.load("main-strategy", variables);
        assertEquals("v2", custom.getRequiredProperty("app.sync.batch.input-schema"));
        assertEquals(
                "/fixture/approved-inputs.json",
                custom.getRequiredProperty("app.sync.batch.coverage-inputs"));
        assertEquals(
                "a".repeat(64),
                custom.getRequiredProperty("app.sync.batch.coverage-inputs-sha256"));
        variables.remove("APP_SYNC_BATCH_INPUT_SCHEMA");
        variables.remove("APP_SYNC_BATCH_COVERAGE_INPUTS");
        variables.remove("APP_SYNC_BATCH_COVERAGE_INPUTS_SHA256");
        assertFalse(
                ProjectConfiguration.load("batch", variables)
                        .containsProperty("app.sync.batch.coverage-inputs"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"data", "main-strategy"})
    void etfAdjustmentLayoutUsesTheMarketDefaultAndEnvironmentOverride(String profile)
            throws Exception {
        var file = configuration("");
        var variables = new HashMap<String, String>();
        variables.put("SPRING_CONFIG_LOCATION", file.toUri().toString());
        variables.put("APP_LOCAL_CONFIG", "optional:file:" + directory.resolve("absent.yml"));
        assertEquals(
                "1",
                ProjectConfiguration.load(profile, variables)
                        .getRequiredProperty("app.sync.etf-adj-schema-version"));
        variables.put("APP_SYNC_ETF_ADJ_SCHEMA_VERSION", "2");
        assertEquals(
                "2",
                ProjectConfiguration.load(profile, variables)
                        .getRequiredProperty("app.sync.etf-adj-schema-version"));
        variables.remove("APP_SYNC_ETF_ADJ_SCHEMA_VERSION");
        assertFalse(
                ProjectConfiguration.load("market-us", variables)
                        .containsProperty("app.sync.etf-adj-schema-version"));
    }

    @Test
    void changingOneAppFileDoesNotChangeAnotherProcess() throws Exception {
        var file = configuration("");
        Files.writeString(
                directory.resolve("apps/cn-data.yml"),
                "\n---\nspring.config.activate.on-profile: data\nserver.port: 18080\n",
                StandardOpenOption.APPEND);
        var variables =
                Map.of(
                        "SPRING_CONFIG_LOCATION",
                        file.toUri().toString(),
                        "APP_LOCAL_CONFIG",
                        "optional:file:" + directory.resolve("absent.yml"));
        assertEquals(
                "18080", ProjectConfiguration.load("data", variables).getProperty("server.port"));
        assertEquals(
                "8081",
                ProjectConfiguration.load("main-strategy", variables).getProperty("server.port"));
        assertFalse(ProjectConfiguration.load("batch", variables).containsProperty("server.port"));
    }
}
