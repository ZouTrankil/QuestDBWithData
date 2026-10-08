package com.zoutrankil.data.index.storage;
import com.zoutrankil.data.index.port.*;
import com.zoutrankil.data.domain.DatasetDefinition;
import com.zoutrankil.data.index.domain.*;
import com.zoutrankil.data.repository.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ConnectionCallback;
import java.util.*;
import java.time.*;
import java.util.function.BooleanSupplier;
public final class QuestDbIndexMonthlyTables implements IndexMonthlyStagingPort {
private final JdbcTemplate jdbc;
public QuestDbIndexMonthlyTables(JdbcTemplate source){jdbc=new JdbcTemplate(Objects.requireNonNull(source.getDataSource()));jdbc.setQueryTimeout(120);}
public IndexMonthlyTables.Table open(String table){return new IndexMonthlyStorage(jdbc,table);}
public String logicalTargetId(String table){com.zoutrankil.data.domain.DatasetDefinition.identifier(table);return StaticTargetIdentity.identify(jdbc,table,0L,"d022-logical-target-v1");}
public String physicalTargetId(String table,IndexMonthlyState.Identity identity){return IndexMonthlyStorage.physicalTargetId(jdbc,table,identity);}
public IndexMonthlyState.Snapshot snapshotIfPresent(String table) throws Exception {
        if (jdbc.queryForList("SELECT id FROM tables() WHERE table_name=?", table).isEmpty()) return null;
        long deadline = System.nanoTime() + Duration.ofMinutes(2).toNanos();
        while (!QuestDbWriteChecks.walSettled(jdbc, table)) {
            if (System.nanoTime() > deadline) throw new IllegalStateException("D022 renamed WAL unresolved; retain publication intent");
            Thread.sleep(50);
        }
        return new IndexMonthlyStorage(jdbc, table).snapshot();
    }
public void rename(String from, String to) { DatasetDefinition.identifier(from); DatasetDefinition.identifier(to); jdbc.execute("RENAME TABLE \""+from+"\" TO \""+to+"\""); }
public int tableCount(String table){return jdbc.queryForList("SELECT id FROM tables() WHERE table_name=?",table).size();}
public void createOutsideStage(String stage,String target,String code,String lower,String end){String sql = "CREATE TABLE \"" + stage + "\" AS (SELECT ts_code,trade_date,close,open,high,low,pre_close,change,pct_chg,vol,amount,layer,bucket,update_time FROM \""
                + target + "\" WHERE NOT (ts_code='" + quoteLiteral(code) + "' AND trade_date>=cast('" + lower
                + "' AS TIMESTAMP) AND trade_date<cast('" + end + "' AS TIMESTAMP))) TIMESTAMP(trade_date) PARTITION BY YEAR WAL";
        jdbc.execute(sql);
}
public void awaitWal(String table, BooleanSupplier cancelled) throws Exception {
        long deadline = System.nanoTime() + Duration.ofMinutes(2).toNanos();
        while (!QuestDbWriteChecks.walSettled(jdbc, table)) {
            check(cancelled);
            if (System.nanoTime() > deadline) throw new IllegalStateException("D022 stage WAL unresolved; retain stage");
            Thread.sleep(50);
        }
    }
private static String quoteLiteral(String value) { return value.replace("'", "''"); }
private static void check(BooleanSupplier cancelled) {
        if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted())
            throw new java.util.concurrent.CancellationException("D022 stage cancelled; retain stage");
    }
}
