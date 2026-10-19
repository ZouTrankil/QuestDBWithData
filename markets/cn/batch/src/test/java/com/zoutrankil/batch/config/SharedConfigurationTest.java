package com.zoutrankil.batch.config;

import com.zoutrankil.batch.storage.BatchMetadataStore;
import com.zoutrankil.batch.schedule.ScheduleCatalog;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import static org.junit.jupiter.api.Assertions.*;

class SharedConfigurationTest {
    @Test void batchLoggingAndInitialSchedulesCanBeOverriddenPerProcess() {
        var defaults=environment(Map.of());
        assertEquals("logs/questdb-batch/application.log",defaults.getRequiredProperty("logging.file.name"));
        assertEquals("0 30 18 ? * MON-FRI",ScheduleCatalog.configured(defaults).get("post_close"));
        var custom=environment(Map.of("LOGGING_FILE_PATH","/fixture/batch-logs","LOGGING_LEVEL_ROOT","WARN"),
                Map.of("jdb.schedules[post_close]","0 0 19 ? * MON-FRI"));
        assertEquals("/fixture/batch-logs/application.log",custom.getRequiredProperty("logging.file.name"));
        assertEquals("WARN",custom.getRequiredProperty("logging.level.root"));
        assertEquals("0 0 19 ? * MON-FRI",ScheduleCatalog.configured(custom).get("post_close"));
        assertThrows(IllegalArgumentException.class,() -> ScheduleCatalog.configured(
                environment(Map.of(),Map.of("jdb.schedules[post_close]","invalid"))));
    }

    @Test void batchInheritsCommonSettingsWithoutMainServiceDatasourceOrScheduler() {
        var env = environment(Map.of("APP_QUESTDB_HOST", "shared-db", "APP_TUSHARE_TOKEN", "fixture-token"));
        assertEquals("http://shared-db:9000", env.getRequiredProperty("jdb.questdb-http-url"));
        assertEquals("fixture-token", env.getRequiredProperty("jdb.tushare-token"));
        assertEquals(env.getRequiredProperty("app.questdb.poll-interval"), env.getRequiredProperty("jdb.questdb-poll-interval"));
        assertFalse(env.containsProperty("spring.datasource.url"));
        assertFalse(env.containsProperty("app.sync.batch.enabled"));
        assertFalse(env.containsProperty("l2.root"));
        assertEquals("none", env.getRequiredProperty("spring.main.web-application-type"));
    }

    @Test void batchCanOverrideCommonConnectionAndTokenForItsOwnInstance() {
        var env = environment(Map.of("APP_QUESTDB_HOST", "shared-db", "APP_TUSHARE_TOKEN", "shared-token",
                "JDB_QUESTDB_HTTP_URL", "http://isolated-db:19000", "JDB_TUSHARE_TOKEN", "batch-token",
                "JDB_API_PORT", "19085"));
        assertEquals("http://isolated-db:19000", env.getRequiredProperty("jdb.questdb-http-url"));
        assertEquals("batch-token", env.getRequiredProperty("jdb.tushare-token"));
        assertEquals("http://localhost:19085", env.getRequiredProperty("jdb.api-url"));
        assertEquals("shared-db", env.getRequiredProperty("app.questdb.host"));
    }

    @Test void batchSourceClientUsesSharedUrlTimeoutsAndQuotaWithItsOwnTokenOverride() {
        var env = environment(Map.of("APP_TUSHARE_APIURL", "https://fixture.invalid/api",
                "APP_TUSHARE_CONNECTTIMEOUT", "2s", "APP_TUSHARE_GLOBALPERMINUTE", "7",
                "JDB_TUSHARE_TOKEN", "batch-fixture"));
        var properties = new SourceRuntimeConfiguration().sourceTushareProperties(env);
        assertEquals(java.net.URI.create("https://fixture.invalid/api"), properties.getApiUrl());
        assertEquals(java.time.Duration.ofSeconds(2), properties.getConnectTimeout());
        assertEquals(7, properties.getGlobalPerMinute());
        assertEquals("batch-fixture", properties.getToken());
    }

    private StandardEnvironment environment(Map<String, Object> variables) {
        return environment(variables, Map.of());
    }

    private StandardEnvironment environment(Map<String, Object> variables, Map<String, Object> overrides) {
        var env = new StandardEnvironment();
        env.getPropertySources().replace(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME,
                new MapPropertySource(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME,
                        Map.of("spring.config.location", "file:./config/application.yml")));
        env.getPropertySources().replace(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                new SystemEnvironmentPropertySource(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, variables));
        env.getPropertySources().addFirst(new MapPropertySource("explicit-config-overrides", overrides));
        org.springframework.boot.context.properties.source.ConfigurationPropertySources.attach(env);
        ConfigDataEnvironmentPostProcessor.applyTo(env, null, null, "batch");
        return env;
    }
    @Test void dedicatedMetadataDatabaseRequired() {
        assertThrows(IllegalArgumentException.class,() -> BatchMetadataStore.validateMetadataUrl("jdbc:postgresql://100.97.201.8:8812/qdb"));
        assertThrows(IllegalArgumentException.class,() -> BatchMetadataStore.validateMetadataUrl("jdbc:sqlite::memory:"));
        BatchMetadataStore.validateMetadataUrl("jdbc:sqlite:./var/jdb-test.sqlite");
    }
}
