package com.zoutrankil.data.repository;

import com.zoutrankil.data.domain.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import javax.sql.DataSource;
import java.util.*;
import java.util.function.BiConsumer;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class BacktestDailyMaterializedViewPortTest {
    @Test void canonicalQueryExtractionAcceptsWindowsAndUnixLineEndings(){
        String unix=BacktestDailyMaterializedViewPort.CREATE_SQL.replace("\r\n","\n").replace('\r','\n');
        String query=BacktestDailyMaterializedViewPort.QUERY_SQL;
        assertEquals(query,BacktestDailyMaterializedViewPort.querySql(unix));
        assertEquals(query,BacktestDailyMaterializedViewPort.querySql(unix.replace("\n","\r\n")));
        assertEquals(query,BacktestDailyMaterializedViewPort.querySql(unix.replace('\n','\r')));
        assertTrue(query.stripLeading().startsWith("SELECT trade_date"));
        assertTrue(query.stripTrailing().endsWith("SAMPLE BY 1d"));
        assertThrows(IllegalStateException.class,()->BacktestDailyMaterializedViewPort.querySql("SELECT 1"));
    }
    private final Map<String,Object> view=new HashMap<>(Map.of(
            "base_table_name","stk_limit","refresh_type","immediate","view_status","valid",
            "view_sql",BacktestDailyMaterializedViewPort.QUERY_SQL,"view_table_dir_name","mv_backtest_daily~90",
            "refresh_base_table_txn",10L,"base_table_txn",10L,"started",1000L,"finished",2000L));
    private final List<Map<String,Object>> clocks=new ArrayList<>();
    private final Map<String,List<Map<String,Object>>> commits=new HashMap<>();
    private void fixture(BiConsumer<BacktestDailyMaterializedViewPort,JdbcTemplate> check){
        for(String name:List.of("mv_backtest_daily","stk_factor","stk_limit","stk_suspend","stk_st_daily")){
            var row=new HashMap<String,Object>();row.put("table_name",name);row.put("id",clocks.size()+1L);row.put("directoryName",name+"~1");
            row.put("table_type",name.equals("mv_backtest_daily")?"M":"T");row.put("walEnabled",true);row.put("table_suspended",false);row.put("dedup",!name.equals("mv_backtest_daily"));
            row.put("writerTxn",10L);row.put("sequencerTxn",10L);row.put("bufferedTxnSize",0L);row.put("suspended",false);clocks.add(row);
            commits.put(name,List.of(new HashMap<>(Map.of("sequencerTxn",10L,"committed",900L))));
        }
        var columns=new ArrayList<Map<String,Object>>();
        for(var column:BacktestDailyViewDataset.DEFINITION.columns())columns.add(new HashMap<>(Map.of(
                "column",column.storageName(),"type",column.storageType().name(),"designated",columns.isEmpty(),"upsertKey",false)));
        var source=mock(JdbcTemplate.class);when(source.getDataSource()).thenReturn(mock(DataSource.class));
        try(var construction=mockConstruction(JdbcTemplate.class,(jdbc,context)->{
            when(jdbc.queryForList(contains("FROM materialized_views()"),eq("mv_backtest_daily"))).thenReturn(List.of(view));
            when(jdbc.queryForList(contains("FROM tables()"))).thenAnswer(ignored->clocks);
            when(jdbc.queryForList(contains("FROM table_columns("))).thenReturn(columns);
            for(String name:BacktestDailyMaterializedViewPort.SOURCES){
                when(jdbc.queryForList(contains("wal_transactions('"+name+"')"))).thenAnswer(ignored->commits.get(name));
                String date=name.equals("stk_suspend")||name.equals("stk_st_daily")?"timestamp":"trade_date";
                when(jdbc.queryForList(contains("FROM table_columns('"+name+"')"))).thenReturn(List.of(Map.of("column",date),Map.of("column","ts_code")));
            }
        })){
            var port=new BacktestDailyMaterializedViewPort(source);check.accept(port,construction.constructed().getFirst());
        }
    }
    @Test void readsOnlyTheAutomaticNativeMvAndItsOriginalSources(){
        fixture((port,jdbc)->{
            assertEquals(5,port.sourceState().tables().size());assertTrue(port.sourceVersion().startsWith("questdb-mv:mv_backtest_daily:"));
            verify(jdbc,never()).execute(anyString());
            assertFalse(BacktestDailyMaterializedViewPort.CREATE_SQL.contains("FROM backtest_daily"));
            assertFalse(BacktestDailyMaterializedViewPort.CREATE_SQL.contains("UNION ALL"));
        });
    }
    @Test void manualRefreshOrDifferentRawSqlCannotPassReadAdmission(){
        fixture((port,jdbc)->{
            view.put("refresh_type","manual");assertThrows(IllegalStateException.class,port::sourceState);
            view.put("refresh_type","immediate");view.put("view_sql","SELECT * FROM backtest_daily");assertThrows(IllegalStateException.class,port::sourceState);
        });
    }
    @Test void aNormalViewOrAnUnsettledWalCannotMasqueradeAsTheNativeMv(){
        fixture((port,jdbc)->{
            clocks.getFirst().put("table_type","V");assertThrows(IllegalStateException.class,port::sourceState);
            clocks.getFirst().put("table_type","M");clocks.getFirst().put("sequencerTxn",11L);
            var pending=assertThrows(BacktestDailyMaterializationReadGuard.NotReadyException.class,port::sourceState);
            assertEquals(BacktestDailyMaterializationReadGuard.NotReadyException.Reason.REFRESH_PENDING,pending.reason());
        });
    }
    @Test void baseLagAndAnInitialRefreshRemainNotReady(){
        fixture((port,jdbc)->{
            view.put("refresh_base_table_txn",9L);assertThrows(BacktestDailyMaterializationReadGuard.NotReadyException.class,port::sourceState);
            view.put("refresh_base_table_txn",10L);view.put("view_status","refreshing");
            assertThrows(BacktestDailyMaterializationReadGuard.NotReadyException.class,port::sourceState);
        });
    }
    @Test void aLateSideTableCommitCannotBeClaimedFreshByTheBaseWatermark(){
        fixture((port,jdbc)->{
            commits.get("stk_factor").getFirst().put("committed",1001L);
            var pending=assertThrows(BacktestDailyMaterializationReadGuard.NotReadyException.class,port::sourceState);
            assertEquals(BacktestDailyMaterializationReadGuard.NotReadyException.Reason.SOURCES_CHANGED,pending.reason());
            verify(jdbc,never()).execute(anyString());
        });
    }
    @Test void anInvalidMvCannotBeRecoveredByAnApplicationRefresh(){
        fixture((port,jdbc)->{
            view.put("view_status","invalid");view.put("invalidation_reason","base renamed");
            assertThrows(IllegalStateException.class,port::sourceState);verify(jdbc,never()).execute(anyString());
        });
    }
    @Test void duplicateSourceKeysCannotBeHiddenByLastAggregates(){
        fixture((port,jdbc)->{
            when(jdbc.queryForList(contains("FROM table_columns('stk_suspend')"))).thenReturn(List.of(Map.of("column","timestamp")));
            assertThrows(IllegalStateException.class,port::sourceState);verify(jdbc,never()).execute(anyString());
        });
    }
}
