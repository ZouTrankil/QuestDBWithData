package com.zoutrankil.data.bootstrap;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.Banner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import static org.junit.jupiter.api.Assertions.*;

class QuestDataStartupTest {
    @TempDir Path directory;
    @Configuration(proxyBeanMethods = false) static class EmptyConfiguration {}

    private final class Fixture implements AutoCloseable {
        final AtomicReference<WebApplicationType> selected = new AtomicReference<>();
        final List<String> lifecycle = new ArrayList<>();
        final SpringApplication application = new SpringApplication(EmptyConfiguration.class);
        ConfigurableApplicationContext context;

        Fixture() { this(Map.of(), Map.of()); }
        Fixture(Map<String, Object> properties, Map<String, Object> variables) {
            var environment = new StandardEnvironment();
            environment.getPropertySources().replace(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME,
                    new MapPropertySource(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME, properties));
            environment.getPropertySources().replace(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                    new SystemEnvironmentPropertySource(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, variables));
            application.setEnvironment(environment);
            application.setBannerMode(Banner.Mode.OFF);
            application.setLogStartupInfo(false);
            application.setRegisterShutdownHook(false);
            application.setDefaultProperties(Map.of("spring.config.location", directory.toUri().toString()));
            application.setApplicationContextFactory(type -> {
                selected.set(type);
                return new AnnotationConfigApplicationContext();
            });
            application.addInitializers(created -> {
                created.addApplicationListener(event -> {
                    if (event instanceof ContextClosedEvent) lifecycle.add("closed");
                });
                created.getBeanFactory().registerSingleton("modeTestRunner", (ApplicationRunner) args -> lifecycle.add("ran"));
            });
        }

        void run(String... args) { context = QuestDataStartup.run(application, args); }
        @Override public void close() { if (context != null) context.close(); }
    }

    @Test void noCommandDefaultsToReactiveAndRetainsTheContext() {
        try (var fixture = new Fixture()) {
            fixture.run();
            assertEquals(WebApplicationType.REACTIVE, fixture.selected.get());
            assertTrue(fixture.context.isActive());
            assertEquals(List.of("ran"), fixture.lifecycle);
            assertEquals("REACTIVE", fixture.context.getEnvironment().getProperty("spring.main.web-application-type"));
        }
    }

    @Test void cliCommandOverridesEnabledWebAndClosesOnlyAfterRunners() throws Exception {
        Files.writeString(directory.resolve("application.properties"), "app.web.enabled=true\n");
        try (var fixture = new Fixture()) {
            fixture.run("plan-sync-job", "--job", "data.stock_basic");
            assertEquals(WebApplicationType.NONE, fixture.selected.get());
            assertFalse(fixture.context.isActive());
            assertEquals(List.of("ran", "closed"), fixture.lifecycle);
        }
    }

    @Test void externalConfigurationCanDisableWebWithoutBusinessArguments() throws Exception {
        Path external = Files.writeString(directory.resolve("external.properties"), "app.web.enabled=false\n");
        try (var fixture = new Fixture()) {
            fixture.run("--spring.config.location=" + external.toUri());
            assertEquals(WebApplicationType.NONE, fixture.selected.get());
            assertFalse(fixture.context.isActive());
        }
    }

    @Test void activeProfileOverridesBaseConfigurationAfterConfigData() throws Exception {
        Files.writeString(directory.resolve("application.properties"), "app.web.enabled=true\n");
        Files.writeString(directory.resolve("application-cli.properties"), "app.web.enabled=false\n");
        try (var fixture = new Fixture()) {
            fixture.run("--spring.profiles.active=cli");
            assertEquals(WebApplicationType.NONE, fixture.selected.get());
            assertArrayEquals(new String[]{"cli"}, fixture.context.getEnvironment().getActiveProfiles());
        }
    }

