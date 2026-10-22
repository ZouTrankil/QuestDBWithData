package com.zoutrankil.market.us.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.zoutrankil.runtime.ProjectConfiguration;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.time.LocalTime;
import java.util.Map;
import java.util.zip.GZIPOutputStream;

class UsItchPrefixCollectorTest {
    @Test
    void rootConfigurationAndStandardOverridesKeepTheScanBounded() throws Exception {
        var s = UsItchPrefixCollector.settings(ProjectConfiguration.load("us-data", Map.of()));
        assertEquals(LocalTime.of(9, 31), s.time());
        assertEquals(8388608, s.rangeBytes());
        assertEquals(1073741824, s.maximumBytes());
        assertEquals(100000, s.count());
        var override =
                UsItchPrefixCollector.settings(
                        ProjectConfiguration.load(
                                "us-data",
                                Map.of(
                                        "APP_MARKET_DATA_ITCH_SAMPLE_MESSAGE_COUNT",
                                        "1000",
                                        "APP_MARKET_DATA_ITCH_REQUESTS_PER_HOUR",
                                        "12")));
        assertEquals(1000, override.count());
        assertEquals(12, override.policy().perHour());
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        UsItchPrefixCollector.settings(
                                ProjectConfiguration.load(
                                        "us-data",
                                        Map.of(
                                                "APP_MARKET_DATA_ITCH_SAMPLE_LOCAL_TIME",
                                                "09:29"))));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        UsItchPrefixCollector.settings(
                                ProjectConfiguration.load(
                                        "us-data",
                                        Map.of(
                                                "APP_MARKET_DATA_ITCH_PREFIX_LIMIT_BYTES",
                                                "2147483648"))));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        UsItchPrefixCollector.settings(
                                ProjectConfiguration.load(
                                        "us-data",
                                        Map.of(
                                                "APP_MARKET_DATA_ITCH_SAMPLE_MESSAGE_COUNT",
                                                "100001"))));
    }

    private byte[] sample(boolean truncated) throws Exception {
        var bytes = new ByteArrayOutputStream();
        try (var out = new DataOutputStream(new GZIPOutputStream(bytes))) {
            int[] sizes = {39, 36, 44};
            char[] types = {'R', 'A', 'P'};
            for (int i = 0; i < 3; i++) {
                byte[] p = new byte[sizes[i]];
                p[0] = (byte) types[i];
                long ns =
                        i == 2
                                ? LocalTime.of(9, 31).toNanoOfDay() + 123
                                : LocalTime.of(9, 29).toNanoOfDay();
                for (int j = 10; j >= 5; j--) {
                    p[j] = (byte) ns;
                    ns >>>= 8;
                }
                out.writeShort(p.length);
                out.write(p, 0, truncated && i == 2 ? p.length - 1 : p.length);
            }
        }
        return bytes.toByteArray();
    }

    @Test
    void locatesGlobalMessageIndexAndByteOffsetWithoutChangingNanoseconds() throws Exception {
        var scan =
                UsItchPrefixCollector.scan(
                        new ByteArrayInputStream(sample(false)), LocalTime.of(9, 31), 1, 10);
        assertEquals(3, scan.first());
        assertEquals(79, scan.firstOffset());
        assertEquals(125, scan.endOffset());
        assertEquals(LocalTime.of(9, 31).toNanoOfDay() + 123, scan.minimum());
        assertEquals(Map.of("P", 1), scan.types());
    }

    @Test
    void truncatedMessageAndExhaustedScanCannotBeDeclaredReady() throws Exception {
        assertThrows(
                EOFException.class,
                () ->
                        UsItchPrefixCollector.scan(
                                new ByteArrayInputStream(sample(true)),
                                LocalTime.of(9, 31),
                                1,
                                10));
        assertThrows(
                IOException.class,
                () ->
                        UsItchPrefixCollector.scan(
                                new ByteArrayInputStream(sample(false)),
                                LocalTime.of(9, 31),
                                1,
                                2));
    }
}
