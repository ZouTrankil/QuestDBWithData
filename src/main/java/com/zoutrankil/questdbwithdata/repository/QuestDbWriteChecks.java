package com.zoutrankil.questdbwithdata.repository;

import com.zoutrankil.questdbwithdata.domain.DatasetDefinition;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.*;

/** Shared physical contract checks for admitted typed writers. No DDL or repair. */
public final class QuestDbWriteChecks {
    private QuestDbWriteChecks() {}
    public static void preflight(JdbcTemplate jdbc,String table,DatasetDefinition definition) {
        DatasetDefinition.identifier(table);
        var columns=jdbc.queryForList("SELECT \"column\",\"type\",\"upsertKey\" FROM table_columns('"+table+"')");
        var types=new HashMap<String,String>();var keys=new HashSet<String>();
        for(var column:columns) {
            types.put(column.get("column").toString(),column.get("type").toString());
            if(Boolean.TRUE.equals(column.get("upsertKey"))) keys.add(column.get("column").toString());
        }
        var expected=new HashMap<String,String>();
        definition.columns().forEach(c->expected.put(c.storageName(),c.storageType().name()));
        if(!types.equals(expected) || !keys.equals(new HashSet<>(definition.dedupKey())))
            throw new IllegalStateException("Target columns or dedup keys differ from declared dataset");
        var metadata=jdbc.queryForList("SELECT designatedTimestamp,partitionBy FROM tables() WHERE table_name=?",table);
        if(metadata.size()!=1 || !Objects.equals(definition.designatedTimestamp(),metadata.getFirst().get("designatedTimestamp"))
                || !definition.partition().name().equals(metadata.getFirst().get("partitionBy")))
            throw new IllegalStateException("Target timestamp or partition differs from declared dataset");
        if(!definition.wal() || !walSettled(jdbc,table)) throw new IllegalStateException("Target WAL is not ready");
    }
    public static boolean walSettled(JdbcTemplate jdbc,String table) {
        DatasetDefinition.identifier(table);
        var tables=jdbc.queryForList("SELECT walEnabled,table_suspended,wal_pending_row_count,table_txn,wal_txn FROM tables() WHERE table_name=?",table);
        var logs=jdbc.queryForList("SELECT suspended,writerTxn,sequencerTxn,bufferedTxnSize FROM wal_tables() WHERE name=?",table);
        if(tables.size()!=1 || logs.size()!=1) return false;
        var t=tables.getFirst();var w=logs.getFirst();
        boolean counters=t.get("table_txn")==null && t.get("wal_txn")==null || same(t.get("table_txn"),t.get("wal_txn"));
        return Boolean.TRUE.equals(t.get("walEnabled")) && Boolean.FALSE.equals(t.get("table_suspended"))
                && zero(t.get("wal_pending_row_count")) && counters && Boolean.FALSE.equals(w.get("suspended"))
                && zero(w.get("bufferedTxnSize")) && same(w.get("writerTxn"),w.get("sequencerTxn"));
    }
    private static boolean zero(Object n) { return n instanceof Number v && v.longValue()==0; }
    private static boolean same(Object a,Object b) { return a instanceof Number x && b instanceof Number y && x.longValue()==y.longValue(); }
}
