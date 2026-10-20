package com.zoutrankil.market.cn.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.zoutrankil.data.domain.DatasetDefinition;
import com.zoutrankil.data.domain.NumericContract;
import com.zoutrankil.data.domain.fund.EtfDailyDataset;
import com.zoutrankil.data.domain.fund.EtfPortfolioDataset;
import com.zoutrankil.data.domain.stock.market.DailyBasicDataset;
import com.zoutrankil.data.domain.stock.market.DailyDataset;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

class CnNumericContractsTest {
    @Test
    void separatesSameNamedTurnoverAndHoldingQuantityAndMarketValueScales() {
        assertEquals(
                new BigDecimal("1000"),
                numeric(DailyDataset.DEFINITION, "amount").multiplierToBaseUnit());
        assertEquals(
                new BigDecimal("1000"),
                numeric(EtfDailyDataset.DEFINITION, "amount").multiplierToBaseUnit());
        assertEquals("股", numeric(EtfPortfolioDataset.DEFINITION, "amount").baseUnit());
        assertEquals("NONE", numeric(EtfPortfolioDataset.DEFINITION, "amount").currency());
        assertEquals(
                BigDecimal.ONE,
                numeric(EtfPortfolioDataset.DEFINITION, "mkv").multiplierToBaseUnit());
        assertEquals(
                new BigDecimal("10000"),
                numeric(DailyBasicDataset.DEFINITION, "total_mv").multiplierToBaseUnit());
        assertEquals("手", numeric(DailyDataset.DEFINITION, "vol").baseUnit());
    }

    @Test
    void doesNotCertifyUndocumentedPortfolioRatioScaleOrMergeDifferentTurnoverDenominators() {
        for (var field : List.of("stk_mkv_ratio", "stk_float_ratio")) {
            var value = numeric(EtfPortfolioDataset.DEFINITION, field);
            assertEquals("UNVERIFIED", value.sourceUnit());
            assertNull(value.multiplierToBaseUnit());
        }
        assertEquals(
                new BigDecimal("0.01"),
                numeric(DailyBasicDataset.DEFINITION, "turnover_rate").multiplierToBaseUnit());
        assertEquals(
                "无限售流通股数", numeric(DailyBasicDataset.DEFINITION, "turnover_rate").denominator());
        assertEquals(
                "自由流通股数", numeric(DailyBasicDataset.DEFINITION, "turnover_rate_f").denominator());
        assertTrue(numeric(DailyDataset.DEFINITION, "pre_close").priceBasis().contains("除权昨收"));
        assertTrue(numeric(EtfDailyDataset.DEFINITION, "pre_close").priceBasis().contains("不自行认定"));
    }

    @Test
    void everyNumericColumnInTheFourSourceContractsHasMetadataAndKeysRemainUnchanged() {
        for (var dataset :
                List.of(
                        DailyDataset.DEFINITION,
                        DailyBasicDataset.DEFINITION,
                        EtfDailyDataset.DEFINITION,
                        EtfPortfolioDataset.DEFINITION)) {
            for (var column : dataset.columns())
                if (column.storageType() == DatasetDefinition.StorageType.DOUBLE)
                    assertNotNull(
                            column.numeric(), dataset.datasetId() + "." + column.storageName());
        }
        assertEquals(List.of("ts_code", "trade_date"), DailyDataset.DEFINITION.businessKey());
        assertEquals(
                List.of("ts_code", "ann_date", "end_date", "symbol"),
                EtfPortfolioDataset.DEFINITION.businessKey());
        assertEquals("timestamp", EtfDailyDataset.DEFINITION.designatedTimestamp());
    }

    private static NumericContract numeric(DatasetDefinition definition, String field) {
        return definition.columns().stream()
                .filter(column -> field.equals(column.storageName()))
                .findFirst()
                .orElseThrow()
                .numeric();
    }
}
