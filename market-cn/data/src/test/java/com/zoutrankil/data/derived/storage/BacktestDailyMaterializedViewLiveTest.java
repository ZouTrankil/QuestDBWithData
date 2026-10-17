package com.zoutrankil.data.derived.storage;

import com.zoutrankil.runtime.ProjectConfiguration;
import com.zoutrankil.data.config.QuestDbProperties;
import com.zoutrankil.data.repository.BacktestDailyMaterializedViewPort;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Explicit opt-in; writes and removes only five uniquely named test objects. */
class BacktestDailyMaterializedViewLiveTest {
    private static void await(JdbcTemplate jdbc,String sql,double expected)throws Exception {
        long end=System.nanoTime()+Duration.ofSeconds(30).toNanos();
        while(true){
            var actual=jdbc.queryForObject(sql,Double.class);
            if(actual!=null&&actual==expected)return;
            if(System.nanoTime()>=end)fail("Automatic MV result did not arrive: "+sql+" expected="+expected+" actual="+actual);
            Thread.sleep(100);
        }
    }
    private static String at(String table,String field,String day,String stock){
        return "SELECT "+field+" FROM "+table+" WHERE trade_date='"+day+"' AND ts_code='"+stock+"'";
    }
    @Test void rawWritesAloneMaintainSuspendedPricesNewStocksAndCorrections()throws Exception {
        assumeTrue("true".equalsIgnoreCase(System.getenv("BACKTEST_MV_LIVE_WRITE_TEST")));
        var config=ProjectConfiguration.load("data");var db=Binder.get(config).bind("app.questdb",QuestDbProperties.class).get();
        var jdbc=new JdbcTemplate(new DriverManagerDataSource("jdbc:postgresql://"+db.getHost()+":"+db.getPgPort()+"/"+db.getDatabase()+"?sslmode=disable",db.getUsername(),db.getPassword()));
        jdbc.setQueryTimeout(60);
        String prefix="java_backtest_mv_test_"+UUID.randomUUID().toString().replace("-","");
        var names=new LinkedHashMap<String,String>();
        for(String raw:BacktestDailyMaterializedViewPort.SOURCES)names.put(raw,prefix+"_"+raw);
        String factor=names.get("stk_factor"),limit=names.get("stk_limit"),suspend=names.get("stk_suspend"),st=names.get("stk_st_daily"),mv=prefix+"_mv";
        var created=new ArrayList<String>();boolean mvCreated=false;
        try{
            for(var entry:names.entrySet()){
                String raw=entry.getKey(),name=entry.getValue();
                String date=raw.equals("stk_factor")||raw.equals("stk_limit")?"trade_date":"timestamp";
                String values=switch(raw){
                    case "stk_factor"->"open DOUBLE,high DOUBLE,low DOUBLE,close DOUBLE,vol DOUBLE,amount DOUBLE,adj_factor DOUBLE";
                    case "stk_limit"->"up_limit DOUBLE,down_limit DOUBLE";
                    case "stk_suspend"->"is_suspended LONG";
                    default->"is_st INT";
                };
                jdbc.execute("CREATE TABLE "+name+" ("+date+" TIMESTAMP,ts_code SYMBOL,"+values+") TIMESTAMP("+date+") PARTITION BY DAY WAL DEDUP UPSERT KEYS("+date+",ts_code)");created.add(name);
            }
            jdbc.execute("INSERT INTO "+factor+" VALUES ('2026-10-14','000001.SZ',10,10,10,10,100,1000,1),('2026-10-14','000002.SZ',20,20,20,20,200,4000,2),('2026-10-15','000001.SZ',11,11,11,11,100,1100,1)");
            jdbc.execute("INSERT INTO "+suspend+" VALUES ('2026-10-15','000002.SZ',1)");
            jdbc.execute("INSERT INTO "+st+" VALUES ('2026-10-15','000002.SZ',1)");
            await(jdbc,"SELECT count() FROM "+factor,3);await(jdbc,"SELECT count() FROM "+suspend,1);await(jdbc,"SELECT count() FROM "+st,1);
            jdbc.execute("INSERT INTO "+limit+" VALUES ('2026-10-14','000001.SZ',11,9),('2026-10-14','000002.SZ',22,18),('2026-10-15','000001.SZ',12.1,9.9),('2026-10-15','000002.SZ',22,18)");
            await(jdbc,"SELECT count() FROM "+limit,4);
            String ddl=BacktestDailyMaterializedViewPort.CREATE_SQL.replaceAll("\\bmv_backtest_daily\\b",mv);
            for(var entry:names.entrySet())ddl=ddl.replaceAll("\\b"+entry.getKey()+"\\b",entry.getValue());
            jdbc.execute(ddl);mvCreated=true;await(jdbc,"SELECT count() FROM "+mv,4);
            var paused=jdbc.queryForMap("SELECT * FROM "+mv+" WHERE trade_date='2026-10-15' AND ts_code='000002.SZ'");
            assertEquals(13,paused.size());
            for(String field:List.of("open","high","low","close"))assertEquals(20.0,paused.get(field));
            assertEquals(0.0,paused.get("vol"));assertEquals(0.0,paused.get("amount"));assertEquals(2.0,paused.get("adj_factor"));
            assertEquals(22.0,paused.get("up_limit"));assertEquals(18.0,paused.get("down_limit"));
            assertEquals(1L,paused.get("is_suspended"));assertEquals(1,paused.get("is_st"));

            // A new symbol joins the universe on the next raw-source batch, while the suspended stock has no price row.
            jdbc.execute("INSERT INTO "+factor+" VALUES ('2026-10-16','000001.SZ',12,12,12,12,100,1200,1),('2026-10-16','000003.SZ',30,30,30,30,300,9000,3)");
            jdbc.execute("INSERT INTO "+suspend+" VALUES ('2026-10-16','000002.SZ',1)");
            jdbc.execute("INSERT INTO "+st+" VALUES ('2026-10-16','000002.SZ',1)");
            await(jdbc,"SELECT count() FROM "+factor,5);await(jdbc,"SELECT count() FROM "+suspend,2);await(jdbc,"SELECT count() FROM "+st,2);
            jdbc.execute("INSERT INTO "+limit+" VALUES ('2026-10-16','000001.SZ',13.2,10.8),('2026-10-16','000002.SZ',22,18),('2026-10-16','000003.SZ',33,27)");
            await(jdbc,"SELECT count() FROM "+mv,7);
            await(jdbc,at(mv,"close","2026-10-16","000002.SZ"),20);await(jdbc,at(mv,"close","2026-10-16","000003.SZ"),30);
            jdbc.execute("INSERT INTO "+limit+" VALUES ('2026-10-15','000002.SZ',25,15)");
            await(jdbc,at(mv,"up_limit","2026-10-15","000002.SZ"),25);

            // The single-base limitation is explicit. Re-ingest the raw base after a side correction; never issue REFRESH.
            jdbc.execute("INSERT INTO "+factor+" VALUES ('2026-10-16','000001.SZ',12.5,12.5,12.5,12.5,100,1250,1)");
            await(jdbc,at(factor,"close","2026-10-16","000001.SZ"),12.5);
            assertEquals(12.0,jdbc.queryForObject(at(mv,"close","2026-10-16","000001.SZ"),Double.class));
            jdbc.execute("INSERT INTO "+limit+" VALUES ('2026-10-16','000001.SZ',13.2,10.8),('2026-10-16','000002.SZ',22,18),('2026-10-16','000003.SZ',33,27)");
            await(jdbc,at(mv,"close","2026-10-16","000001.SZ"),12.5);
        }finally{
            if(mvCreated)jdbc.execute("DROP MATERIALIZED VIEW "+mv);
            Collections.reverse(created);for(String name:created)jdbc.execute("DROP TABLE "+name);
        }
    }
}