    @Test void environmentVariableOverridesExternalConfiguration() throws Exception {
        Files.writeString(directory.resolve("application.properties"), "app.web.enabled=false\n");
        try (var fixture = new Fixture(Map.of(), Map.of("APP_WEB_ENABLED", "true"))) {
            fixture.run();
            assertEquals(WebApplicationType.REACTIVE, fixture.selected.get());
        }
    }

    @Test void systemPropertyOverridesEnvironmentVariable() {
        try (var fixture = new Fixture(Map.of("app.web.enabled", "false"), Map.of("APP_WEB_ENABLED", "true"))) {
            fixture.run();
            assertEquals(WebApplicationType.NONE, fixture.selected.get());
        }
    }

    @Test void commandLineConfigurationOverridesSystemAndEnvironmentProperties() {
        try (var fixture = new Fixture(Map.of("app.web.enabled", "false"), Map.of("APP_WEB_ENABLED", "false"))) {
            fixture.run("--app.web.enabled=true", "--logging.level.root=ERROR");
            assertEquals(WebApplicationType.REACTIVE, fixture.selected.get());
        }
    }

    @Test void explicitWebOverridesTheDefaultSwitch() {
        try (var fixture = new Fixture()) {
            fixture.run("--web", "--app.web.enabled=false");
            assertEquals(WebApplicationType.REACTIVE, fixture.selected.get());
            assertTrue(fixture.context.isActive());
        }
    }

    @Test void explicitWebCannotAccompanyACommand() {
        try (var fixture = new Fixture()) {
            assertThrows(IllegalArgumentException.class, () -> fixture.run("--web", "show-sync-history"));
            assertNull(fixture.selected.get());
            assertTrue(fixture.lifecycle.isEmpty());
        }
    }

    @Test void matchingExplicitSpringModeIsAllowedForBothModes() {
        try (var cli = new Fixture(); var web = new Fixture()) {
            cli.run("--spring.main.web-application-type=none", "plan-sync-job");
            web.run("--spring.main.web-application-type=reactive");
            assertEquals(WebApplicationType.NONE, cli.selected.get());
            assertEquals(WebApplicationType.REACTIVE, web.selected.get());
        }
    }

    @Test void conflictingSpringModesAndServletAreRejectedBeforeContextCreation() {
        for (String[] args : List.of(
                new String[]{"plan-sync-job", "--spring.main.web-application-type=reactive"},
                new String[]{"--spring.main.web-application-type=none"},
                new String[]{"--web", "--spring.main.web-application-type=none"},
                new String[]{"--spring.main.web-application-type=servlet"},
                new String[]{"plan-sync-job", "--spring.main.web-application-type=servlet"})) {
            try (var fixture = new Fixture()) {
                assertThrows(IllegalArgumentException.class, () -> fixture.run(args));
                assertNull(fixture.selected.get());
            }
        }
    }

    @Test void conflictingSpringModeFromEnvironmentIsAlsoRejected() {
        try (var fixture = new Fixture(Map.of(), Map.of("SPRING_MAIN_WEB_APPLICATION_TYPE", "reactive"))) {
            assertThrows(IllegalArgumentException.class, () -> fixture.run("plan-sync-job"));
            assertNull(fixture.selected.get());
        }
    }

    @Test void invalidDefaultSwitchIsRejectedInsteadOfSilentlyDisablingWeb() {
        try (var fixture = new Fixture()) {
            var failure = assertThrows(org.springframework.core.convert.ConversionFailedException.class,
                    () -> fixture.run("--app.web.enabled=invalid"));
            assertEquals(2, com.zoutrankil.data.cli.CliExitStatus.failureCode(failure));
            assertNull(fixture.selected.get());
        }
    }

    @Test void startupPolicyIsNotInstalledGloballyOnOtherApplications() {
        try (var fixture = new Fixture()) {
            fixture.application.setWebApplicationType(WebApplicationType.NONE);
            fixture.context = fixture.application.run();
            assertEquals(WebApplicationType.NONE, fixture.selected.get());
            assertNull(fixture.context.getEnvironment().getPropertySources().get("questDataRuntimeMode"));
        }
    }
}
