package com.zoutrankil.data.factor.storage;

import com.zoutrankil.data.domain.JobDefinitionJson;
import com.zoutrankil.data.domain.table.FactorDailyMetricRow;
import com.zoutrankil.data.factor.application.DailyMetricDelta;
import com.zoutrankil.data.repository.*;
import com.zoutrankil.data.service.VerifiedBatchExecutor;
import com.zoutrankil.data.sync.port.VerifiedWriteSession;
import io.questdb.client.QuestDB;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;

/** Typed bounded metric writer, called under the job runner's durable submission and date lock. */
public final class DailyMetricWriteSession implements VerifiedWriteSession<FactorDailyMetricRow,DailyMetricDelta.Key> {
    private final JdbcTemplate jdbc;private final QuestDB questdb;private final String target;
    private boolean senderStopped;
    public DailyMetricWriteSession(JdbcTemplate template,QuestDB questdb,String expectedTarget) {
        jdbc=new JdbcTemplate(Objects.requireNonNull(template.getDataSource()));jdbc.setQueryTimeout(60);
        this.questdb=Objects.requireNonNull(questdb);target=Objects.requireNonNull(expectedTarget);
    }
    public static String targetIdentity(JdbcTemplate jdbc) {
        var rows=jdbc.queryForList("SELECT id,directoryName FROM tables() WHERE table_name='factor_daily_metric'");
        if(rows.size()!=1)throw new IllegalStateException("Metric target missing");
        return StaticTargetIdentity.identify(jdbc,"factor_daily_metric",((Number)rows.getFirst().get("id")).longValue(),
                (String)rows.getFirst().get("directoryName"));
    }
    public static final VerifiedBatchExecutor.Codec<FactorDailyMetricRow,DailyMetricDelta.Key> CODEC=new VerifiedBatchExecutor.Codec<>() {
        public DailyMetricDelta.Key key(FactorDailyMetricRow row){return DailyMetricDelta.key(row);}
        public byte[] canonicalBytes(FactorDailyMetricRow row) {
            try{return JobDefinitionJson.mapper().writeValueAsBytes(DailyMetricStorageMapping.values(row));}
            catch(Exception failure){throw new IllegalArgumentException("Invalid metric encoding",failure);}
        }
        public int estimatedTransportBytes(FactorDailyMetricRow row,byte[] canonical){return Math.addExact(Math.multiplyExact(canonical.length,4),128);}
    };
    public VerifiedBatchExecutor.Codec<FactorDailyMetricRow,DailyMetricDelta.Key> codec(){return CODEC;}
    public String targetIdentity() {
        String actual=targetIdentity(jdbc);
        if(!target.equals(actual))throw new IllegalStateException("Metric target identity changed");
        return actual;
    }
    public void preflight() {
        var columns=jdbc.queryForList("SELECT \"column\",\"type\",upsertKey FROM table_columns('factor_daily_metric')");
        var types=new LinkedHashMap<String,String>();var keys=new HashSet<String>();
        for(var column:columns){String name=(String)column.get("column");types.put(name,(String)column.get("type"));if(Boolean.TRUE.equals(column.get("upsertKey")))keys.add(name);}
        var metadata=jdbc.queryForList("SELECT designatedTimestamp,partitionBy FROM tables() WHERE table_name='factor_daily_metric'");
        if(!types.equals(DailyMetricStorageMapping.types()) || !keys.equals(DailyMetricStorageMapping.KEYS)
                || metadata.size()!=1 || !"trade_date".equals(metadata.getFirst().get("designatedTimestamp"))
                || !"MONTH".equals(metadata.getFirst().get("partitionBy")) || !target.equals(targetIdentity(jdbc)) || !walSettled())
            throw new IllegalStateException("Metric target schema, identity or WAL differs");
    }
    public void send(List<FactorDailyMetricRow> rows)throws Exception {
        if(rows.isEmpty() || rows.size()>250)throw new IllegalArgumentException("Expected 1..250 metrics");
        preflight();var keys=new HashSet<DailyMetricDelta.Key>();int bytes=0;
        for(var row:rows) {
            if(!keys.add(CODEC.key(row)))throw new IllegalArgumentException("Duplicate metric write key");
            if(!row.tradeDate().equals(row.tradeDate().atOffset(ZoneOffset.UTC).toLocalDate().atStartOfDay().toInstant(ZoneOffset.UTC)))
                throw new IllegalArgumentException("Metric date must be UTC midnight");
            if(!Objects.equals(row.win(),row.rankIc()==null?null:row.rankIc()>0)
                    || !Objects.equals(row.pureWin(),row.pureRankIc()==null?null:row.pureRankIc()>0))
                throw new IllegalArgumentException("Metric win flag disagrees with IC availability/sign");
            for(var value:DailyMetricStorageMapping.values(row).values())if(value instanceof Instant time && time.getNano()%1000!=0)
                throw new IllegalArgumentException("Metric timestamp exceeds microsecond precision");
            bytes=Math.addExact(bytes,CODEC.estimatedTransportBytes(row,CODEC.canonicalBytes(row)));
        }
        if(bytes>1024*1024)throw new IllegalArgumentException("Metric write byte budget exceeded");
        var delta=DailyMetricDelta.compare(rows,readback(rows.stream().map(DailyMetricDelta::key).toList()));
        if(!delta.newerTargetKeys().isEmpty())throw new IllegalStateException("Newer target metrics require fresh input reconciliation");
        rows=delta.writes();
        if(rows.isEmpty()){senderStopped=true;return;}
        senderStopped=false;var sender=questdb.borrowSender();
        try {
            for(var row:rows) {
                var values=DailyMetricStorageMapping.values(row);var line=sender.table("factor_daily_metric");
                for(var e:values.entrySet())if(e.getValue() instanceof String text)line.symbol(e.getKey(),text);
                for(var e:values.entrySet()) {
                    Object value=e.getValue();String name=e.getKey();
                    if(value instanceof Double number)line.doubleColumn(name,number);
                    else if(value instanceof Integer number)line.longColumn(name,number);
                    else if(value instanceof Boolean flag)line.boolColumn(name,flag);
                    else if(value instanceof Instant time && !name.equals("trade_date"))line.timestampColumn(name,time);
                }
                line.at(row.tradeDate());
            }
            long sequence=sender.flushAndGetSequence();
            if(sequence<0 || !sender.awaitAckedFsn(sequence,10000))throw new IllegalStateException("Metric acknowledgement uncertain; reconcile before replay");
        }finally{sender.close();senderStopped=true;}
    }
    public List<FactorDailyMetricRow> readback(List<DailyMetricDelta.Key> keys) {
        if(keys.isEmpty())return List.of();
        if(keys.size()>250 || new HashSet<>(keys).size()!=keys.size())throw new IllegalArgumentException("At most 250 unique metric keys");
        if(!target.equals(targetIdentity(jdbc)))throw new IllegalStateException("Metric target changed");
        var dates=keys.stream().map(k->k.date().atOffset(ZoneOffset.UTC).toLocalDate()).distinct().toList();
        var wanted=new HashSet<>(keys);var result=new ArrayList<FactorDailyMetricRow>();
        for(var date:dates)for(var row:new QuestDbDailyMetrics(jdbc).read(date))if(wanted.contains(DailyMetricDelta.key(row)))result.add(row);
        return List.copyOf(result);
    }
    public boolean walSettled(){return QuestDbWriteChecks.walSettled(jdbc,"factor_daily_metric");}
    public boolean uncertainSenderStopped(){return senderStopped;}
}
