package com.zoutrankil.data.derived.storage;

import com.zoutrankil.data.bootstrap.ProjectConfiguration;
import com.zoutrankil.data.config.QuestDbProperties;
import com.zoutrankil.data.repository.BacktestDailyMaterializationPort;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Opt-in, read-only verification of the current single-table read alias on the configured server. */
class BacktestDailyViewLiveReadAcceptanceTest {
    @Test void publicAliasHasNoOwnedStorageAndMatchesEveryLatestDayField() {
        assumeTrue("true".equalsIgnoreCase(System.getenv("BACKTEST_VIEW_LIVE_READ")));
        var config=ProjectConfiguration.load("data");
        var db=Binder.get(config).bind("app.questdb",QuestDbProperties.class).get();
        var dataSource=new DriverManagerDataSource("jdbc:postgresql://"+db.getHost()+":"+db.getPgPort()+"/"+db.getDatabase()+"?sslmode=disable",db.getUsername(),db.getPassword());
        var jdbc=new JdbcTemplate(dataSource);jdbc.setQueryTimeout(60);
        var port=new BacktestDailyMaterializationPort(jdbc);var before=port.sourceState();
        assertEquals(0L,jdbc.queryForObject("SELECT count() FROM materialized_views() WHERE view_name='v_backtest_daily'",Long.class));
        assertEquals(0L,jdbc.queryForObject("SELECT count() FROM wal_tables() WHERE name='v_backtest_daily'",Long.class));
        assertTrue(before.base().rows()>0);var day=before.base().to();
        var expected=port.digest("backtest_daily",day,day,true);
        assertTrue(expected.rows()>0);
        assertEquals(expected,port.digest("v_backtest_daily",day,day,false));
        assertEquals(before,port.sourceState());
    }
}
