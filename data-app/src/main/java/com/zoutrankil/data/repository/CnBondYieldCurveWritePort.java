package com.zoutrankil.data.repository;

import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.domain.table.CnBondYieldCurveRow;
import com.zoutrankil.data.domain.temporal.TemporalValues;
import com.zoutrankil.data.service.VerifiedBatchExecutor;
import io.questdb.client.QuestDB;
import io.questdb.client.Sender;
import org.springframework.jdbc.core.JdbcTemplate;
import java.security.MessageDigest;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.*;
import java.util.*;

/** Exact formal YEAR/WAL/three-key DEDUP upsert, with all-six-field typed readback. No DDL. */
public final class CnBondYieldCurveWritePort implements VerifiedBatchExecutor.Port<CnBondYieldCurveRow,CnBondYieldCurveKey> {
    public static final String FORMAL="cn_bond_yield_curve";
    private final JdbcTemplate jdbc;private final QuestDB questdb;private final String table,targetId;
    private volatile boolean senderStopped;
    public CnBondYieldCurveWritePort(JdbcTemplate jdbc,QuestDB questdb,String table,String targetId) {
        if(!FORMAL.equals(table))throw new IllegalArgumentException("Only exact formal cn_bond_yield_curve is admitted");
        if(targetId==null||!targetId.matches("static-v2-[0-9a-f]{64}"))throw new IllegalArgumentException("Frozen ChinaBond target identity required");
        this.jdbc=new JdbcTemplate(Objects.requireNonNull(jdbc.getDataSource()));this.jdbc.setQueryTimeout(60);this.jdbc.setMaxRows(31001);
        this.questdb=Objects.requireNonNull(questdb);this.table=table;this.targetId=targetId;
    }
    public static DatasetDefinition definition() {
        var columns=new ArrayList<DatasetDefinition.Column>();
        columns.add(new DatasetDefinition.Column("trade_date","trade_date","trade_date",DatasetDefinition.StorageType.TIMESTAMP,false,
                "ChinaBond business date at exact UTC midnight",new DatasetDefinition.TemporalContract(DatasetDefinition.TemporalKind.BUSINESS_DATE,"BASIC","calendar","MICROS","Provider calendar date")));
        for(String field:List.of("curve_name","curve_code","tenor","yield_value","source"))
            columns.add(new DatasetDefinition.Column(field,field,field,field.equals("yield_value")?DatasetDefinition.StorageType.DOUBLE:DatasetDefinition.StorageType.SYMBOL,
                    false,"Existing ChinaBond physical field; native source mapping retains legacy values",null));
        return new DatasetDefinition(FORMAL,1,"chinabond.bond_china_yield","cn_bond_yield_curve_owner",FORMAL,DatasetDefinition.ObjectKind.TABLE,
                columns,List.of("trade_date","curve_code","tenor"),List.of("trade_date","curve_code","tenor"),"trade_date",DatasetDefinition.Partition.YEAR,true,
                Set.of(DatasetDefinition.Capability.READ,DatasetDefinition.Capability.WRITE),List.of("exchange_calendar"),
                "Preserve audited YEAR/WAL/DEDUP(trade_date,curve_code,tenor). Explicit <=31-day source-certified SSE-session BACKFILL only; no formal DDL, invented checkpoint, or deletion of legacy keys.");
    }
    public static CnBondYieldCurveKey key(CnBondYieldCurveRow row) {
        Objects.requireNonNull(row);Instant time=Objects.requireNonNull(row.tradeDate());LocalDate day=time.atOffset(ZoneOffset.UTC).toLocalDate();
        if(!time.equals(day.atStartOfDay().toInstant(ZoneOffset.UTC)))throw new IllegalArgumentException("Exact UTC-midnight ChinaBond business date required");
        if(row.curveName()==null||row.curveName().isBlank()||row.source()==null||row.source().isBlank()
                ||row.yieldValue()==null||!Double.isFinite(row.yieldValue()))throw new IllegalArgumentException("Complete finite ChinaBond row required");
        return new CnBondYieldCurveKey(day,row.curveCode(),row.tenor());
    }
    public static Map<String,Object> values(CnBondYieldCurveRow row) {
        key(row);var value=new LinkedHashMap<String,Object>();value.put("trade_date",row.tradeDate());value.put("curve_name",row.curveName());
        value.put("curve_code",row.curveCode());value.put("tenor",row.tenor());value.put("yield_value",row.yieldValue());value.put("source",row.source());return value;
    }
    public static final VerifiedBatchExecutor.Codec<CnBondYieldCurveRow,CnBondYieldCurveKey> CODEC=new VerifiedBatchExecutor.Codec<>() {
        public CnBondYieldCurveKey key(CnBondYieldCurveRow row){return CnBondYieldCurveWritePort.key(row);}
        public byte[] canonicalBytes(CnBondYieldCurveRow row){try{return JobDefinitionJson.mapper().writeValueAsBytes(values(row));}
            catch(Exception error){throw new IllegalArgumentException("Cannot canonicalize ChinaBond row",error);}}
        public int estimatedTransportBytes(CnBondYieldCurveRow row,byte[] bytes){return Math.addExact(Math.multiplyExact(bytes.length,4),128);}
    };
    public static String targetId(JdbcTemplate jdbc) {
        QuestDbWriteChecks.preflight(jdbc,FORMAL,definition());var rows=jdbc.queryForList("SELECT id,directoryName FROM tables() WHERE table_name=?",FORMAL);
        if(rows.size()!=1||!(rows.getFirst().get("id") instanceof Number id)||!(rows.getFirst().get("directoryName") instanceof String directory))
            throw new IllegalStateException("Exact formal ChinaBond table identity required");
        return StaticTargetIdentity.identify(jdbc,FORMAL,id.longValue(),directory);
    }
    public void preflight(){requireExpectedTarget();QuestDbWriteChecks.preflight(jdbc,table,definition());requireExpectedTarget();}
    private void requireExpectedTarget(){
        var rows=jdbc.queryForList("SELECT id,directoryName FROM tables() WHERE table_name=?",table);
        if(rows.size()!=1||!(rows.getFirst().get("id") instanceof Number id)||!(rows.getFirst().get("directoryName") instanceof String directory)
                ||!targetId.equals(StaticTargetIdentity.identify(jdbc,table,id.longValue(),directory)))throw new IllegalStateException("ChinaBond target generation changed");
    }
    public void send(List<CnBondYieldCurveRow> rows)throws Exception {
        if(rows==null||rows.isEmpty()||rows.size()>250)throw new IllegalArgumentException("ChinaBond batch requires 1..250 rows");
        preflight();senderStopped=false;long bytes=0;var seen=new HashSet<CnBondYieldCurveKey>();
        for(var row:rows){if(!seen.add(key(row)))throw new IllegalArgumentException("Duplicate ChinaBond source key");
            bytes=Math.addExact(bytes,CODEC.estimatedTransportBytes(row,CODEC.canonicalBytes(row)));if(bytes>1024*1024)throw new IllegalArgumentException("ChinaBond batch exceeds 1 MiB");}
        boolean attempted=false;
        try(Sender sender=questdb.borrowSender()) {
            for(var row:rows)sender.table(table).symbol("curve_name",row.curveName()).symbol("curve_code",row.curveCode())
                    .symbol("tenor",row.tenor()).symbol("source",row.source()).doubleColumn("yield_value",row.yieldValue()).at(row.tradeDate());
            long sequence=sender.flushAndGetSequence();attempted=true;
            if(sequence<0||!sender.awaitAckedFsn(sequence,10000))throw new IllegalStateException("ChinaBond QWP ACK unknown; exact keys must be reconciled before replay");
        }catch(Exception error){attempted=true;throw error;}finally{senderStopped=attempted;}
    }
    public List<CnBondYieldCurveRow> readback(List<CnBondYieldCurveKey> keys) {
        if(keys==null||keys.isEmpty()||keys.size()>250||new HashSet<>(keys).size()!=keys.size())throw new IllegalArgumentException("ChinaBond readback requires 1..250 unique full keys");
        requireExpectedTarget();var predicates=new ArrayList<String>();var parameters=new ArrayList<Object>();
        LocalDate from=keys.stream().map(CnBondYieldCurveKey::tradeDate).min(LocalDate::compareTo).orElseThrow(),to=keys.stream().map(CnBondYieldCurveKey::tradeDate).max(LocalDate::compareTo).orElseThrow();
        parameters.add(micros(from));parameters.add(micros(to.plusDays(1)));
        for(var key:keys){predicates.add("(trade_date=cast(? AS TIMESTAMP) AND curve_code=? AND tenor=?)");parameters.add(micros(key.tradeDate()));parameters.add(key.curveCode());parameters.add(key.tenor());}
        var result=jdbc.query(select()+" WHERE trade_date>=cast(? AS TIMESTAMP) AND trade_date<cast(? AS TIMESTAMP) AND ("+String.join(" OR ",predicates)+") ORDER BY trade_date,curve_code,tenor LIMIT "+(keys.size()+1),this::physical,parameters.toArray());
        requireUnique(result);return List.copyOf(result);
    }
    public List<CnBondYieldCurveRow> readWindow(LocalDate from,LocalDate to) {
        if(from==null||to==null||from.isAfter(to)||java.time.temporal.ChronoUnit.DAYS.between(from,to)>=31)throw new IllegalArgumentException("ChinaBond read window must be <=31 days");
        requireExpectedTarget();var rows=jdbc.query(select()+" WHERE trade_date>=cast(? AS TIMESTAMP) AND trade_date<cast(? AS TIMESTAMP) ORDER BY trade_date,curve_code,tenor LIMIT 31001",this::physical,micros(from),micros(to.plusDays(1)));
        if(rows.size()>31000)throw new IllegalStateException("ChinaBond window exceeds bounded 31000-row inventory");requireUnique(rows);return List.copyOf(rows);
    }
    public void requireCompatibleDate(LocalDate date,List<CnBondYieldCurveRow> expected) {
        var expectedKeys=new HashSet<CnBondYieldCurveKey>();for(var row:expected)if(!date.equals(key(row).tradeDate())||!expectedKeys.add(key(row)))throw new IllegalArgumentException("ChinaBond expected date or key invalid");
        if(expectedKeys.isEmpty())throw new IllegalStateException("ChinaBond source is empty on an expected SSE trading date");
        for(var row:readWindow(date,date))if(!expectedKeys.contains(key(row)))throw new IllegalStateException("SOURCE_INCOMPLETE: existing ChinaBond curve point is absent from the complete source on "+date+"; inspect before any retraction");
    }
    public static String fingerprint(List<CnBondYieldCurveRow> rows)throws Exception {
        requireUnique(rows);var hash=MessageDigest.getInstance("SHA-256");for(var row:rows.stream().sorted(Comparator.comparing(CnBondYieldCurveWritePort::key)).toList()){hash.update(CODEC.canonicalBytes(row));hash.update((byte)'\n');}return HexFormat.of().formatHex(hash.digest());
    }
    private static void requireUnique(List<CnBondYieldCurveRow> rows){var keys=new HashSet<CnBondYieldCurveKey>();for(var row:rows)if(!keys.add(key(row)))throw new IllegalStateException("Duplicate physical ChinaBond curve-point key");}
    private CnBondYieldCurveRow physical(ResultSet rs,int index)throws SQLException {
        Object timestamp=rs.getObject("trade_micros"),yield=rs.getObject("yield_value");
        if(!(timestamp instanceof Number time)||!(yield instanceof Number value)||!Double.isFinite(value.doubleValue()))throw new SQLException("Typed finite ChinaBond timestamp and yield required");
        LocalDate date=TemporalValues.CalendarTimestamp.fromStorageEpoch(time.longValue(),TemporalValues.EpochUnit.MICROS).date();
        var row=new CnBondYieldCurveRow(date.atStartOfDay().toInstant(ZoneOffset.UTC),rs.getString("curve_name"),rs.getString("curve_code"),rs.getString("tenor"),value.doubleValue(),rs.getString("source"));key(row);return row;
    }
    private String select(){return "SELECT cast(trade_date AS long) AS trade_micros,curve_name,curve_code,tenor,yield_value,source FROM \""+table+"\"";}
    private static long micros(LocalDate date){return new TemporalValues.CalendarTimestamp(date).storageEpoch(TemporalValues.EpochUnit.MICROS);}
    public boolean walSettled(){requireExpectedTarget();return QuestDbWriteChecks.walSettled(jdbc,table);}
    public boolean uncertainSenderStopped(){return senderStopped;}
}
