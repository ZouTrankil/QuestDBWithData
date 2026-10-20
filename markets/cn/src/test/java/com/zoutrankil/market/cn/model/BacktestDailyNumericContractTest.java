package com.zoutrankil.market.cn.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.zoutrankil.data.domain.DatasetDefinition.Column;
import com.zoutrankil.data.domain.derived.BacktestDailyViewDataset;

import org.junit.jupiter.api.Test;

import java.util.List;

class BacktestDailyNumericContractTest {
    @Test
    void numericMetadataKeepsSuspensionSynthesisSeparateFromMissingRawSourceValues() {
        assertTrue(column("amount").numeric().missingPolicy().contains("停牌补行金额为零"));
        assertTrue(column("vol").numeric().missingPolicy().contains("停牌补行成交量为零"));
        assertTrue(column("close").numeric().priceBasis().contains("ASOF 收盘价"));
        assertTrue(column("up_limit").numeric().missingPolicy().contains("保持 null"));
        assertTrue(column("is_st").numeric().missingPolicy().contains("coalesce 为零"));
        assertTrue(column("adj_factor").numeric().missingPolicy().contains("不用一补缺失"));
    }

    @Test
    void allNumericViewColumnsHaveMetadataWithoutChangingLayoutOrKeys() {
        var definition = BacktestDailyViewDataset.DEFINITION;
        for (var column : definition.columns())
            if (!List.of("trade_date", "ts_code").contains(column.storageName()))
                assertNotNull(column.numeric(), column.storageName());
        assertEquals(List.of("trade_date", "ts_code"), definition.businessKey());
        assertEquals("trade_date", definition.designatedTimestamp());
        assertEquals(
                List.of(
                        "trade_date",
                        "ts_code",
                        "open",
                        "high",
                        "low",
                        "close",
                        "vol",
                        "amount",
                        "adj_factor",
                        "up_limit",
                        "down_limit",
                        "is_suspended",
                        "is_st"),
                definition.columns().stream().map(Column::storageName).toList());
    }

    private static Column column(String field) {
        return BacktestDailyViewDataset.DEFINITION.columns().stream()
                .filter(column -> field.equals(column.storageName()))
                .findFirst()
                .orElseThrow();
    }
}
