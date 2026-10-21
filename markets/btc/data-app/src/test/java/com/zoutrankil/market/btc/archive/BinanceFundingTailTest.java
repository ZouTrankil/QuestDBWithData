package com.zoutrankil.market.btc.archive;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.zoutrankil.data.marketdata.VerifiedMarketDataStore;
import com.zoutrankil.market.btc.archive.mapper.BinanceFundingMapper;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;

class BinanceFundingTailTest {
    private static final LocalDate DAY = LocalDate.of(2026, 10, 1);

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void apiIntervalRemainsUnknownAndMillisecondEventIsPreserved() throws Exception {
        var rows =
                BinanceFundingMapper.map(
                        bytes(
                                "[{\"symbol\":\"BTCUSDT\",\"fundingTime\":1790812800001,\"fundingRate\":\"0.00007981\",\"rateType\":\"Regular\"}]"),
                        DAY,
                        DAY,
                        "a".repeat(64),
                        Instant.parse("2026-10-21T00:00:00Z"));
        assertEquals("2026-10-01T00:00:00.001Z", rows.getFirst().get("funding_time"));
        assertNull(rows.getFirst().get("funding_interval_hours"));
        var definition = BinanceArchiveDefinitions.definition(BinanceArchiveDataset.FUNDING);
        assertEquals(2, definition.schemaVersion());
        assertNull(
                VerifiedMarketDataStore.normalize(definition, rows.getFirst())
                        .get("funding_interval_hours"));
    }

    @Test
    void foreignSymbolOrEmptySourceCannotBeDeclaredComplete() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        BinanceFundingMapper.map(
                                bytes("[]"), DAY, DAY, "a".repeat(64), Instant.now()));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        BinanceFundingMapper.map(
                                bytes(
                                        "[{\"symbol\":\"ETHUSDT\",\"fundingTime\":1790812800001,\"fundingRate\":\"0.1\"}]"),
                                DAY,
                                DAY,
                                "a".repeat(64),
                                Instant.now()));
    }
}
