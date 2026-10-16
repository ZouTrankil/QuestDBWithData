package com.zoutrankil.data.repository;

import com.zoutrankil.data.domain.BacktestDailyViewDataset;
import java.time.LocalDate;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import javax.sql.DataSource;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static com.zoutrankil.data.repository.BacktestDailyMaterializationPort.*;

class BacktestDailyReadViewTest {
    private final Map<String,Object> metadata = new HashMap<>(Map.of(
            "view_sql", VIEW_SQL, "view_status", "valid", "id", 20L,
            "directoryName", "v_backtest_daily~20", "table_type", "V", "matView", false));
    private List<Map<String,Object>> columns() {
        var result = new ArrayList<Map<String,Object>>();
        var definition = BacktestDailyViewDataset.DEFINITION;
        for (int i=0;i<definition.columns().size();i++) {
            var c=definition.columns().get(i);
            result.add(new HashMap<>(Map.of("column",c.storageName(),"type",c.storageType().name(),
                    "designated",i==0,"upsertKey",false)));
        }
        return result;
    }
    private void withPort(java.util.function.BiConsumer<BacktestDailyMaterializationPort,JdbcTemplate> check) {
        var source=mock(JdbcTemplate.class);
        when(source.getDataSource()).thenReturn(mock(DataSource.class));
        try (var construction=mockConstruction(JdbcTemplate.class)) {
            var port=new BacktestDailyMaterializationPort(source);
            var jdbc=construction.constructed().getFirst();
            when(jdbc.queryForList(startsWith("SELECT v."),eq(ALIAS))).thenReturn(List.of(metadata));
            when(jdbc.queryForList(contains("FROM table_columns("))).thenReturn(columns());
            when(jdbc.queryForList("SELECT table_type FROM tables() WHERE table_name=?",ALIAS))
                    .thenReturn(List.of(Map.of("table_type","V")));
            check.accept(port,jdbc);
        }
    }
    @Test void existingReadAliasUsesCanonicalTypesWithoutAnyRefreshOrDdl() {
        withPort((port,jdbc)->{
            port.installAndVerifyView();
            assertEquals(new ViewState(ALIAS,20,"v_backtest_daily~20",VIEW_SQL),port.viewState());
            verify(jdbc,never()).execute(anyString());
        });
    }
    @Test void absentAliasCreatesOnlyAViewAndPreservesLongSuspensionFlag() {
        withPort((port,jdbc)->{
            when(jdbc.queryForList("SELECT table_type FROM tables() WHERE table_name=?",ALIAS)).thenReturn(List.of());
            port.installAndVerifyView();
            verify(jdbc).execute("CREATE VIEW \"v_backtest_daily\" AS ("+VIEW_SQL+")");
            verify(jdbc,times(1)).execute(anyString());
        });
    }
    @Test void oldPhysicalMaterializationIsNotSilentlyRecreatedOrDropped() {
        withPort((port,jdbc)->{
            when(jdbc.queryForList("SELECT table_type FROM tables() WHERE table_name=?",ALIAS))
                    .thenReturn(List.of(Map.of("table_type","M")));
            assertThrows(IllegalStateException.class,port::installAndVerifyView);
            verify(jdbc,never()).execute(anyString());
        });
    }
    @Test void differentSqlOwnedWalOrNarrowedPublicFlagFailsClosed() {
        withPort((port,jdbc)->{
            metadata.put("view_sql","SELECT * FROM stk_factor");
            assertThrows(IllegalStateException.class,port::viewState);
            metadata.put("view_sql",VIEW_SQL);metadata.put("own_wal",ALIAS);
            assertThrows(IllegalStateException.class,port::viewState);
            metadata.remove("own_wal");var narrowed=columns();narrowed.get(11).put("type","INT");
            when(jdbc.queryForList(contains("FROM table_columns("))).thenReturn(narrowed);
            assertThrows(IllegalStateException.class,port::viewState);
        });
    }
    @Test void sourceSnapshotDetectsAChangedResultTableEvenWhenAliasIsUnchanged() {
        withPort((port,jdbc)->{
            var reader=spy(port);var day=LocalDate.of(2026,10,16);
            var first=new Identity(7,"backtest_daily~7",1,day,day,3,3,3);
            var changed=new Identity(7,"backtest_daily~7",1,day,day,4,4,4);
            doReturn(first,changed).when(reader).identity(BASE);
            assertThrows(IllegalStateException.class,reader::sourceState);
        });
    }
}
