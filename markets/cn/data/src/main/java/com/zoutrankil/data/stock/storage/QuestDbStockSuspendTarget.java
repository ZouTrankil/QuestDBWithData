package com.zoutrankil.data.stock.storage;

import com.zoutrankil.data.domain.stock.market.StockSuspend;
import com.zoutrankil.data.domain.stock.market.StockSuspendDataset;
import com.zoutrankil.data.domain.temporal.TemporalValues.CalendarTimestamp;
import com.zoutrankil.data.domain.temporal.TemporalValues.EpochUnit;
import com.zoutrankil.data.repository.QuestDbWriteChecks;
import com.zoutrankil.data.repository.StaticTargetIdentity;
import com.zoutrankil.data.stock.domain.StockSuspendState.Identity;
import com.zoutrankil.data.stock.domain.StockSuspendState.Prepared;
import com.zoutrankil.data.stock.domain.StockSuspendState.Range;
import com.zoutrankil.data.stock.domain.StockSuspendState.Snapshot;
import com.zoutrankil.data.stock.domain.policy.StockSuspendTablePolicy;
import com.zoutrankil.data.stock.port.StockSuspendStagingPort;
import com.zoutrankil.data.stock.port.StockSuspendTables;
import com.zoutrankil.data.stock.port.StockSuspendTarget;
import com.zoutrankil.data.stock.port.StockSuspendWriteSession;
import com.zoutrankil.market.cn.model.CnTradeDateLayout;

import io.questdb.client.QuestDB;

import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/** D011 physical adapter; construction performs no database or sender I/O. */
public final class QuestDbStockSuspendTarget implements StockSuspendTarget.Versioned {
    private final CnTradeDateLayout layout;
    private final String table;
    private final JdbcTemplate jdbc;
    private final QuestDB questdb;

    public QuestDbStockSuspendTarget(String table, JdbcTemplate jdbc, QuestDB questdb) {
        this(table, jdbc, questdb, CnTradeDateLayout.LEGACY_V1);
    }

    public QuestDbStockSuspendTarget(
            String table, JdbcTemplate jdbc, QuestDB questdb, CnTradeDateLayout layout) {
        this.layout = Objects.requireNonNull(layout);
        this.jdbc = Objects.requireNonNull(jdbc);
        this.questdb = Objects.requireNonNull(questdb);
        StockSuspendTablePolicy.requireExecutionTarget(table);
        this.table = table;
    }

    @Override
    public int datasetVersion() {
        return layout.schemaVersion();
    }

    @Override
    public StockSuspendTarget forVersion(int version) {
        var selected = CnTradeDateLayout.fromVersion(version);
        return selected == layout
                ? this
                : new QuestDbStockSuspendTarget(table, jdbc, questdb, selected);
    }

    @Override
    public String tableName() {
        return table;
    }

    @Override
    public String targetId() {
        return StockSuspendTargetIdentity.logical(jdbc, table, layout);
    }

    @Override
    public String physicalTargetId() {
        QuestDbWriteChecks.preflight(jdbc, table, StockSuspendDataset.definition(table, layout));
        var rows =
                jdbc.queryForList(
                        "SELECT id,directoryName FROM tables() WHERE table_name=?", table);
        if (rows.size() != 1
                || !(rows.getFirst().get("id") instanceof Number id)
                || !(rows.getFirst().get("directoryName") instanceof String directory))
            throw new IllegalStateException(
                    "Exact isolated stk_suspend QuestDB target identity required");
        return StaticTargetIdentity.identify(jdbc, table, id.longValue(), directory);
    }

    @Override
    public Range range() {
        String dateColumn = layout.physicalDateColumn("stk_suspend");
        String sql =
                "SELECT cast(min("
                        + dateColumn
                        + ") AS long) AS min_micros,cast(max("
                        + dateColumn
                        + ") AS long) AS max_micros FROM \""
                        + table
                        + "\"";
        return jdbc.query(
                sql,
                rs -> {
                    if (!rs.next())
                        throw new IllegalStateException(
                                "QuestDB did not return stk_suspend date range aggregate");
                    Object min = rs.getObject("min_micros"), max = rs.getObject("max_micros");
                    if (min == null && max == null) return new Range(null, null);
                    if (!(min instanceof Number minValue) || !(max instanceof Number maxValue))
                        throw new IllegalStateException(
                                "QuestDB stk_suspend range is not a timestamp epoch");
                    return new Range(
                            CalendarTimestamp.fromStorageEpoch(
                                            minValue.longValue(), EpochUnit.MICROS)
                                    .date(),
                            CalendarTimestamp.fromStorageEpoch(
                                            maxValue.longValue(), EpochUnit.MICROS)
                                    .date());
                });
    }

    @Override
    public StockSuspendWriteSession newWriter(String frozenPhysicalTargetId) {
        return new StockSuspendWritePort(table, jdbc, questdb, frozenPhysicalTargetId, layout);
    }

    @Override
    public StockSuspendTables.Table openTable(String table) {
        return new StockSuspendStorage(jdbc, table, layout);
    }

    @Override
    public String physicalTargetId(String table, Identity identity) {
        return StockSuspendStorage.physicalTargetId(jdbc, table, identity);
    }

    @Override
    public Prepared prepare(
            Snapshot before,
            Snapshot current,
            List<StockSuspend> source,
            LocalDate from,
            LocalDate to)
            throws Exception {
        return StockSuspendStaging.prepare(before, current, source, from, to);
    }

    @Override
    public StockSuspendStagingPort newStaging() {
        return new StockSuspendStaging(jdbc, table, layout);
    }

    @Override
    public StockSuspendTables newPublicationTables() {
        return new QuestDbStockSuspendTables(jdbc, layout);
    }
}
