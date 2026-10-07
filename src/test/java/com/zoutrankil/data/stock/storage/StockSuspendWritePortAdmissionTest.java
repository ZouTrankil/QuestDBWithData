package com.zoutrankil.data.stock.storage;

import io.questdb.client.QuestDB;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import javax.sql.DataSource;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class StockSuspendWritePortAdmissionTest {
    private static final String STAGE = "java_stk_suspend_stage_0123456789abcdef0123456789abcdef";
    private static final String TARGET_ID = "static-v2-" + "a".repeat(64);

    @Test void externalTargetsAndOwnedStageBindingHaveSeparateAdmissionWithoutDatabaseIo() {
        var jdbc = mock(JdbcTemplate.class);
        var source = mock(DataSource.class);
        var questdb = mock(QuestDB.class);
        when(jdbc.getDataSource()).thenReturn(source);
        for (String table : List.of("stk_suspend", "java_d011_stk_suspend_fixture", "stk_suspend_d011_fixture")) {
            var writer = new StockSuspendWritePort(table,jdbc,questdb,TARGET_ID);
            var first = writer.forTarget(STAGE,TARGET_ID);
            var second = writer.forTarget(STAGE,TARGET_ID);
            assertNotSame(writer,first);
            assertNotSame(first,second);
            assertThrows(IllegalArgumentException.class,()->writer.forTarget("stk_suspend",TARGET_ID));
            assertThrows(IllegalArgumentException.class,()->writer.forTarget("java_stk_suspend_stage_bad",TARGET_ID));
            assertThrows(NullPointerException.class,()->writer.forTarget(STAGE,null));
        }
        assertThrows(IllegalArgumentException.class,()->new StockSuspendWritePort(STAGE,jdbc,questdb,TARGET_ID));
        assertThrows(IllegalArgumentException.class,()->new StockSuspendWritePort("other_stk_suspend",jdbc,questdb,TARGET_ID));
        verifyNoInteractions(source,questdb);
    }
}
