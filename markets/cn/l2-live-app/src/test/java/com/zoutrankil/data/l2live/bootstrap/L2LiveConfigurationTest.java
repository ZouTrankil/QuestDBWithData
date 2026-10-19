package com.zoutrankil.data.l2live.bootstrap;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class L2LiveConfigurationTest {
    @TempDir Path root;

    @Test void unifiedYamlSuppliesSharedEndpointAndL2SettingsWhileOverridesStillWin() throws Exception {
        Path config = root.resolve("application.yml");
        for (String folder : java.util.List.of("markets", "apps")) {
            try (var files = Files.walk(Path.of("config", folder))) {
                for (Path source : files.filter(Files::isRegularFile).toList()) {
                    String relative = Path.of("config").relativize(source).toString().replace('\\', '/');
                    try (var input = getClass().getResourceAsStream("/" + relative)) {
                        assertNotNull(input, relative);
                        Path target = root.resolve(relative);
                        Files.createDirectories(target.getParent());
                        Files.copy(input, target);
                    }
                }
            }
        }
        try (var stream = getClass().getResourceAsStream("/application.yml")) {
            assertNotNull(stream);
            Files.writeString(config, new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8) + """
                    ---
                    spring.config.activate.on-profile: fixture
                    app.questdb.host: shared-db
                    app.questdb.qwp-port: 19000
                    app.questdb.poll-interval: 250ms
                    app.questdb.visibility-timeout: 12s
                    l2:
                      username: yaml-account
                      password: yaml-secret
                      broker: yaml-broker
                      batch-size: 42
                      symbols: 563360
                    """);
        }
        var variables = Map.of("SPRING_CONFIG_LOCATION", config.toUri().toString(), "SPRING_PROFILES_ACTIVE", "fixture");
        var yaml = L2LiveConfiguration.parse(new String[]{"plan", "--root", root.toString()}, variables);
        assertEquals("http://shared-db:19000", yaml.questDb);
        assertEquals(java.time.Duration.ofMillis(250), yaml.pollInterval);
        assertEquals(java.time.Duration.ofSeconds(12), yaml.visibilityTimeout);
        assertEquals("yaml-account", yaml.username);
        assertEquals(42, yaml.batchSize);
        assertEquals("tcp://yaml-broker:1883", yaml.brokerUri);
        Files.writeString(root.resolve("credentials.env"), "ALIYUN_L2_BROKER=file-broker\nALIYUN_L2_PASSWORD=file-secret\n");
        var overrides = new java.util.HashMap<>(variables);
        overrides.put("ALIYUN_L2_PASSWORD", "environment-secret");
        var overridden = L2LiveConfiguration.parse(new String[]{"plan", "--root", root.toString(), "--batch-size", "43"}, overrides);
        assertEquals("tcp://file-broker:1883", overridden.brokerUri);
        assertEquals("environment-secret", overridden.password);
        assertEquals(43, overridden.batchSize);
        assertFalse(overridden.plan().toString().contains("secret"));
    }

    @Test void planNeedsNoSecretAndDoesNotLeakConfiguredCredentials() throws Exception {
        var empty = L2LiveConfiguration.parse(new String[]{"plan", "--root", root.toString(), "--symbols", "563360"}, Map.of());
        assertEquals(false, empty.plan().get("credentialsConfigured"));
        assertEquals(java.util.List.of("563360.SH"), empty.symbols);
        var configured = L2LiveConfiguration.parse(new String[]{"plan", "--root", root.toString(), "--symbols", "563360"},
                Map.of("ALIYUN_L2_USERNAME", "private-fixture", "ALIYUN_L2_PASSWORD", "sensitive-fixture"));
        assertEquals(true, configured.plan().get("credentialsConfigured"));
        assertFalse(configured.plan().toString().contains("private-fixture"));
        assertFalse(configured.plan().toString().contains("sensitive-fixture"));
    }

    @Test void readsLiteralCredentialsWithEnvironmentPrecedenceWithoutShellExpansion() throws Exception {
        Files.writeString(root.resolve("credentials.env"), "ALIYUN_L2_USERNAME=private-fixture\nALIYUN_L2_PASSWORD='$(literal)#value'\n");
        var file = L2LiveConfiguration.parse(new String[]{"plan", "--root", root.toString(), "--symbols", "563360"}, Map.of());
        assertEquals("$(literal)#value", file.password);
        var env = L2LiveConfiguration.parse(new String[]{"plan", "--root", root.toString(), "--symbols", "563360"},
                Map.of("ALIYUN_L2_PASSWORD", "environment-wins"));
        assertEquals("environment-wins", env.password);
    }

    @Test void privateSubscriptionIsRequiredOnlyForLiveConnection() throws Exception {
        var absent = L2LiveConfiguration.parse(new String[]{"replay", "--root", root.toString(), "--symbols", "563360"}, Map.of());
        assertEquals("replay", absent.command);
        assertEquals("SUBSCRIPTION_CREDENTIALS_MISSING", assertThrows(IllegalArgumentException.class, absent::requireLiveCredentials).getMessage());
        var publicAccount = L2LiveConfiguration.parse(new String[]{"run", "--root", root.toString(), "--symbols", "563360"},
                Map.of("ALIYUN_L2_USERNAME", "level2_test2", "ALIYUN_L2_PASSWORD", "not-a-real-secret"));
        assertEquals("PUBLIC_TEST_ACCOUNT_NOT_ALLOWED", assertThrows(IllegalArgumentException.class, publicAccount::requireLiveCredentials).getMessage());
    }

    @Test void invalidBrokerCannotCarryAuthenticationOrWrongProtocol() {
        assertThrows(IllegalArgumentException.class, () -> L2LiveConfiguration.parse(
                new String[]{"plan", "--root", root.toString(), "--broker", "tcp://username:password@localhost:1883", "--symbols", "563360"}, Map.of()));
        assertThrows(IllegalArgumentException.class, () -> L2LiveConfiguration.parse(
                new String[]{"plan", "--root", root.toString(), "--broker", "http://localhost:1883", "--symbols", "563360"}, Map.of()));
    }

    @Test void rejectsAmbiguousScopeAndNumericBudgets() {
        for (String symbol : java.util.List.of("563360.SZ", "430047.BJ", "110001.SH", "563360,563360"))
            assertThrows(IllegalArgumentException.class, () -> L2LiveConfiguration.parse(
                    new String[]{"plan", "--root", root.toString(), "--symbols", symbol}, Map.of()));
        assertThrows(IllegalArgumentException.class, () -> L2LiveConfiguration.parse(
                new String[]{"plan", "--root", root.toString(), "--batch-size", "0"}, Map.of()));
        assertThrows(IllegalArgumentException.class, () -> L2LiveConfiguration.parse(
                new String[]{"plan", "--root", root.toString(), "--symbols", "563360", "--symbols", "563360"}, Map.of()));
    }
}
