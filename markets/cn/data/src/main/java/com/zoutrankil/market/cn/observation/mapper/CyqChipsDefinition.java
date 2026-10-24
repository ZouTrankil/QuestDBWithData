package com.zoutrankil.market.cn.observation.mapper;

import com.zoutrankil.data.domain.DatasetDefinition;
import com.zoutrankil.data.domain.DatasetDefinition.Capability;
import com.zoutrankil.data.domain.DatasetDefinition.Column;
import com.zoutrankil.data.domain.DatasetDefinition.ObjectKind;
import com.zoutrankil.data.domain.DatasetDefinition.Partition;
import com.zoutrankil.data.domain.DatasetDefinition.StorageType;
import com.zoutrankil.data.domain.DatasetDefinition.TemporalContract;
import com.zoutrankil.data.domain.DatasetDefinition.TemporalKind;
import com.zoutrankil.data.marketdata.VerifiedMarketDataStore;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/** 单证券单日的全部价格档位归并为两个等长数组；下标对应，不重算或归一化占比。 */
public final class CyqChipsDefinition {
    private CyqChipsDefinition() {}

    public static DatasetDefinition definition() {
        var date =
                new TemporalContract(
                        TemporalKind.BUSINESS_DATE,
                        "BASIC",
                        "calendar",
                        "DAY",
                        "来源交易日，UTC 午夜仅承载日期");
        return new DatasetDefinition(
                "cyq_chips",
                1,
                "tushare.cyq_chips",
                "contract_table_owner",
                "cyq_chips",
                ObjectKind.TABLE,
                List.of(
                        new Column(
                                "ts_code",
                                "ts_code",
                                "ts_code",
                                StorageType.SYMBOL,
                                false,
                                "来源证券代码",
                                null),
                        new Column(
                                "trade_date",
                                "trade_date",
                                "trade_date",
                                StorageType.TIMESTAMP,
                                false,
                                "来源交易日",
                                date),
                        new Column(
                                "price",
                                "price",
                                "price",
                                StorageType.DOUBLE_ARRAY,
                                false,
                                "全部成本价格档位，保留来源价格单位，按价格升序",
                                null),
                        new Column(
                                "percent",
                                "percent",
                                "percent",
                                StorageType.DOUBLE_ARRAY,
                                false,
                                "各档位占比原值，单位 %，与价格下标一一对应",
                                null)),
                List.of("ts_code", "trade_date"),
                List.of("ts_code", "trade_date"),
                "trade_date",
                Partition.YEAR,
                true,
                Set.of(Capability.READ, Capability.WRITE),
                List.of(),
                "只写已有一维 DOUBLE[] 表；完整分页、价格唯一和配对数组回读后才验收");
    }

    public static List<Map<String, Object>> physicalRows(List<Map<String, Object>> rows) {
        if (rows.isEmpty()) return List.of();
        String code = Objects.toString(rows.getFirst().get("ts_code"), "");
        LocalDate date = LocalDate.parse(rows.getFirst().get("trade_date").toString());
        var bins = new TreeMap<Double, Double>();
        for (var row : rows) {
            if (!Objects.equals(code, row.get("ts_code"))
                    || !date.toString().equals(row.get("trade_date")))
                throw new IllegalArgumentException("筹码档位越出单证券单日范围");
            if (!(row.get("price") instanceof Number p)
                    || !(row.get("percent") instanceof Number weight))
                throw new IllegalArgumentException("筹码价格与占比不得缺失");
            double price = p.doubleValue(), percent = weight.doubleValue();
            if (!Double.isFinite(price)
                    || price <= 0
                    || !Double.isFinite(percent)
                    || percent < 0
                    || percent > 100) throw new IllegalArgumentException("筹码档位数值无效");
            if (bins.putIfAbsent(price, percent) != null)
                throw new IllegalArgumentException("筹码价格档位重复，不能合并可能重叠的分页");
        }
        double sum = bins.values().stream().mapToDouble(Double::doubleValue).sum();
        // 来源按百分数保留两位小数；逐档舍入误差只作有界检查，不补尾差或改变来源占比。
        double tolerance = bins.size() * 0.005 + 1e-8;
        if (sum <= 0 || Math.abs(sum - 100d) > tolerance)
            throw new IllegalArgumentException("筹码占比合计超出逐档舍入容差，不能认证完整分布");
        var row =
                Map.<String, Object>of(
                        "ts_code",
                        code,
                        "trade_date",
                        date.atStartOfDay(ZoneOffset.UTC).toInstant().toString(),
                        "price",
                        List.copyOf(bins.keySet()),
                        "percent",
                        List.copyOf(bins.values()));
        return List.of(VerifiedMarketDataStore.normalize(definition(), row));
    }
}
