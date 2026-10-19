package com.zoutrankil.data.financial.storage;

import com.zoutrankil.data.financial.domain.FinancialContract;
import com.zoutrankil.data.financial.domain.FinancialContract.*;
import com.zoutrankil.data.financial.port.FinancialTarget;
import com.zoutrankil.data.repository.*;
import com.zoutrankil.data.domain.JobDefinitionJson;
import com.zoutrankil.data.service.VerifiedBatchExecutor;
import io.questdb.client.*;
import org.springframework.jdbc.core.JdbcTemplate;
import java.time.*;
import java.util.*;

/** 只写已有正式表；发送前检查列和 dedup，按完整键回读全部字段。 */
public final class FinancialWritePort implements FinancialTarget {
    public static final VerifiedBatchExecutor.Codec<Row,Key> CODEC=new VerifiedBatchExecutor.Codec<>() {
        public Key key(Row row){return row.key();}
        public byte[] canonicalBytes(Row row){try{return JobDefinitionJson.canonicalMapper().writeValueAsBytes(row);}catch(Exception e){throw new IllegalArgumentException(e);}}
        public int estimatedTransportBytes(Row row,byte[] b){return Math.addExact(128,Math.multiplyExact(b.length,4));}
    };
    private final FinancialContract contract;private final JdbcTemplate jdbc;private final QuestDB questdb;
    public FinancialWritePort(FinancialContract contract,JdbcTemplate jdbc,QuestDB questdb){this.contract=contract;this.jdbc=new JdbcTemplate(jdbc.getDataSource());this.jdbc.setQueryTimeout(30);this.questdb=questdb;}
    public String targetId(){var rows=jdbc.queryForList("SELECT id,directoryName FROM tables() WHERE table_name=?",contract.dataset());if(rows.size()!=1)throw new IllegalStateException("财务正式表缺失");var r=rows.getFirst();return StaticTargetIdentity.identify(jdbc,contract.dataset(),((Number)r.get("id")).longValue(),r.get("directoryName").toString());}
    public void preflight(){QuestDbWriteChecks.preflight(jdbc,contract.dataset(),contract.definition());}
    public boolean walSettled(){return QuestDbWriteChecks.walSettled(jdbc,contract.dataset());}
    /** 旧表额外的本地列没有供应商契约；在整表锁内保留原值，不用 NULL 覆盖。 */
    public List<Row> preserveLocalColumns(List<Row> rows) {
        var local=contract.fields().stream().filter(f->!f.collected()).map(Field::name).toList();if(local.isEmpty()||rows.isEmpty())return rows;
        var before=new HashMap<Key,Row>();for(var old:readback(rows.stream().map(Row::key).toList()))if(before.put(old.key(),old)!=null)throw new IllegalStateException("本地财务列存在重复业务键");
        return rows.stream().map(row->{var values=new TreeMap<>(row.values());var old=before.get(row.key());if(old!=null)for(String field:local)values.put(field,old.values().get(field));return new Row(values);}).toList();
    }
    public void send(List<Row> rows)throws Exception {
        if(rows.isEmpty()||rows.size()>100)throw new IllegalArgumentException("财务批次必须为 1..100 行");
        try(Sender sender=questdb.borrowSender()) {
            for(var row:rows) {
                var target=sender.table(contract.dataset());
                for(var f:contract.fields())if(f.type().equals("SYMBOL")&&row.values().get(f.name())!=null)target.symbol(f.name(),row.values().get(f.name()).toString());
                for(var f:contract.fields()) {
                    Object v=row.values().get(f.name());if(v==null||f.name().equals("ann_date")||f.type().equals("SYMBOL"))continue;
                    switch(f.type()) {case "STRING" -> target.stringColumn(f.name(),v.toString());case "DOUBLE" -> target.doubleColumn(f.name(),((Number)v).doubleValue());case "INT","LONG" -> target.longColumn(f.name(),((Number)v).longValue());default -> throw new IllegalArgumentException("未登记写入类型");}
                }
                target.at(row.key().announcement().atStartOfDay(ZoneOffset.UTC).toInstant());
            }
            long sequence=sender.flushAndGetSequence();if(sequence<0||!sender.awaitAckedFsn(sequence,10000))throw new IllegalStateException("财务发送结果未决");
        }
    }
    public List<Row> readback(List<Key> keys) {
        if(keys.isEmpty()||keys.size()>100||new HashSet<>(keys).size()!=keys.size())throw new IllegalArgumentException("财务回读需完整唯一业务键");
        var params=new ArrayList<Object>();var clauses=new ArrayList<String>();
        for(var key:keys){clauses.add("(ts_code=? AND ann_date=cast(? AS TIMESTAMP) AND end_date=?)");params.add(key.code());params.add(Math.multiplyExact(key.announcement().toEpochDay(),86400000000L));params.add(key.period());}
        String names=String.join(",",contract.fields().stream().map(f->"\""+f.name()+"\"").toList());
        return jdbc.query("SELECT "+names+" FROM "+contract.dataset()+" WHERE "+String.join(" OR ",clauses)+" LIMIT "+(keys.size()+1),(rs,n)->{
            var map=new TreeMap<String,Object>();for(var f:contract.fields()) {
                Object v=switch(f.type()){case "TIMESTAMP" -> rs.getTimestamp(f.name())==null?null:rs.getTimestamp(f.name()).toInstant().atZone(ZoneOffset.UTC).toLocalDate();case "DOUBLE" -> rs.getObject(f.name(),Double.class);case "INT" -> rs.getObject(f.name(),Integer.class);case "LONG" -> rs.getObject(f.name(),Long.class);default -> rs.getString(f.name());};
                if(v instanceof Double d){if(Double.isNaN(d))v=null;else if(d==0d)v=0d;}map.put(f.name(),v);
            }return new Row(map);
        },params.toArray());
    }
}
