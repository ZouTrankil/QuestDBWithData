package com.zoutrankil.data.etf.storage;

import com.zoutrankil.data.domain.EtfPortfolio;
import com.zoutrankil.data.domain.EtfPortfolioKey;
import com.zoutrankil.data.domain.EtfPortfolioDataset;
import com.zoutrankil.data.etf.port.EtfTarget;
import com.zoutrankil.data.etf.port.EtfWriteSession;
import com.zoutrankil.data.repository.StaticTargetIdentity;
import io.questdb.client.QuestDB;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;
import com.zoutrankil.data.etf.domain.EtfTargetRange;
import java.time.LocalDate;
import com.zoutrankil.data.repository.QuestDbWriteChecks;

/** Configured physical target; each execution obtains an independent write session. */
public final class QuestDbEtfPortfolioTarget implements EtfTarget<EtfPortfolio,EtfPortfolioKey> {
    private final String table;
    private final JdbcTemplate jdbc;
    private final QuestDB questdb;

    public QuestDbEtfPortfolioTarget(String table,JdbcTemplate jdbc,QuestDB questdb) {
        this.jdbc=Objects.requireNonNull(jdbc);
        this.questdb=Objects.requireNonNull(questdb);
        EtfPortfolioDataset.requireExecutionTable(table);
        this.table=table;
    }

    @Override public String tableName() { return table; }

    @Override public String targetId() {
        EtfPortfolioDataset.requireExecutionTable(table);
        QuestDbWriteChecks.preflight(jdbc, table, EtfPortfolioDataset.definition(table));
        var rows = jdbc.queryForList("SELECT id,directoryName FROM tables() WHERE table_name=?", table);
        if (rows.size() != 1 || !(rows.getFirst().get("id") instanceof Number id)
                || !(rows.getFirst().get("directoryName") instanceof String directory))
            throw new IllegalStateException("Exact isolated etf_portfolio target identity required");
        return StaticTargetIdentity.identify(jdbc, table, id.longValue(), directory);
    }

    @Override public EtfTargetRange range() {
        return jdbc.query("SELECT cast(min(ann_date) AS long) AS min_micros, cast(max(ann_date) AS long) AS max_micros FROM \"" + table + "\"", rs -> {
            if (!rs.next()) throw new IllegalStateException("QuestDB did not return etf_portfolio announcement range");
            Object min = rs.getObject("min_micros"), max = rs.getObject("max_micros");
            if (min == null && max == null) return checkedRange(null, null);
            if (!(min instanceof Number minValue) || !(max instanceof Number maxValue))
                throw new IllegalStateException("Invalid etf_portfolio physical announcement-date range");
            var from = com.zoutrankil.data.domain.temporal.TemporalValues.CalendarTimestamp
                    .fromStorageEpoch(minValue.longValue(), com.zoutrankil.data.domain.temporal.TemporalValues.EpochUnit.MICROS).date();
            var to = com.zoutrankil.data.domain.temporal.TemporalValues.CalendarTimestamp
                    .fromStorageEpoch(maxValue.longValue(), com.zoutrankil.data.domain.temporal.TemporalValues.EpochUnit.MICROS).date();
            return checkedRange(from, to);
        });
    }

    private static EtfTargetRange checkedRange(LocalDate min,LocalDate max) {
        if ((min == null) != (max == null) || min != null && min.isAfter(max))
            throw new IllegalStateException("Invalid physical etf_portfolio announcement-date range");
        return new EtfTargetRange(min,max);
    }

    @Override public EtfWriteSession<EtfPortfolio,EtfPortfolioKey> newWriter(String frozenTargetId) {
        return new EtfPortfolioWritePort(table,frozenTargetId,jdbc,questdb);
    }
}
