package com.zoutrankil.data.derived.application;

import com.zoutrankil.data.derived.storage.QuestDbMarketBreadthDailyV1Target;

import com.zoutrankil.data.derived.storage.MarketBreadthDailyV1MaterializationPort;

import com.zoutrankil.data.derived.storage.MarketBreadthDailyV1ReadRepository;

import com.zoutrankil.data.service.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import com.zoutrankil.data.config.QuestDbProperties;
import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.repository.*;
import java.nio.file.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Complete raw real-source slices on a proven private instance, then canonical Java materialization. */
class MarketBreadthDailyV1LiveMaterializeAcceptanceTest {
    @Test void realSourceFirstRunReplayIncrementalEmptyAndInvalidRecovery() throws Exception {
        assumeTrue("true".equalsIgnoreCase(System.getenv("D095_LIVE_MATERIALIZE")));
        var properties = new QuestDbProperties(); properties.setHost("127.0.0.1");
        properties.setPgPort(18812); properties.setQwpPort(19000); properties.setDatabase("qdb");
        var jdbc = jdbc("127.0.0.1",18812,"admin","quest");
        var formal = jdbc(required("APP_QUESTDB_HOST"),Integer.parseInt(System.getenv().getOrDefault("APP_QUESTDB_PGPORT","8812")),
                required("APP_QUESTDB_USERNAME"),required("APP_QUESTDB_PASSWORD"));
        var calendarVersion = formal.queryForObject("SELECT cast(t.id AS STRING) || ':' || cast(w.sequencerTxn AS STRING) FROM tables() t JOIN wal_tables() w ON t.table_name=w.name WHERE t.table_name='exchange_calendar'",String.class);
        var calendar = calendar(formal,"SELECT exchange,cal_date,is_open,pretrade_date FROM exchange_calendar WHERE exchange='SSE' AND cal_date >= '2026-09-17' AND cal_date < '2026-10-07' ORDER BY cal_date LIMIT 21");
        assertEquals(20,calendar.size());
        assertEquals(calendarVersion,formal.queryForObject("SELECT cast(t.id AS STRING) || ':' || cast(w.sequencerTxn AS STRING) FROM tables() t JOIN wal_tables() w ON t.table_name=w.name WHERE t.table_name='exchange_calendar'",String.class));
        new MarketBreadthDailyV1MaterializationPort(jdbc,properties,true).verifyPrivateInstance();
        jdbc.execute("CREATE TABLE IF NOT EXISTS exchange_calendar (exchange SYMBOL,cal_date TIMESTAMP,is_open INT,pretrade_date STRING) TIMESTAMP(cal_date) PARTITION BY YEAR WAL DEDUP UPSERT KEYS(exchange,cal_date)");
        jdbc.execute("TRUNCATE TABLE exchange_calendar");
        jdbc.batchUpdate("INSERT INTO exchange_calendar (exchange,cal_date,is_open,pretrade_date) VALUES (?,?,?,?)",calendar,100,
                (statement,row) -> { statement.setString(1,row.get("exchange").toString()); statement.setTimestamp(2,(Timestamp)row.get("cal_date"),Calendar.getInstance(TimeZone.getTimeZone("UTC")));
                    statement.setInt(3,((Number)row.get("is_open")).intValue()); statement.setString(4,(String)row.get("pretrade_date")); });
        await(() -> jdbc.queryForObject("SELECT count() FROM exchange_calendar",Long.class) == 20,Duration.ofSeconds(30));
        var calendarRead = calendar(jdbc,"SELECT exchange,cal_date,is_open,pretrade_date FROM exchange_calendar ORDER BY cal_date");
        assertEquals(calendar,calendarRead);
        Path ledgerPath = Path.of("var/d095-java-" + UUID.randomUUID() + ".sqlite3");
        var owner = new MarketBreadthDailyV1JobService(new QuestDbMarketBreadthDailyV1Target(jdbc, properties, true, ""), ledgerPath);
        var start = LocalDate.of(2026,9,17); var end = LocalDate.of(2026,9,18);
        var logical = LocalDate.of(2026,10,6);
        var firstPlan = owner.plan(start,end,logical,null);
        assertEquals(SyncJobDefinition.Mode.INCREMENTAL,firstPlan.request().mode());
        assertFalse(firstPlan.request().parameters().containsKey("checkpoint_before"));
        var first = owner.run(firstPlan);
        assertEquals(SyncRunState.VERIFIED,first.result().state(),first.toString());
        assertEquals(11105,first.sourceRawRows()); assertEquals(2,first.result().verifiedRows());
        assertEquals(2,owner.status(first.result().runId()).verifiedRows());
        assertEquals(0,owner.status(first.result().runId()).unresolvedSlices());
        var replayPlan = owner.plan(start,end,logical,null);
        assertEquals(end,replayPlan.request().parameters().get("checkpoint_before"));
        var replay = owner.run(replayPlan);
        assertEquals(SyncRunState.VERIFIED,replay.result().state(),replay.toString());
        var repository = new MarketBreadthDailyV1ReadRepository(new QuestDbBoundedReader(jdbc));
        var initialRows = repository.findRange(start,end.plusDays(1),31,null).rows();
        assertEquals(2,initialRows.size()); assertEquals(2,new HashSet<>(initialRows.stream().map(MarketBreadthDailyV1::tradeDate).toList()).size());

        // A third complete real day is copied from formal SELECTs, without formal writes.
        var incrementDate = LocalDate.of(2026,9,21);
        var sourceBefore = sourceVersion(formal);
        var increment = raw(formal,incrementDate,incrementDate.plusDays(1));
        assertTrue(increment.size() > 5000 && increment.size() <= 10000);
        assertEquals(sourceBefore,sourceVersion(formal),"Formal source changed during real extraction");
        jdbc.batchUpdate("INSERT INTO stk_factor (trade_date,ts_code,pct_change,amount) VALUES (?,?,?,?)",increment,500,
                (statement,row) -> {
                    statement.setTimestamp(1,row.timestamp(),Calendar.getInstance(TimeZone.getTimeZone("UTC")));
                    statement.setString(2,row.code());
                    if(row.pct() == null) statement.setNull(3,Types.DOUBLE); else statement.setDouble(3,row.pct());
                    if(row.amount() == null) statement.setNull(4,Types.DOUBLE); else statement.setDouble(4,row.amount());
                });
        await(() -> raw(jdbc,incrementDate,incrementDate.plusDays(1)).size() == increment.size(),Duration.ofSeconds(30));
        var returned = raw(jdbc,incrementDate,incrementDate.plusDays(1));
        assertEquals(new HashSet<>(increment),new HashSet<>(returned)); assertEquals(increment.size(),returned.size());
        await(() -> {
            var state = new MarketBreadthDailyV1MaterializationPort(jdbc,properties,true).snapshot();
            return state.valid() && state.sourceSettled() && state.mvSettled();
        },Duration.ofSeconds(90));
        var incrementalPlan = owner.plan(start,incrementDate,logical,null);
        assertEquals(end,incrementalPlan.request().parameters().get("checkpoint_before"));
        var incremental = owner.run(incrementalPlan);
        assertEquals(SyncRunState.VERIFIED,incremental.result().state(),incremental.toString());
        assertEquals(11105L + increment.size(),incremental.sourceRawRows());
        assertEquals(3,incremental.result().verifiedRows());
        var port = new MarketBreadthDailyV1MaterializationPort(jdbc,properties,true);
        assertRows(port.expected(start,incrementDate),port.actual(start,incrementDate));
        assertEquals(sourceBefore,sourceVersion(formal),"Acceptance must not mutate the formal source");
        var futurePlan = owner.plan(start,LocalDate.of(2026,10,6),logical,null);
        var missingDays = owner.run(futurePlan);
        assertEquals(SyncRunState.FAILED,missingDays.result().state(),"Missing open-session source buckets cannot advance checkpoint");
        var checkpointPlan = owner.plan(start,incrementDate,logical,null);
        assertEquals(incrementDate,checkpointPlan.request().parameters().get("checkpoint_before"));
        assertEquals(incrementDate.minusDays(2),checkpointPlan.request().from());

        var emptyDate = LocalDate.of(2026,9,20);
        var empty = owner.run(owner.plan(emptyDate,emptyDate,logical,SyncJobDefinition.Mode.MATERIALIZE));
        assertEquals(SyncRunState.VERIFIED_EMPTY,empty.result().state());
        assertEquals(0,empty.sourceRawRows()); assertEquals(0,empty.result().verifiedRows());

        var original = jdbc.queryForObject("SELECT amount FROM stk_factor WHERE trade_date='2026-09-17' AND ts_code='000001.SZ'",Double.class);
        jdbc.update("UPDATE stk_factor SET amount=? WHERE trade_date='2026-09-17' AND ts_code='000001.SZ'",original + 1);
        await(() -> !new MarketBreadthDailyV1MaterializationPort(jdbc,properties,true).snapshot().valid(),Duration.ofSeconds(30));
        assertThrows(IllegalStateException.class,() -> repository.findForDate(start));
        var invalid = owner.run(owner.plan(start,end,logical,SyncJobDefinition.Mode.RECONCILE));
        assertEquals(SyncRunState.FAILED,invalid.result().state());
        assertEquals(0,invalid.result().verifiedRows());
        jdbc.update("UPDATE stk_factor SET amount=? WHERE trade_date='2026-09-17' AND ts_code='000001.SZ'",original);
        await(() -> Objects.equals(original,jdbc.queryForObject("SELECT amount FROM stk_factor WHERE trade_date='2026-09-17' AND ts_code='000001.SZ'",Double.class)),Duration.ofSeconds(30));
        await(() -> new MarketBreadthDailyV1MaterializationPort(jdbc,properties,true).snapshot().sourceSettled(),Duration.ofSeconds(30));
        var fullRepair = owner.repairIsolated();
        assertEquals(SyncRunState.VERIFIED,fullRepair.result().state(),fullRepair.toString());
        await(() -> ready(jdbc,properties),Duration.ofSeconds(90));
        var recovery = owner.run(owner.plan(start,incrementDate,logical,SyncJobDefinition.Mode.RECONCILE));
        assertEquals(SyncRunState.VERIFIED,recovery.result().state(),recovery.toString());
        assertRows(port.expected(start,incrementDate),port.actual(start,incrementDate));
        var evidence = new LinkedHashMap<String,Object>();
        evidence.put("calendarRows",calendar.size()); evidence.put("calendarFieldComparisons",calendar.size() * 4); evidence.put("missingDatesRejected",missingDays);
        evidence.put("first",first); evidence.put("replay",replay); evidence.put("incremental",incremental);
        evidence.put("newRealSourceRows",increment.size()); evidence.put("newRawFieldComparisons",increment.size() * 4);
        evidence.put("fullRepair",fullRepair); evidence.put("empty",empty); evidence.put("invalidRejected",invalid); evidence.put("recovery",recovery);
        evidence.put("checkpointBefore",end); evidence.put("checkpointAfter",incrementDate);
        evidence.put("revisionFrom",checkpointPlan.request().from()); evidence.put("ledger",ledgerPath.toString());
        evidence.put("allOutputFieldsMatched",true); evidence.put("formalMutated",false);
        Path output = Path.of("artifacts/java-migration/D095/commands/java-materialize-acceptance-20261006.json");
        Files.writeString(output,JobDefinitionJson.mapper().writerWithDefaultPrettyPrinter().writeValueAsString(evidence));
    }
    private record Raw(Timestamp timestamp,String code,Double pct,Double amount) {}
    private static List<Map<String,Object>> calendar(JdbcTemplate jdbc,String sql) {
        return jdbc.query(sql,(rs,n) -> {
            var row = new LinkedHashMap<String,Object>(); row.put("exchange",rs.getString("exchange"));
            row.put("cal_date",rs.getTimestamp("cal_date",Calendar.getInstance(TimeZone.getTimeZone("UTC"))));
            row.put("is_open",rs.getInt("is_open")); row.put("pretrade_date",rs.getString("pretrade_date")); return row;
        });
    }
    private static List<Raw> raw(JdbcTemplate jdbc,LocalDate from,LocalDate to) {
        var rows = jdbc.query("SELECT trade_date,ts_code,pct_change,amount FROM stk_factor WHERE trade_date >= '" + from
                + "' AND trade_date < '" + to + "' ORDER BY trade_date,ts_code LIMIT 10001",(rs,n) -> {
            double pct = rs.getDouble(3); Double pctValue = rs.wasNull() ? null : pct;
            double amount = rs.getDouble(4); Double amountValue = rs.wasNull() ? null : amount;
            return new Raw(rs.getTimestamp(1,Calendar.getInstance(TimeZone.getTimeZone("UTC"))),rs.getString(2),pctValue,amountValue);
        });
        if(rows.size() > 10000 || new HashSet<>(rows.stream().map(row -> row.timestamp() + ":" + row.code()).toList()).size() != rows.size())
            throw new IllegalStateException("Real raw slice exceeded budget or has duplicate keys");
        return rows;
    }
    private static String sourceVersion(JdbcTemplate jdbc) {
        return jdbc.queryForObject("SELECT cast(t.id AS STRING) || ':' || cast(w.sequencerTxn AS STRING) FROM tables() t JOIN wal_tables() w ON t.table_name=w.name WHERE t.table_name='stk_factor'",String.class);
    }
    private static void assertRows(List<MarketBreadthDailyV1> expected,List<MarketBreadthDailyV1> actual) {
        assertEquals(expected.size(),actual.size());
        for(int i=0;i<expected.size();i++) assertTrue(MarketBreadthDailyV1MaterializeAdapter.CODEC.equivalent(expected.get(i),actual.get(i)));
    }
    private static boolean ready(JdbcTemplate jdbc,QuestDbProperties properties) {
        try { var state = new MarketBreadthDailyV1MaterializationPort(jdbc,properties,true).snapshot(); return state.valid() && state.caughtUp(); }
        catch (RuntimeException transientChange) { return false; }
    }
    @FunctionalInterface private interface Check { boolean test() throws Exception; }
    private static void await(Check check,Duration timeout) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        while(System.nanoTime() < deadline) {
            try { if(check.test()) return; }
            catch (IllegalStateException transientMetadata) {
                if (!"D095 metadata changed during snapshot".equals(transientMetadata.getMessage())) throw transientMetadata;
            }
            Thread.sleep(100);
        }
        fail("Isolated asynchronous state not visible before bounded deadline");
    }
    private static JdbcTemplate jdbc(String host,int port,String user,String password) {
        var source = new DriverManagerDataSource("jdbc:postgresql://" + host + ":" + port + "/qdb?sslmode=disable",user,password);
        source.setDriverClassName("org.postgresql.Driver"); var jdbc = new JdbcTemplate(source); jdbc.setQueryTimeout(30); return jdbc;
    }
    private static String required(String key) {
        var value = System.getenv(key); if(value == null || value.isBlank()) throw new IllegalStateException("Missing setting: " + key); return value;
    }
}
