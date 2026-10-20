package com.zoutrankil.market.us.marketdata;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

class UsFundHoldingsTest {
    @Test
    void currentEquityHoldingsKeepTheirProxyBasisAndRejectDuplicates() throws Exception {
        var holdings = new ArrayList<Map<String, Object>>();
        for (int i = 0; i < 101; i++)
            holdings.add(
                    new LinkedHashMap<>(
                            Map.of(
                                    "ticker",
                                    "X" + i,
                                    "issuerName",
                                    "Company " + i,
                                    "cusip",
                                    String.format("%09d", i),
                                    "percentageOfTotalNetAssets",
                                    100. / 101,
                                    "currency",
                                    "USD",
                                    "securityTypeName",
                                    "Common Stock")));
        var document =
                Map.of(
                        "cusip",
                        "QQQ",
                        "effectiveBusinessDate",
                        "2026-10-16",
                        "totalNumberOfHoldings",
                        holdings.size(),
                        "holdings",
                        holdings);
        var json = new ObjectMapper();
        var rows = UsFundHoldings.qqq(json.writeValueAsBytes(document), "sha", Instant.EPOCH);
        assertEquals(101, rows.size());
        assertEquals("ETF_HOLDINGS_PROXY", rows.getFirst().get("membership_basis"));
        holdings.getLast().put("ticker", "X0");
        assertThrows(
                IllegalArgumentException.class,
                () -> UsFundHoldings.qqq(json.writeValueAsBytes(document), "sha", Instant.EPOCH));
    }
}
