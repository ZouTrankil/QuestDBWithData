package com.zoutrankil.market.cn.observation.mapper;

import com.zoutrankil.data.domain.DatasetDefinition;
import com.zoutrankil.data.domain.DatasetDefinition.Capability;
import com.zoutrankil.data.domain.DatasetDefinition.Column;
import com.zoutrankil.data.domain.DatasetDefinition.ObjectKind;
import com.zoutrankil.data.domain.DatasetDefinition.Partition;
import com.zoutrankil.data.domain.DatasetDefinition.StorageType;
import com.zoutrankil.data.domain.DatasetDefinition.TemporalContract;
import com.zoutrankil.data.domain.DatasetDefinition.TemporalKind;
import com.zoutrankil.data.domain.JobDefinitionJson;
import com.zoutrankil.data.marketdata.VerifiedMarketDataStore;
import com.zoutrankil.data.repository.FileEvidenceStore;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** 回购和大宗的范围快照；序号只标识本次快照中的位置，不表示稳定事件身份。 */
public final class SnapshotEventDefinition {
    public static final Set<String> DATASETS = Set.of("repurchase", "block_trade");
    public static final String ORDINAL = "snapshot_ordinal";
    public static final int MAX_SOURCE_ROWS = 2000;
    public static final int MAX_TARGET_ROWS = 1_000_000;
    public static final int MAX_BYTES = 128 * 1024 * 1024;

    private SnapshotEventDefinition() {}

    public static String dateField(String dataset) {
        if (!DATASETS.contains(dataset)) throw new IllegalArgumentException("未知范围快照表");
        return dataset.equals("repurchase") ? "ann_date" : "trade_date";
    }

    public static int sourceLimit(String dataset) {
        return dataset.equals("repurchase") ? 2000 : 1000;
    }

    public static DatasetDefinition definition(String dataset, String table) {
        String date = dateField(dataset);
        var columns = new ArrayList<Column>();
        columns.add(
                new Column(
                        "ts_code",
                        "ts_code",
                        "ts_code",
                        StorageType.SYMBOL,
                        false,
                        "来源证券代码，不按股票清单过滤债券",
                        null));
        columns.add(
                new Column(
                        date,
                        date,
                        date,
                        StorageType.TIMESTAMP,
                        false,
                        "来源业务日期",
                        new TemporalContract(
                                TemporalKind.BUSINESS_DATE,
                                "BASIC",
                                "calendar",
                                "DAY",
                                "UTC 午夜仅承载日期")));
        var fields =
                dataset.equals("repurchase")
                        ? List.of(
                                "end_date",
                                "proc",
                                "exp_date",
                                "vol",
                                "amount",
                                "high_limit",
                                "low_limit")
                        : List.of("price", "vol", "amount", "buyer", "seller");
        for (String name : fields) {
            boolean text = Set.of("end_date", "proc", "exp_date", "buyer", "seller").contains(name);
            columns.add(
                    new Column(
                            name,
                            name,
                            name,
                            name.equals("proc")
                                    ? StorageType.SYMBOL
                                    : text ? StorageType.STRING : StorageType.DOUBLE,
                            true,
                            "来源原值：" + name,
                            null));
        }
        columns.add(
                new Column(
                        "derived:snapshot_position",
                        ORDINAL,
                        ORDINAL,
                        StorageType.LONG,
                        false,
                        "证券与业务日内按完整内容排序的位置；仅用于当前范围快照",
                        null));
        return new DatasetDefinition(
                dataset,
                2,
                "tushare." + dataset,
                "snapshot_event_owner",
                table,
                ObjectKind.TABLE,
                columns,
                List.of("ts_code", date, ORDINAL),
                List.of(),
                date,
                Partition.YEAR,
                true,
                Set.of(Capability.READ, Capability.WAL_REPLACE),
                List.of(),
                "完整范围替换保留同值多条；不直接 UPSERT，不自动切换旧请求");
    }

    public static DatasetDefinition definition(String dataset) {
        return definition(dataset, dataset);
    }

    public static List<Map<String, Object>> assignOrdinals(
            String dataset, List<Map<String, Object>> input) {
        if (input.size() > MAX_TARGET_ROWS) throw new IllegalArgumentException("范围快照行数超过预算");
        var definition = definition(dataset);
        var sorted = new ArrayList<Map<String, Object>>();
        for (var row : input) {
            var copy = new TreeMap<>(row);
            copy.put(ORDINAL, 1L);
            var normalized = VerifiedMarketDataStore.normalize(definition, copy);
            sorted.add(normalized);
        }
        sorted.sort(Comparator.comparing(SnapshotEventDefinition::canonical));
        var next = new HashMap<String, Long>();
        var result = new ArrayList<Map<String, Object>>();
        for (var row : sorted) {
            String key = canonical(List.of(row.get("ts_code"), row.get(dateField(dataset))));
            var copy = new TreeMap<>(row);
            copy.put(ORDINAL, next.merge(key, 1L, Long::sum));
            result.add(Collections.unmodifiableMap(copy));
        }
        return List.copyOf(result);
    }

    /** 摘要前按完整行排序，重复行逐条参与，不用 Map 去重。 */
    public static String fingerprint(List<Map<String, Object>> rows) {
        var sorted = rows.stream().map(SnapshotEventDefinition::canonical).sorted().toList();
        return FileEvidenceStore.sha256(canonical(sorted).getBytes(StandardCharsets.UTF_8));
    }

    public static String canonical(Object value) {
        try {
            return JobDefinitionJson.canonicalMapper().writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalArgumentException("快照内容无法规范编码", e);
        }
    }

    public static LocalDate date(String dataset, Map<String, Object> row) {
        var instant = Instant.parse(row.get(dateField(dataset)).toString());
        if (instant.getNano() != 0 || Math.floorMod(instant.getEpochSecond(), 86400) != 0)
            throw new IllegalArgumentException("快照业务日不是 UTC 午夜日期载体");
        return instant.atZone(ZoneOffset.UTC).toLocalDate();
    }
}
