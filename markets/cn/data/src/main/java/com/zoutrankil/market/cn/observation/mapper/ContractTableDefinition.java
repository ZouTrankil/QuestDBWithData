package com.zoutrankil.market.cn.observation.mapper;

import com.zoutrankil.batch.source.SourceContract;
import com.zoutrankil.data.domain.DatasetDefinition;
import com.zoutrankil.data.domain.DatasetDefinition.Capability;
import com.zoutrankil.data.domain.DatasetDefinition.Column;
import com.zoutrankil.data.domain.DatasetDefinition.ObjectKind;
import com.zoutrankil.data.domain.DatasetDefinition.Partition;
import com.zoutrankil.data.domain.DatasetDefinition.StorageType;
import com.zoutrankil.data.domain.DatasetDefinition.TemporalContract;
import com.zoutrankil.data.domain.DatasetDefinition.TemporalKind;
import com.zoutrankil.data.marketdata.VerifiedMarketDataStore;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** 只接明确登记的来源契约，保留原物理键、日期布局和字段单位。 */
public final class ContractTableDefinition {
    public static final Set<String> DATASETS =
            Set.of(
                    "sge_daily",
                    "cyq_chips",
                    "fut_holding",
                    "stk_alert",
                    "broker_recommend",
                    "fut_basic",
                    "disclosure_date",
                    "share_float",
                    "dividend",
                    "fina_audit",
                    "fina_mainbz",
                    "pledge_stat",
                    "stk_holdernumber",
                    "stk_holdertrade",
                    "stk_shock",
                    "stk_high_shock");

    private ContractTableDefinition() {}

    public static DatasetDefinition from(String dataset) {
        if (!DATASETS.contains(dataset)) throw new IllegalArgumentException("未接入的契约表");
        var source = SourceContract.load(dataset);
        if (dataset.equals("cyq_chips")) return CyqChipsDefinition.definition();
        var columns =
                source.columns().stream()
                        .map(
                                c ->
                                        new Column(
                                                dataset.equals("fut_holding")
                                                                && c.target().equals("exchange")
                                                        ? "derived:request_exchange"
                                                        : c.target().equals("update_time")
                                                                ? "derived:observation_started_at"
                                                                : c.source(),
                                                c.target(),
                                                c.target(),
                                                dataset.equals("fina_mainbz")
                                                                && c.target().equals("bz_code")
                                                        ? StorageType.VARCHAR
                                                        : StorageType.valueOf(c.type()),
                                                !c.key(),
                                                c.target().equals("timestamp")
                                                        ? "旧表技术哨兵，不表示历史快照日期"
                                                        : "来源原值：" + c.source(),
                                                !c.type().equals("TIMESTAMP")
                                                        ? null
                                                        : c.target().equals("timestamp")
                                                                ? new TemporalContract(
                                                                        TemporalKind.TECHNICAL,
                                                                        "ISO_INSTANT",
                                                                        "UTC",
                                                                        "MICROS",
                                                                        "固定 epoch 哨兵")
                                                                : c.target().equals("update_time")
                                                                        ? new TemporalContract(
                                                                                TemporalKind
                                                                                        .INSTANT,
                                                                                "ISO_INSTANT",
                                                                                "UTC",
                                                                                "MICROS",
                                                                                "冻结请求开始时刻；不表示公告可用时刻")
                                                                        : new TemporalContract(
                                                                                TemporalKind
                                                                                        .BUSINESS_DATE,
                                                                                "BASIC",
                                                                                "calendar",
                                                                                "DAY",
                                                                                "来源业务日期，UTC 午夜仅承载日期")))
                        .toList();
        var keys =
                source.columns().stream()
                        .filter(SourceContract.Column::key)
                        .map(SourceContract.Column::target)
                        .toList();
        return new DatasetDefinition(
                dataset,
                1,
                "tushare." + dataset,
                "contract_table_owner",
                dataset,
                ObjectKind.TABLE,
                columns,
                keys,
                keys,
                source.timestampColumn(),
                dataset.equals("fut_holding") ? Partition.MONTH : Partition.YEAR,
                true,
                Set.of(Capability.READ, Capability.WRITE),
                List.of(),
                "有限明确范围；不自动改表、不认证历史时点、不删除消失键");
    }

    public static List<Map<String, Object>> physicalRows(
            String dataset, List<Map<String, Object>> rows, Instant observed) {
        if (dataset.equals("cyq_chips")) return CyqChipsDefinition.physicalRows(rows);
        return rows.stream().map(r -> physical(dataset, r, observed)).toList();
    }

    public static Map<String, Object> physical(
            String dataset, Map<String, Object> business, Instant observed) {
        var source = SourceContract.load(dataset);
        var row = new TreeMap<>(source.physicalRow(business, observed));
        // 旧来源归档缺 exchange；正式入口固定 CFFEX，把请求维度显式写入，不清空已有交易所。
        if (dataset.equals("fut_holding")) {
            if (row.get("exchange") != null && !row.get("exchange").equals("CFFEX"))
                throw new IllegalArgumentException("持仓排名交易所越出 CFFEX 请求范围");
            row.put("exchange", "CFFEX");
        }
        // 所有来源 TIMESTAMP 日期显式编码；技术哨兵与观测时刻保持真实含义。
        for (var c : source.columns())
            if (c.type().equals("TIMESTAMP")
                    && !Set.of("timestamp", "update_time").contains(c.target())
                    && row.get(c.target()) != null)
                row.put(
                        c.target(),
                        LocalDate.parse(row.get(c.target()).toString())
                                .atStartOfDay(ZoneOffset.UTC)
                                .toInstant()
                                .toString());
        return VerifiedMarketDataStore.normalize(from(dataset), row);
    }
}
