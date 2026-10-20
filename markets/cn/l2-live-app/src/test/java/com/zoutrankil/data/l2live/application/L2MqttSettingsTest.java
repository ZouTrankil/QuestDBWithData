package com.zoutrankil.data.l2live.application;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

class L2MqttSettingsTest {
    @Test
    void identityTopicsAndSecretSafeRepresentationUseExplicitSymbols() throws Exception {
        var symbols = new ArrayList<>(List.of("563360.SZ", "159242", "563360.SH"));
        var settings =
                new L2MqttSettings(
                        L2MqttSettings.DEFAULT_BROKER_URI,
                        "fixture_account",
                        "fixture_secret",
                        symbols,
                        48,
                        20);
        symbols.clear();
        assertEquals("fixture_account", settings.clientId());
        assertEquals(List.of("563360.SH", "159242.SZ"), settings.symbols());
        assertEquals(
                List.of(
                        "trans/563360",
                        "order/563360",
                        "market/563360",
                        "trans/159242",
                        "order/159242",
                        "market/159242"),
                settings.topics());
        assertFalse(settings.toString().contains("fixture_account"));
        assertFalse(settings.toString().contains("fixture_secret"));
        String json = new ObjectMapper().writeValueAsString(settings);
        assertFalse(json.contains("fixture_account"));
        assertFalse(json.contains("fixture_secret"));
        assertThrows(
                UnsupportedOperationException.class, () -> settings.symbols().add("588010.SH"));
    }

    @Test
    void unsafeBrokerPublicAccountMissingCredentialsAndExcessQuotaAreRefusedWithoutEcho() {
        for (String broker :
                List.of(
                        "tcp://fixture_account:fixture_secret@127.0.0.1:1883",
                        "tcp://127.0.0.1:1883?password=fixture_secret",
                        "ws://127.0.0.1:1883",
                        "tcp://127.0.0.1:1883/path")) {
            var error =
                    assertThrows(
                            IllegalArgumentException.class,
                            () ->
                                    new L2MqttSettings(
                                            broker,
                                            "fixture_account",
                                            "fixture_secret",
                                            List.of("563360.SH"),
                                            48,
                                            20));
            assertFalse(error.toString().contains("fixture_secret"));
            assertNull(error.getCause());
        }
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new L2MqttSettings(
                                L2MqttSettings.DEFAULT_BROKER_URI,
                                "level2_test1",
                                "fixture_secret",
                                List.of("563360.SH"),
                                48,
                                20));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new L2MqttSettings(
                                L2MqttSettings.DEFAULT_BROKER_URI,
                                "fixture_account",
                                "",
                                List.of("563360.SH"),
                                48,
                                20));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new L2MqttSettings(
                                L2MqttSettings.DEFAULT_BROKER_URI,
                                "fixture_account",
                                "fixture_secret",
                                List.of("563360.SH", "588010.SH"),
                                3,
                                20));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new L2MqttSettings(
                                L2MqttSettings.DEFAULT_BROKER_URI,
                                "fixture_account",
                                "fixture_secret",
                                List.of(),
                                48,
                                20));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new L2MqttSettings(
                                L2MqttSettings.DEFAULT_BROKER_URI,
                                "fixture_account",
                                "fixture_secret",
                                List.of("563360.SH"),
                                50,
                                20));
    }

    @Test
    void environmentIdentityMustAgreeAndMalformedNumericValuesDoNotEscape() {
        var env =
                new HashMap<>(
                        Map.of(
                                "ALIYUN_L2_CLIENT_ID",
                                "fixture_account",
                                "ALIYUN_L2_PASSWORD",
                                "fixture_secret"));
        assertEquals(
                "fixture_account",
                L2MqttSettings.fromEnvironment(env, List.of("563360.SH")).username());
        env.put("ALIYUN_L2_USERNAME", "different_fixture_account");
        assertThrows(
                IllegalArgumentException.class,
                () -> L2MqttSettings.fromEnvironment(env, List.of("563360.SH")));
        env.remove("ALIYUN_L2_USERNAME");
        env.put("ALIYUN_L2_MAX_TOPICS", "fixture_secret");
        var error =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> L2MqttSettings.fromEnvironment(env, List.of("563360.SH")));
        assertFalse(error.toString().contains("fixture_secret"));
        assertNull(error.getCause());
    }

    @Test
    void receiveEnvelopeDefensivelyCopiesExactBinaryPayload() {
        byte[] payload = {0, -1, 2};
        var received =
                new ReceivedMessage(
                        "market/563360",
                        payload,
                        Instant.parse("2026-10-14T02:00:00Z"),
                        "session-fixture",
                        0,
                        true,
                        true);
        payload[0] = 10;
        byte[] returned = received.payload();
        returned[1] = 9;
        assertArrayEquals(new byte[] {0, -1, 2}, received.payload());
        assertTrue(received.retained());
        assertTrue(received.duplicate());
        assertFalse(received.subscriptionAuthorized());
        var authorized =
                new ReceivedMessage(
                        "market/563360",
                        received.payload(),
                        received.receivedAt(),
                        received.sessionId(),
                        0,
                        true,
                        true,
                        true);
        assertTrue(authorized.subscriptionAuthorized());
        assertArrayEquals(new byte[] {0, -1, 2}, authorized.payload());
    }
}
