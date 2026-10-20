package com.zoutrankil.data.maintenance.application;

import com.zoutrankil.data.domain.DatasetDefinition;
import com.zoutrankil.data.domain.DatasetReadQuery;
import com.zoutrankil.data.domain.DatasetValues;
import com.zoutrankil.data.domain.macro.interest.InterestRateDatasets;
import com.zoutrankil.data.domain.stock.reference.StockShockDatasets;
import com.zoutrankil.data.repository.QuestDbBoundedReader;

import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 有界检查五项标准日期的实际读取；不启动 owner、不写表，也不签发完整性凭证。 */
public final class CnReferenceDateReadInspection {
    public static final List<DatasetDefinition> DEFINITIONS =
            List.of(
                    StockShockDatasets.SHOCK,
                    StockShockDatasets.HIGH_SHOCK,
                    InterestRateDatasets.SHIBOR,
                    InterestRateDatasets.HIBOR,
                    InterestRateDatasets.LPR);
    private final JdbcTemplate jdbc;

    public CnReferenceDateReadInspection(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Map<String, Object> inspect(LocalDate from, LocalDate toExclusive) {
        if (from == null
                || toExclusive == null
                || !from.isBefore(toExclusive)
                || ChronoUnit.DAYS.between(from, toExclusive) > 400)
            throw new IllegalArgumentException("只读检查要求 1 至 400 天的日期范围");
        var reader = new QuestDbBoundedReader(jdbc);
        var results = new ArrayList<Map<String, Object>>();
        for (var definition : DEFINITIONS) {
            var item = new LinkedHashMap<String, Object>();
            item.put("dataset", definition.datasetId());
            item.put("definitionVersion", definition.schemaVersion());
            item.put("mapping", definition.columns());
            try {
                var before = identity(definition.objectName());
                var date =
                        definition.columns().stream()
                                .filter(
                                        c ->
                                                c.storageName()
                                                        .equals(definition.designatedTimestamp()))
                                .findFirst()
                                .orElseThrow();
                var query =
                        new DatasetReadQuery(
                                definition.columns().stream()
                                        .map(DatasetDefinition.Column::logicalName)
                                        .toList(),
                                Map.of(),
                                date.logicalName(),
                                from,
                                toExclusive,
                                2,
                                null);
                var page = reader.read(definition, query, null, DatasetValues::asMap);
                var after = identity(definition.objectName());
                if (!before.equals(after)) throw new IllegalStateException("读取中物理身份变化");
                item.put("physicalIdentity", after);
                item.put("rows", page.rows());
                item.put("state", page.rows().isEmpty() ? "NO_SAMPLE" : "LIMITED_READ_SUCCEEDED");
            } catch (Exception error) {
                item.put("state", "FAILED");
                item.put("failureType", error.getClass().getSimpleName());
            }
            results.add(item);
        }
        return Map.of(
                "observedAt",
                Instant.now(),
                "fromInclusive",
                from,
                "toExclusive",
                toExclusive,
                "sourceCoverageVerified",
                false,
                "physicalMigrationExecuted",
                false,
                "objects",
                results,
                "allLimitedReadsSucceeded",
                results.stream().allMatch(r -> r.get("state").equals("LIMITED_READ_SUCCEEDED")));
    }

    private Map<String, Object> identity(String table) {
        return jdbc.queryForMap(
                "SELECT id,directoryName,walEnabled,designatedTimestamp,partitionBy FROM tables() WHERE table_name=?",
                table);
    }
}
