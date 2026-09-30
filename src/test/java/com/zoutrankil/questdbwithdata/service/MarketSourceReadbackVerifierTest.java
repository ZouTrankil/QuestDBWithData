package com.zoutrankil.questdbwithdata.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class MarketSourceReadbackVerifierTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final List<String> D007_FIELDS = List.of("ts_code", "trade_date", "open", "high", "low",
            "close", "pre_close", "change", "pct_chg", "vol", "amount", "ah_vol", "ah_amount");
    private static final List<String> D008_FIELDS = List.of("ts_code", "trade_date", "close", "turnover_rate",
            "turnover_rate_f", "volume_ratio", "pe", "pe_ttm", "pb", "ps", "ps_ttm", "dv_ratio",
            "dv_ttm", "total_share", "float_share", "free_share", "total_mv", "circ_mv");
    private static final List<String> D009_FIELDS = List.of("ts_code", "trade_date", "close", "open", "high",
            "low", "pre_close", "change", "pct_change", "vol", "amount", "adj_factor", "open_hfq",
            "open_qfq", "close_hfq", "close_qfq", "high_hfq", "high_qfq", "low_hfq", "low_qfq",
            "pre_close_hfq", "pre_close_qfq", "macd_dif", "macd_dea", "macd", "kdj_k", "kdj_d",
            "kdj_j", "rsi_6", "rsi_12", "rsi_24", "boll_upper", "boll_mid", "boll_lower", "cci");

    @Test void dailyBasicRawContractMapsAllEighteenColumnsAndPreservesNulls() {
        ObjectNode raw = row(D008_FIELDS, "20260928", "2.75");
        raw.putNull("pe_ttm");

        Map<String, Object> mapped = MarketSourceReadbackVerifier.normalizeRawRow("D008", raw);

        assertEquals(18, mapped.size());
        assertEquals("000001.SZ", mapped.get("ts_code"));
        assertEquals(LocalDate.of(2026, 9, 28), mapped.get("trade_date"));
        assertEquals(2.75d, mapped.get("close"));
        assertNull(mapped.get("pe_ttm"));
        assertTrue(mapped.containsKey("circ_mv"));
    }

    @Test void stockFactorRawPctChangeColumnMatchesPhysicalName() {
        ObjectNode raw = row(D009_FIELDS, "20260928", "1.5");

        Map<String, Object> mapped = MarketSourceReadbackVerifier.normalizeRawRow("D009", raw);

        assertEquals(35, mapped.size());
        assertEquals(1.5d, mapped.get("pct_change"));
        assertFalse(mapped.containsKey("pct_chg"));
        assertEquals(1.5d, mapped.get("boll_lower"));
    }

    @Test void dailyRawContractRequiresNumericJsonAndUsesExactTradeDateKey() {
        ObjectNode raw = row(D007_FIELDS, "20260928", "1.5");
        for (String field : D007_FIELDS) {
            if (!field.equals("ts_code") && !field.equals("trade_date")) raw.put(field, 1.5);
        }
        raw.put("close", 12.5);

        Map<String, Object> mapped = MarketSourceReadbackVerifier.normalizeRawRow("D007", raw);

        assertEquals(13, mapped.size());
        assertEquals("000001.SZ", mapped.get("ts_code"));
        assertEquals(LocalDate.of(2026, 9, 28), mapped.get("trade_date"));
        assertEquals(12.5d, mapped.get("close"));
        assertThrows(IllegalArgumentException.class, () -> MarketSourceReadbackVerifier.normalizeRawRow(
                "D007", row(D007_FIELDS, "20260928", "")));
    }

    @Test void rejectsRowsWithMissingOrAdditionalSourceFieldsAndInvalidBusinessKeys() {
        ObjectNode missing = row(D008_FIELDS, "20260928", "1.0");
        missing.remove("circ_mv");
        assertThrows(IllegalArgumentException.class,
                () -> MarketSourceReadbackVerifier.normalizeRawRow("D008", missing));

        ObjectNode additional = row(D008_FIELDS, "20260928", "1.0");
        additional.put("unexpected", 1);
        assertThrows(IllegalArgumentException.class,
                () -> MarketSourceReadbackVerifier.normalizeRawRow("D008", additional));

        ObjectNode invalidKey = row(D008_FIELDS, "20260931", "1.0");
        assertThrows(IllegalArgumentException.class,
                () -> MarketSourceReadbackVerifier.normalizeRawRow("D008", invalidKey));
    }

    private static ObjectNode row(List<String> fields, String date, String numeric) {
        ObjectNode row = JSON.createObjectNode();
        for (String field : fields) {
            if (field.equals("ts_code")) row.put(field, "000001.SZ");
            else if (field.equals("trade_date")) row.put(field, date);
            else row.put(field, numeric);
        }
        return row;
    }
}
