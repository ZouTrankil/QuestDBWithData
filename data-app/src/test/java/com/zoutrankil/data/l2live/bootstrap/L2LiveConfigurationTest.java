package com.zoutrankil.data.l2live.bootstrap;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class L2LiveConfigurationTest {
    @TempDir Path root;

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
