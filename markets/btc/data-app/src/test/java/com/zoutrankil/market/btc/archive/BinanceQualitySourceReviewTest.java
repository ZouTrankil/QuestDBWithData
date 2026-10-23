package com.zoutrankil.market.btc.archive;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zoutrankil.market.btc.archive.application.BinanceQualitySourceReview;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.List;
import java.util.Map;

class BinanceQualitySourceReviewTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void planRejectsDuplicatesFutureAndUnboundedRequests() {
        var item = Map.of("dataset", "spot_1h", "day", "2021-01-21");
        var plan =
                Map.of(
                        "schemaVersion",
                        1,
                        "batchId",
                        "a".repeat(64),
                        "requests",
                        List.of(item, item));
        assertThrows(
                IllegalArgumentException.class,
                () -> BinanceQualitySourceReview.validatePlan(JSON.valueToTree(plan)));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        BinanceQualitySourceReview.validatePlan(
                                JSON.valueToTree(
                                        Map.of(
                                                "schemaVersion",
                                                1,
                                                "batchId",
                                                "a".repeat(64),
                                                "requests",
                                                Collections.nCopies(65, item)))));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        BinanceQualitySourceReview.validatePlan(
                                JSON.valueToTree(
                                        Map.of(
                                                "schemaVersion",
                                                1,
                                                "batchId",
                                                "a".repeat(64),
                                                "requests",
                                                List.of(
                                                        Map.of(
                                                                "dataset",
                                                                "funding",
                                                                "day",
                                                                "2021-01-21"))))));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        BinanceQualitySourceReview.validatePlan(
                                JSON.valueToTree(
                                        Map.of(
                                                "schemaVersion",
                                                1,
                                                "batchId",
                                                "a".repeat(64),
                                                "requests",
                                                List.of(
                                                        Map.of(
                                                                "dataset",
                                                                "spot_1h",
                                                                "day",
                                                                "2999-01-01"))))));
    }

    @Test
    void firstFundingDependencyOnlyAllowsThePreviousDayHourlyEvidence() throws Exception {
        var plan =
                JSON.readTree(
                        "{\"schemaVersion\":1,\"batchId\":\""
                                + "a".repeat(64)
                                + "\",\"requests\":[{\"dataset\":\"spot_1h\",\"day\":\"2019-12-31\",\"fundingBoundaryDependency\":true}]}");
        assertEquals(1, BinanceQualitySourceReview.validatePlan(plan).size());
        for (var invalid :
                List.of(
                        plan.toString().replace("2019-12-31", "2019-12-30"),
                        plan.toString().replace("spot_1h", "spot_1d"),
                        plan.toString().replace("true", "false"),
                        plan.toString().replace("2019-12-31", "2020-01-01")))
            assertThrows(
                    IllegalArgumentException.class,
                    () -> BinanceQualitySourceReview.validatePlan(JSON.readTree(invalid)));
    }

    @Test
    void aggregateTradeEvidenceIsLimitedToSpotRequests() throws Exception {
        var plan =
                JSON.readTree(
                        "{\"schemaVersion\":1,\"batchId\":\""
                                + "a".repeat(64)
                                + "\",\"requests\":[{\"dataset\":\"spot_1h\",\"day\":\"2021-01-21\",\"aggregateTradesCheck\":true}]}");
        assertEquals(1, BinanceQualitySourceReview.validatePlan(plan).size());
        var wrongProduct = JSON.readTree(plan.toString().replace("spot_1h", "perp_1h"));
        assertThrows(
                IllegalArgumentException.class,
                () -> BinanceQualitySourceReview.validatePlan(wrongProduct));
    }

    @Test
    void officialRestUsesPairOnlyForIndexAndFreezesOneUtcDay() {
        var day = LocalDate.of(2021, 1, 21);
        assertTrue(
                BinanceQualitySourceReview.restUri(BinanceArchiveDataset.INDEX_1H, day)
                        .getQuery()
                        .startsWith("pair=BTCUSDT&interval=1h&"));
        assertTrue(
                BinanceQualitySourceReview.restUri(BinanceArchiveDataset.SPOT_1H, day)
                        .toString()
                        .startsWith(
                                "https://data-api.binance.vision/api/v3/klines?symbol=BTCUSDT"));
        assertTrue(
                BinanceQualitySourceReview.restUri(BinanceArchiveDataset.MARK_1H, day)
                        .getQuery()
                        .contains("startTime=1611187200000&endTime=1611273599999"));
    }

    @Test
    void restProjectionKeepsOriginalMillisecondPrecisionAndRejectsOutOfDay() throws Exception {
        var day = LocalDate.of(2025, 1, 1);
        long start = day.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
        byte[] bytes =
                JSON.writeValueAsBytes(
                        List.of(
                                List.of(
                                        start,
                                        "10",
                                        "12",
                                        "9",
                                        "11",
                                        "1",
                                        start + 3599999L,
                                        "10",
                                        1,
                                        "1",
                                        "10",
                                        "0")));
        var zip =
                BinanceQualitySourceReview.restProjection(
                        BinanceArchiveDataset.SPOT_1H, day, bytes);
        var coverage =
                BinanceArchiveMapper.inspect(
                        BinanceArchiveDataset.SPOT_1H,
                        zip,
                        day,
                        day,
                        "a".repeat(64),
                        Instant.parse("2026-01-01T00:00:00Z"));
        assertEquals("2025-01-01T00:59:59.999Z", coverage.rows().getFirst().get("close_time"));
        assertEquals(23, coverage.missingOpenTimes().size());
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        BinanceArchiveMapper.inspect(
                                BinanceArchiveDataset.SPOT_1H,
                                zip,
                                day.plusDays(1),
                                day.plusDays(1),
                                "a".repeat(64),
                                Instant.parse("2026-01-01T00:00:00Z")));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        BinanceQualitySourceReview.restProjection(
                                BinanceArchiveDataset.SPOT_1H,
                                day,
                                "[[0,1]]".getBytes(StandardCharsets.UTF_8)));
    }
}
