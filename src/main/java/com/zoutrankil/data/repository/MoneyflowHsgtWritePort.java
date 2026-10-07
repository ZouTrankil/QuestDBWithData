package com.zoutrankil.data.repository;

import com.zoutrankil.data.domain.JobDefinitionJson;
import com.zoutrankil.data.domain.MoneyflowHsgt;
import com.zoutrankil.data.domain.MoneyflowHsgtDataset;
import com.zoutrankil.data.domain.MoneyflowHsgtKey;
import com.zoutrankil.data.domain.temporal.TemporalValues;
import com.zoutrankil.data.mapper.MoneyflowHsgtMapper;
import com.zoutrankil.data.service.MoneyflowHsgtSource;
import com.zoutrankil.data.service.StaticTargetIdentity;
import com.zoutrankil.data.service.VerifiedBatchExecutor;
import io.questdb.client.QuestDB;
import io.questdb.client.Sender;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;

/** D027 append-only stage writer. It never writes the non-DEDUP formal target directly. */
public final class MoneyflowHsgtWritePort implements VerifiedBatchExecutor.Port<MoneyflowHsgt,MoneyflowHsgtKey> {
    public static final int MAX_BATCH_ROWS = 250;
    public static final int MAX_BATCH_BYTES = 1024 * 1024;
    private static final Duration ACK_TIMEOUT = Duration.ofSeconds(10);
    public static final VerifiedBatchExecutor.Codec<MoneyflowHsgt,MoneyflowHsgtKey> CODEC = new VerifiedBatchExecutor.Codec<>() {
        @Override public MoneyflowHsgtKey key(MoneyflowHsgt row) { return row == null ? null : row.key(); }
        @Override public byte[] canonicalBytes(MoneyflowHsgt row) {
            try { return JobDefinitionJson.mapper().writeValueAsBytes(new MoneyflowHsgtMapper().values(row).asMap()); }
            catch (Exception failure) { throw new IllegalArgumentException("Cannot canonicalize D027 row", failure); }
        }
        @Override public int estimatedTransportBytes(MoneyflowHsgt row, byte[] canonical) {
            return Math.addExact(Math.multiplyExact(canonical.length, 4), 128);
        }
    };
    private final String formalTable, frozenTargetId;
    private volatile String writeTable, writeTargetId;
    private final JdbcTemplate jdbc;
    private final QuestDB questdb;
    private final MoneyflowHsgtMapper mapper = new MoneyflowHsgtMapper();
    private volatile boolean senderStopped;
    public MoneyflowHsgtWritePort(String formalTable, String frozenTargetId, JdbcTemplate jdbc, QuestDB questdb) {
        MoneyflowHsgtDataset.requireAdmittedTable(formalTable);
        if (frozenTargetId == null || !frozenTargetId.matches("static-v2-[0-9a-f]{64}"))
            throw new IllegalArgumentException("Frozen D027 logical target id required");
        this.formalTable = formalTable; this.frozenTargetId = frozenTargetId;
        this.writeTable = formalTable; this.writeTargetId = frozenTargetId;
        this.jdbc = new JdbcTemplate(Objects.requireNonNull(jdbc).getDataSource());
        this.jdbc.setQueryTimeout(20); this.jdbc.setMaxRows(MAX_BATCH_ROWS + 1);
        this.questdb = Objects.requireNonNull(questdb);
    }
    /** Bind only to the journal-owned, same-schema non-DEDUP stage for the current run. */
    public synchronized void useStage(String stage, String stageId) {
        MoneyflowHsgtDataset.requireIsolatedTable(stage);
        if (!stage.contains("_stage_") || stageId == null || !stageId.matches("static-v2-[0-9a-f]{64}")
                || stageId.equals(frozenTargetId)) throw new IllegalArgumentException("D027 verified stage identity required");
        writeTable = stage; writeTargetId = stageId;
    }
    public String formalTable() { return formalTable; }
    public String stageTable() { return writeTable; }
    @Override public void preflight() {
        requireWriteTarget();
        QuestDbWriteChecks.preflight(jdbc, writeTable, MoneyflowHsgtDataset.admittedWriteDefinition(writeTable));
        requireWriteTarget();
    }
    @Override public void send(List<MoneyflowHsgt> rows) throws Exception {
        if (rows == null || rows.isEmpty() || rows.size() > MAX_BATCH_ROWS)
            throw new IllegalArgumentException("D027 requires 1..250 row batches");
        if (writeTable.equals(formalTable)) throw new IllegalStateException("D027 direct writes are forbidden; bind a full-snapshot stage first");
        preflight(); senderStopped = false;
        DatasetWritePreparation.prepareWalReplace(MoneyflowHsgtDataset.isolatedWriteDefinition(writeTable), rows,
                mapper::values, new DatasetWritePreparation.Limits(MAX_BATCH_ROWS, MAX_BATCH_BYTES));
        long bytes = 0;
        for (var row : rows) {
            bytes = Math.addExact(bytes, CODEC.estimatedTransportBytes(row, CODEC.canonicalBytes(row)));
            if (bytes > MAX_BATCH_BYTES) throw new IllegalArgumentException("D027 ILP batch exceeds 1 MiB before send");
        }
        boolean attempted = false;
        try (Sender sender = questdb.borrowSender()) {
            for (var row : rows) {
                var line = sender.table(writeTable);
                if (row.ggtSs() != null) line.doubleColumn("ggt_ss", row.ggtSs());
                if (row.ggtSz() != null) line.doubleColumn("ggt_sz", row.ggtSz());
                if (row.hgt() != null) line.doubleColumn("hgt", row.hgt());
                if (row.sgt() != null) line.doubleColumn("sgt", row.sgt());
                if (row.northMoney() != null) line.doubleColumn("north_money", row.northMoney());
                if (row.southMoney() != null) line.doubleColumn("south_money", row.southMoney());
                line.at(new TemporalValues.CalendarTimestamp(row.tradeDate()).storageCarrier());
            }
            long sequence = sender.flushAndGetSequence(); attempted = true;
            if (sequence < 0 || !sender.awaitAckedFsn(sequence, ACK_TIMEOUT.toMillis()))
                throw new IllegalStateException("D027 stage ACK is unknown; do not publish before exact stage reconciliation");
        } catch (Exception failure) { attempted = true; throw failure; }
        finally { senderStopped = attempted; }
    }
    @Override public List<MoneyflowHsgt> readback(List<MoneyflowHsgtKey> keys) {
        if (keys == null || keys.isEmpty() || keys.size() > MAX_BATCH_ROWS || new HashSet<>(keys).size() != keys.size())
            throw new IllegalArgumentException("D027 readback requires 1..250 unique trade dates");
        requireWriteTarget(); var values = new ArrayList<Object>();
        for (var key : keys) values.add(micros(key.tradeDate()));
        String predicates = String.join(" OR ", keys.stream().map(k -> "trade_date=cast(? AS TIMESTAMP)").toList());
        var result = jdbc.query(select(writeTable) + " WHERE " + predicates + " ORDER BY trade_date LIMIT " + (keys.size() + 1),
                this::physical, values.toArray());
        if (result.size() > keys.size()) throw new IllegalStateException("D027 stage has duplicate business dates");
        return List.copyOf(result);
    }
    public List<MoneyflowHsgt> readWindow(String table, LocalDate from, LocalDate to) {
        requireBounded(from, to); requireTableIdentity(table);
        long lower = micros(from), upper = micros(to.plusDays(1));
        var rows = jdbc.query(select(table) + " WHERE trade_date>=cast(? AS TIMESTAMP) AND trade_date<cast(? AS TIMESTAMP) ORDER BY trade_date LIMIT "
                + (Math.toIntExact(java.time.temporal.ChronoUnit.DAYS.between(from,to)+1)+1), this::physical, lower, upper);
        long limit = java.time.temporal.ChronoUnit.DAYS.between(from,to)+1;
        if (rows.size() > limit) throw new IllegalStateException("D027 window contains duplicate natural dates");
        return List.copyOf(rows);
    }
    public TargetRange readTargetRange() {
        requireTableIdentity(formalTable);
        return jdbc.query("SELECT cast(min(trade_date) AS long) AS min_micros,cast(max(trade_date) AS long) AS max_micros,count(*) AS row_count FROM \""+formalTable+"\"", rs -> {
            if (!rs.next()) throw new SQLException("D027 target range unavailable");
            Object min=rs.getObject("min_micros"), max=rs.getObject("max_micros"), count=rs.getObject("row_count");
            if (!(count instanceof Number n)) throw new SQLException("D027 target count unavailable");
            if(min==null&&max==null) { if(n.longValue()!=0)throw new SQLException("D027 empty range/count mismatch"); return new TargetRange(null,null,0); }
            if(!(min instanceof Number a)||!(max instanceof Number b)||n.longValue()<1)throw new SQLException("D027 target range invalid");
            return new TargetRange(date(a.longValue()),date(b.longValue()),n.longValue());
        });
    }
    @Override public boolean walSettled() { requireWriteTarget(); return QuestDbWriteChecks.walSettled(jdbc, writeTable); }
    @Override public boolean uncertainSenderStopped() { return senderStopped; }
    public record TargetRange(LocalDate min, LocalDate max, long rows) {
        public TargetRange { if ((min==null)!=(max==null)||min!=null&&min.isAfter(max)||rows<0||(min==null)!=(rows==0))throw new IllegalArgumentException("Invalid D027 target range"); }
        public boolean empty() { return rows == 0; }
    }
    private MoneyflowHsgt physical(ResultSet rs,int n)throws SQLException {
        Object raw=rs.getObject("trade_micros");if(!(raw instanceof Number micros))throw new SQLException("D027 timestamp required");
        var v=new LinkedHashMap<String,Object>();v.put("trade_date",date(micros.longValue()));
        for(String field:List.of("ggt_ss","ggt_sz","hgt","sgt","north_money","south_money")){
            Object value=rs.getObject(field);if(value!=null&&(!(value instanceof Number number)||!Double.isFinite(number.doubleValue())))throw new SQLException("Invalid D027 metric "+field);
            v.put(field,value==null?null:((Number)value).doubleValue());}
        try{return mapper.fromValues(new com.zoutrankil.data.domain.DatasetValues(v));}
        catch(RuntimeException invalid){throw new SQLException("Invalid D027 physical row",invalid);}
    }
    private static String select(String table) { return "SELECT cast(trade_date AS long) AS trade_micros,ggt_ss,ggt_sz,hgt,sgt,north_money,south_money FROM \""+table+"\""; }
    private void requireWriteTarget() { requireTableIdentity(writeTable); }
    private void requireTableIdentity(String table) {
        var rows=jdbc.queryForList("SELECT id,directoryName FROM tables() WHERE table_name=?",table);
        if(rows.size()!=1||!(rows.getFirst().get("id") instanceof Number id)||!(rows.getFirst().get("directoryName") instanceof String dir)
                ||!(table.equals(formalTable)?frozenTargetId:writeTargetId).equals(StaticTargetIdentity.identify(jdbc,table,id.longValue(),dir)))
            throw new IllegalStateException("D027 physical table generation changed");
    }
    private static long micros(LocalDate day){return new TemporalValues.CalendarTimestamp(day).storageEpoch(TemporalValues.EpochUnit.MICROS);}
    private static LocalDate date(long micros)throws SQLException{try{return TemporalValues.CalendarTimestamp.fromStorageEpoch(micros,TemporalValues.EpochUnit.MICROS).date();}catch(RuntimeException invalid){throw new SQLException("Invalid D027 business timestamp",invalid);}}
    private static void requireBounded(LocalDate from,LocalDate to){if(from==null||to==null||from.isAfter(to)||java.time.temporal.ChronoUnit.DAYS.between(from,to)+1>MoneyflowHsgtSource.MAX_RANGE_DAYS)throw new IllegalArgumentException("D027 read window must be <=31 days");}
}
