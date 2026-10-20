package com.zoutrankil.data.stock.storage;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.zoutrankil.data.domain.stock.basic.StockStDaily;
import com.zoutrankil.data.stock.mapper.StockStDailyMapper;
import com.zoutrankil.market.cn.model.CnTradeDateLayout;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;

class StockStDailyVersionedCodecTest {
    @Test
    void literalVectorsPreserveV1BytesAndGiveV2ItsOwnLogicalDateEncoding() {
        var row = new StockStDaily("000001.SZ", LocalDate.of(2026, 9, 28), 1);
        var mapper = new StockStDailyMapper();
        assertSame(
                StockStDailyWritePort.CODEC,
                StockStDailyWritePort.codec(CnTradeDateLayout.LEGACY_V1));
        assertArrayEquals(
                "{\"ts_code\":\"000001.SZ\",\"is_st\":1,\"timestamp\":\"2026-09-28\"}"
                        .getBytes(StandardCharsets.UTF_8),
                StockStDailyWritePort.CODEC.canonicalBytes(row));
        assertArrayEquals(
                "{\"ts_code\":\"000001.SZ\",\"is_st\":1,\"trade_date\":\"2026-09-28\"}"
                        .getBytes(StandardCharsets.UTF_8),
                StockStDailyWritePort.codec(CnTradeDateLayout.TRADE_DATE_V2).canonicalBytes(row));
        assertEquals(row, mapper.fromValues(mapper.values(row)));
        var next = mapper.values(row, CnTradeDateLayout.TRADE_DATE_V2);
        assertFalse(next.columns().contains("timestamp"));
        assertEquals(row, mapper.fromValues(next, CnTradeDateLayout.TRADE_DATE_V2));
        assertThrows(IllegalArgumentException.class, () -> mapper.fromValues(next));
        assertThrows(
                IllegalArgumentException.class,
                () -> mapper.fromValues(mapper.values(row), CnTradeDateLayout.TRADE_DATE_V2));
    }
}
