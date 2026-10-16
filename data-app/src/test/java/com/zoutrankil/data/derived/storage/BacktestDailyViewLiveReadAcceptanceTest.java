package com.zoutrankil.data.derived.storage;

import com.zoutrankil.data.bootstrap.ProjectConfiguration;
import com.zoutrankil.data.config.QuestDbProperties;
import com.zoutrankil.data.repository.BacktestDailyMaterializedViewPort;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Opt-in, read-only verification of the database after the native MV migration. */
class BacktestDailyViewLiveReadAcceptanceTest {
    @Test void theNativeMvIsCurrentAndTheOldResultTableAndAliasAreAbsent(){
        assumeTrue("true".equalsIgnoreCase(System.getenv("BACKTEST_VIEW_LIVE_READ")));
        var config=ProjectConfiguration.load("data");var db=Binder.get(config).bind("app.questdb",QuestDbProperties.class).get();
        var ds=new DriverManagerDataSource("jdbc:postgresql://"+db.getHost()+":"+db.getPgPort()+"/"+db.getDatabase()+"?sslmode=disable",db.getUsername(),db.getPassword());
        var jdbc=new JdbcTemplate(ds);jdbc.setQueryTimeout(120);
        var port=new BacktestDailyMaterializedViewPort(jdbc);var before=port.sourceState();
        assertEquals(0L,jdbc.queryForObject("SELECT count() FROM tables() WHERE table_name IN ('backtest_daily','v_backtest_daily')",Long.class));
        var latest=jdbc.queryForMap("SELECT count() AS n,count_distinct(ts_code) AS codes FROM mv_backtest_daily WHERE trade_date=(SELECT max(trade_date) FROM mv_backtest_daily)");
        assertTrue(((Number)latest.get("n")).longValue()>0);assertEquals(latest.get("n"),latest.get("codes"));
        assertEquals(before,port.sourceState());
    }
}
