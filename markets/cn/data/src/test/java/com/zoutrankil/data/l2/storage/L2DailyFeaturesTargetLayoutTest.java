package com.zoutrankil.data.l2.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.zoutrankil.data.l2.port.L2DailyFeaturesTarget;
import com.zoutrankil.data.l2.port.L2DailyFeaturesWriteSession;
import com.zoutrankil.market.cn.model.CnTradeDateLayout;

import io.questdb.client.QuestDB;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

class L2DailyFeaturesTargetLayoutTest {
    private static final String TABLE = "java_d086_l2_daily_features_versioned";

    @ParameterizedTest
    @ValueSource(ints = {1, 2})
    void scopesKeepTheSameTableButUseOriginalVersionAndDistinctIdentity(int current)
            throws Exception {
        var jdbc = mock(JdbcTemplate.class);
        var ds = mock(DataSource.class);
        var qdb = mock(QuestDB.class);
        when(jdbc.getDataSource()).thenReturn(ds);
        when(jdbc.queryForList("SELECT id,directoryName FROM tables() WHERE table_name=?", TABLE))
                .thenReturn(List.of(Map.of("id", 37L, "directoryName", "partition~37")));
        var target =
                new QuestDbL2DailyFeaturesTarget(
                        TABLE,
                        jdbc,
                        qdb,
                        L2ManifestDailyTargetContractTest.properties(),
                        CnTradeDateLayout.fromVersion(current));
        var first = L2DailyFeaturesTarget.forFrozenVersion(target, 1);
        var second = L2DailyFeaturesTarget.forFrozenVersion(target, 2);
        assertNotSame(first, second);
        assertEquals(TABLE, first.tableName());
        assertEquals(TABLE, second.tableName());
        assertEquals(1, L2DailyFeaturesTarget.versionOf(first));
        assertEquals(2, L2DailyFeaturesTarget.versionOf(second));
        assertNotEquals(first.targetId(), second.targetId());
        assertEquals(
                new QuestDbL2DailyFeaturesTarget(
                                TABLE, jdbc, qdb, L2ManifestDailyTargetContractTest.properties())
                        .targetId(),
                first.targetId());
        var writer = (L2DailyFeaturesWriteSession.Versioned) second.newWriter();
        assertEquals(2, writer.datasetVersion());
        assertNotSame(writer, second.newWriter());
        verifyNoInteractions(ds, qdb);
    }

    @Test
    void newIdentityRejectsLossyOrMissingMetadataAndBadScopeVersions() throws Exception {
        var jdbc = mock(JdbcTemplate.class);
        var target =
                new QuestDbL2DailyFeaturesTarget(
                        TABLE,
                        jdbc,
                        mock(QuestDB.class),
                        L2ManifestDailyTargetContractTest.properties(),
                        CnTradeDateLayout.TRADE_DATE_V2);
        for (Object id : List.of(37.0, "37", 0L, -1L)) {
            when(jdbc.queryForList(anyString(), eq(TABLE)))
                    .thenReturn(List.of(Map.of("id", id, "directoryName", "x")));
            assertThrows(IllegalStateException.class, target::targetId);
        }
        when(jdbc.queryForList(anyString(), eq(TABLE)))
                .thenReturn(List.of(Map.of("id", 37L, "directoryName", "")));
        assertThrows(IllegalStateException.class, target::targetId);
        assertThrows(
                IllegalArgumentException.class,
                () -> L2DailyFeaturesTarget.forFrozenVersion(target, 3));
        var legacy = mock(L2DailyFeaturesTarget.class);
        assertSame(legacy, L2DailyFeaturesTarget.forFrozenVersion(legacy, 1));
        assertThrows(
                IllegalArgumentException.class,
                () -> L2DailyFeaturesTarget.forFrozenVersion(legacy, 2));
        var wrong = mock(L2DailyFeaturesTarget.Versioned.class);
        when(wrong.tableName()).thenReturn(TABLE);
        when(wrong.forVersion(2)).thenReturn(legacy);
        assertThrows(
                IllegalStateException.class,
                () -> L2DailyFeaturesTarget.forFrozenVersion(wrong, 2));
    }
}
