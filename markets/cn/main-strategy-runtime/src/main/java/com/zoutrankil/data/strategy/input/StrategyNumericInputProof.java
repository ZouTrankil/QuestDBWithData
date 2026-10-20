package com.zoutrankil.data.strategy.input;

import com.zoutrankil.data.domain.fund.EtfDailyDataset;
import com.zoutrankil.data.domain.fund.EtfPortfolioDataset;
import com.zoutrankil.data.domain.stock.market.DailyBasicDataset;
import com.zoutrankil.data.domain.stock.market.DailyDataset;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** v2 证明保留来源数值单位；发布原值，不把元数据中的倍率偷偷应用到模型。 */
final class StrategyNumericInputProof {
    private StrategyNumericInputProof() {}

    static Map<String, Object> describe() {
        var datasets =
                List.of(
                        DailyDataset.DEFINITION,
                        DailyBasicDataset.DEFINITION,
                        EtfDailyDataset.DEFINITION,
                        EtfPortfolioDataset.DEFINITION);
        var fields = new ArrayList<Map<String, Object>>();
        for (var dataset : datasets) {
            for (var column : dataset.columns()) {
                if (column.numeric() == null) continue;
                var item = new LinkedHashMap<String, Object>();
                item.put("sourceDataset", dataset.datasetId());
                item.put("datasetVersion", dataset.schemaVersion());
                item.put("sourceField", column.sourceName());
                item.put("storageField", column.storageName());
                String logical =
                        dataset.datasetId().equals("etf_portfolio")
                                ? StrategyInputRows.standardFieldName(
                                        "etf_portfolio", column.storageName())
                                : column.logicalName();
                item.put("logicalField", logical);
                item.put("numeric", column.numeric());
                fields.add(Map.copyOf(item));
            }
        }
        return Map.of(
                "schemaVersion",
                1,
                "fields",
                List.copyOf(fields),
                "valuesConverted",
                false,
                "unitContractsComplete",
                false,
                "stockDailyProjection",
                StrategyStockDailyProjection.evidence(),
                "modelAcceptanceVerified",
                false);
    }
}
