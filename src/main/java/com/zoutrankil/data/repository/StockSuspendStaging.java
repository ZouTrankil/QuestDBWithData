package com.zoutrankil.data.repository;

import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.domain.temporal.TemporalValues;
import org.springframework.jdbc.core.*;
import java.nio.file.*;
import java.sql.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/** Builds and fully reads back an authoritative D011 date-window replacement stage. */
public final class StockSuspendStaging {
    public record Prepared(StockSuspendStorage.Snapshot before, StockSuspendStorage.Snapshot current,
                           LocalDate fromInclusive, LocalDate toInclusive, List<StockSuspend> source,
                           List<StockSuspend> expected) {
        public Prepared { source=List.copyOf(source); expected=List.copyOf(expected); }
        public boolean requiresWrite() { return !current.rows().equals(expected); }
    }
    public record Verified(String table, StockSuspendStorage.Snapshot snapshot, int batches, String receipt) {}
    private final JdbcTemplate jdbc;
    private final String target;

    public StockSuspendStaging(JdbcTemplate source,String target) {
        DatasetDefinition.identifier(target);this.target=target;
        jdbc=new JdbcTemplate(Objects.requireNonNull(source).getDataSource());jdbc.setQueryTimeout(20);
    }

    public static Prepared prepare(StockSuspendStorage.Snapshot before,StockSuspendStorage.Snapshot current,
                                   List<StockSuspend> source,LocalDate from,LocalDate to) throws Exception {
        Objects.requireNonNull(before);Objects.requireNonNull(current);Objects.requireNonNull(from);Objects.requireNonNull(to);
        if(from.isAfter(to) || !before.equals(current))
            throw new IllegalArgumentException("Ordered date window and unchanged physical stk_suspend target required");
        LocalDate toExclusive=to.plusDays(1);
        var initialOutside=StockSuspendStorage.outside(before.rows(),from,toExclusive);
        var currentOutside=StockSuspendStorage.outside(current.rows(),from,toExclusive);
        if(!initialOutside.equals(currentOutside))
            throw new IllegalStateException("stk_suspend rows outside replacement window changed during source fetch");
        var incoming=StockSuspendStorage.sortedUnique(source);
        for(var row:incoming) if(row.tradeDate().isBefore(from) || row.tradeDate().isAfter(to))
            throw new IllegalArgumentException("stk_suspend replacement source escaped frozen date window");
        var expected=new ArrayList<StockSuspend>(initialOutside.size()+incoming.size());
        expected.addAll(initialOutside);expected.addAll(incoming);
        var completeExpected=StockSuspendStorage.sortedUnique(expected);
        if(completeExpected.size()>StockSuspendStorage.MAX_ROWS
                ||StockSuspendStorage.canonical(completeExpected).length>StockSuspendStorage.MAX_BYTES)
            throw new IllegalStateException("stk_suspend replacement exceeds bounded snapshot budget");
        return new Prepared(before,current,from,to,incoming,completeExpected);
    }

    public Verified write(Prepared prepared,Path evidenceFolder,BooleanSupplier cancelled) throws Exception {
        check(cancelled);
        var replay=prepare(prepared.before(),prepared.current(),prepared.source(),prepared.fromInclusive(),prepared.toInclusive());
        if(!prepared.equals(replay))throw new IllegalArgumentException("stk_suspend preparation changed before staging");
        String stage="java_stk_suspend_stage_"+UUID.randomUUID().toString().replace("-","");
        Files.createDirectories(evidenceFolder);var json=JobDefinitionJson.mapper();
        Files.write(evidenceFolder.resolve(stage+"-intent.json"),json.writeValueAsBytes(Map.of(
                "target",target,"stage",stage,"prepared",prepared,"windowSemantics","[fromInclusive,toInclusive]",
                "sourceRows",prepared.source().size(),"maxRows",StockSuspendStorage.MAX_ROWS,
                "maxBytes",StockSuspendStorage.MAX_BYTES)),StandardOpenOption.CREATE_NEW);
        LocalDate toExclusive=prepared.toInclusive().plusDays(1);
        String fromLiteral=timestampLiteral(prepared.fromInclusive()),toLiteral=timestampLiteral(toExclusive);
        String ddl="CREATE TABLE \""+stage+"\" AS (SELECT * FROM \""+target+"\" WHERE timestamp < cast('"+fromLiteral
                +"' AS TIMESTAMP) OR timestamp >= cast('"+toLiteral+"' AS TIMESTAMP)) TIMESTAMP(timestamp) PARTITION BY DAY WAL "
                +"DEDUP UPSERT KEYS(ts_code,timestamp)";
        jdbc.execute(ddl);var storage=new StockSuspendStorage(jdbc,stage);
        awaitWal(stage,cancelled);
        storage.preflight();
        var copied=storage.snapshot();
        var expectedOutside=StockSuspendStorage.outside(prepared.before().rows(),prepared.fromInclusive(),toExclusive);
        if(!copied.rows().equals(expectedOutside))
            throw new IllegalStateException("stk_suspend CTAS stage did not preserve the exact rows outside its replacement window");
        String insert="INSERT INTO \""+stage+"\" (ts_code,is_suspended,timestamp) VALUES (?,?,cast(? AS TIMESTAMP))";
        int batches=0;
        for(int start=0;start<prepared.source().size();start+=250) {
            check(cancelled);var batch=prepared.source().subList(start,Math.min(prepared.source().size(),start+250));
            jdbc.batchUpdate(insert,new BatchPreparedStatementSetter() {
                @Override public int getBatchSize(){return batch.size();}
                @Override public void setValues(PreparedStatement statement,int index)throws SQLException {
                    var row=batch.get(index);statement.setString(1,row.tsCode());statement.setLong(2,row.isSuspended());
                    statement.setLong(3,new TemporalValues.CalendarTimestamp(row.tradeDate()).storageEpoch(TemporalValues.EpochUnit.MICROS));
                }
            });batches++;
        }
        awaitWal(stage,cancelled);check(cancelled);
        var actual=storage.snapshot();
        if(!actual.rows().equals(prepared.expected()))throw new IllegalStateException("stk_suspend replacement stage full-row readback differs");
        Path receipt=evidenceFolder.resolve(stage+"-verified.json");
        Files.write(receipt,json.writeValueAsBytes(Map.of("target",target,"stage",stage,"actual",actual,"batches",batches,
                "sourceRows",prepared.source().size(),"windowFrom",prepared.fromInclusive().toString(),"windowTo",prepared.toInclusive().toString(),
                "preservedOutsideRows",expectedOutside.size())),StandardOpenOption.CREATE_NEW);
        return new Verified(stage,actual,batches,receipt.toString());
    }

    private static String timestampLiteral(LocalDate date) {
        return DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSSSS'Z'").withZone(ZoneOffset.UTC).format(date.atStartOfDay(ZoneOffset.UTC).toInstant());
    }
    private void awaitWal(String table,BooleanSupplier cancelled)throws Exception {
        long deadline=System.nanoTime()+Duration.ofSeconds(60).toNanos();
        while(!QuestDbWriteChecks.walSettled(jdbc,table)) {
            check(cancelled);if(System.nanoTime()>deadline)throw new IllegalStateException("stk_suspend stage WAL unresolved; retain stage");
            Thread.sleep(50);
        }
    }
    private static void check(BooleanSupplier cancelled) {
        if(cancelled.getAsBoolean()||Thread.currentThread().isInterrupted())throw new CancellationException("stk_suspend staging cancelled; retain stage");
    }
}
