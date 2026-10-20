package com.zoutrankil.data.etf.storage;

import com.zoutrankil.data.domain.fund.EtfDaily;
import com.zoutrankil.data.domain.fund.EtfDailyDataset;
import com.zoutrankil.data.domain.fund.EtfDailyKey;
import com.zoutrankil.data.domain.temporal.TemporalValues.CalendarTimestamp;
import com.zoutrankil.data.domain.temporal.TemporalValues.EpochUnit;
import com.zoutrankil.data.etf.domain.EtfTargetRange;
import com.zoutrankil.data.etf.port.EtfDailyTarget;
import com.zoutrankil.data.etf.port.EtfDailyWriteSession;
import com.zoutrankil.data.etf.port.EtfWriteSession;
import com.zoutrankil.data.repository.StaticTargetIdentity;
import com.zoutrankil.market.cn.model.CnTradeDateLayout;

import io.questdb.client.QuestDB;

import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.util.Objects;

/** Configured physical target; each execution obtains an independent write session. */
public final class QuestDbEtfDailyTarget implements EtfDailyTarget.Versioned {
    private final CnTradeDateLayout layout;
    private final String table;
    private final JdbcTemplate jdbc;
    private final QuestDB questdb;

    public QuestDbEtfDailyTarget(String table, JdbcTemplate jdbc, QuestDB questdb) {
        this(table, jdbc, questdb, CnTradeDateLayout.LEGACY_V1);
    }

    public QuestDbEtfDailyTarget(
            String table, JdbcTemplate jdbc, QuestDB questdb, CnTradeDateLayout layout) {
        this.layout = Objects.requireNonNull(layout);
        this.jdbc = Objects.requireNonNull(jdbc);
        this.questdb = Objects.requireNonNull(questdb);
        EtfDailyDataset.requireExecutionTable(table);
        this.table = table;
    }

    @Override
    public String tableName() {
        return table;
    }

    @Override
    public String targetId() {
        EtfDailyDataset.requireExecutionTable(table);
        var rows =
                jdbc.queryForList(
                        "SELECT id,directoryName FROM tables() WHERE table_name = ?", table);
        if (rows.size() != 1
                || !(rows.getFirst().get("id") instanceof Number id)
                || !(rows.getFirst().get("directoryName") instanceof String directory))
            throw new IllegalStateException(
                    "Exact isolated etf_daily QuestDB target identity required");
        return StaticTargetIdentity.identify(jdbc, table, id.longValue(), directory);
    }

    @Override
    public EtfTargetRange range() {
        return range(layout.schemaVersion());
    }

    @Override
    public int datasetVersion() {
        return layout.schemaVersion();
    }

    @Override
    public EtfTargetRange range(int datasetVersion) {
        String dateColumn =
                CnTradeDateLayout.fromVersion(datasetVersion).physicalDateColumn("etf_daily");
        String sql =
                "SELECT cast(min("
                        + dateColumn
                        + ") AS long) AS min_nanos, cast(max("
                        + dateColumn
                        + ") AS long) AS max_nanos FROM \""
                        + table
                        + "\"";
        return jdbc.query(
                sql,
                rs -> {
                    if (!rs.next())
                        throw new IllegalStateException(
                                "QuestDB did not return etf_daily date range");
                    Object min = rs.getObject("min_nanos"), max = rs.getObject("max_nanos");
                    if (min == null && max == null) return checkedRange(null, null);
                    if (!(min instanceof Number minValue) || !(max instanceof Number maxValue))
                        throw new IllegalStateException(
                                "QuestDB etf_daily date range has invalid types");
                    var from =
                            CalendarTimestamp.fromStorageEpoch(
                                            minValue.longValue(), EpochUnit.NANOS)
                                    .date();
                    var to =
                            CalendarTimestamp.fromStorageEpoch(
                                            maxValue.longValue(), EpochUnit.NANOS)
                                    .date();
                    return checkedRange(from, to);
                });
    }

    private static EtfTargetRange checkedRange(LocalDate min, LocalDate max) {
        if ((min == null) != (max == null) || min != null && min.isAfter(max))
            throw new IllegalStateException("Invalid etf_daily physical range");
        return new EtfTargetRange(min, max);
    }

    @Override
    public EtfWriteSession<EtfDaily, EtfDailyKey> newWriter(String frozenTargetId) {
        return newWriter(frozenTargetId, layout.schemaVersion());
    }

    @Override
    public EtfDailyWriteSession newWriter(String frozenTargetId, int datasetVersion) {
        return new EtfDailyWritePort(
                table,
                frozenTargetId,
                jdbc,
                questdb,
                CnTradeDateLayout.fromVersion(datasetVersion));
    }
}
