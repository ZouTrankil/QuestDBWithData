package com.zoutrankil.data.stock.storage;
import com.zoutrankil.data.stock.port.*;
import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.stock.domain.DailyBasicTargetRange;
import com.zoutrankil.data.sync.port.VerifiedWriteSession;
import com.zoutrankil.data.config.QuestDbProperties;
import io.questdb.client.QuestDB;
import org.springframework.jdbc.core.JdbcTemplate;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
public final class DailyBasicQuestDbTarget implements DailyBasicTarget {
    private final String table;
    private final JdbcTemplate jdbc;
    private final QuestDB questdb;
    private final QuestDbProperties properties;
    public DailyBasicQuestDbTarget(String table, JdbcTemplate jdbc, QuestDB questdb, QuestDbProperties properties) {
        this.table=table; this.jdbc=jdbc; this.questdb=questdb; this.properties=properties;
    }
    @Override public String tableName() { return table; }
    @Override public String targetId() throws Exception {
        var rows = jdbc.queryForList("SELECT id,directoryName FROM tables() WHERE table_name = ?", table);
        if (rows.size() != 1 || !(rows.getFirst().get("id") instanceof Number)
                || rows.getFirst().get("directoryName") == null)
            throw new IllegalStateException("Exact daily_basic QuestDB target identity required");
        String identity = properties.getHost() + ":" + properties.getPgPort() + ":" + properties.getQwpPort()
                + ":" + properties.getDatabase() + ":" + table + ":" + rows.getFirst().get("id")
                + ":" + rows.getFirst().get("directoryName");
        return "questdb-" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(identity.getBytes(StandardCharsets.UTF_8)));
    }
    @Override public VerifiedWriteSession<DailyBasic, DailyBasicKey> newWriter() { return new DailyBasicWritePort(table,jdbc,questdb); }
    @Override public DailyBasicTargetRange range() {
        String sql = "SELECT cast(min(trade_date) AS long) AS min_micros, cast(max(trade_date) AS long) AS max_micros FROM \"" + table + "\"";
        return jdbc.query(sql, rs -> {
            if (!rs.next()) throw new IllegalStateException("QuestDB did not return the daily_basic date range aggregate");
            Object min = rs.getObject("min_micros"), max = rs.getObject("max_micros");
            if (min == null && max == null) return new DailyBasicTargetRange(null, null);
            if (!(min instanceof Number minValue) || !(max instanceof Number maxValue))
                throw new IllegalStateException("QuestDB daily_basic date range is not a timestamp epoch");
            var minDate = com.zoutrankil.data.domain.temporal.TemporalValues.CalendarTimestamp
                    .fromStorageEpoch(minValue.longValue(), com.zoutrankil.data.domain.temporal.TemporalValues.EpochUnit.MICROS).date();
            var maxDate = com.zoutrankil.data.domain.temporal.TemporalValues.CalendarTimestamp
                    .fromStorageEpoch(maxValue.longValue(), com.zoutrankil.data.domain.temporal.TemporalValues.EpochUnit.MICROS).date();
            return new DailyBasicTargetRange(minDate, maxDate);
        });
    }
}
