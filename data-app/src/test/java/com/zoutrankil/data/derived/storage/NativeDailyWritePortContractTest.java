package com.zoutrankil.data.derived.storage;

import com.zoutrankil.data.derived.domain.*;
import com.zoutrankil.data.derived.port.*;
import com.zoutrankil.data.domain.table.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

public class NativeDailyWritePortContractTest {
    public record Row(Instant tradeDate,String month,Double value,Boolean flag,Instant updatedAt) {}
    private static final Instant DAY=Instant.parse("2026-09-17T00:00:00Z");
    private static final List<String> COLUMNS=List.of("trade_date","month","value","flag","updated_at");

    @Test void pureProjectionPreservesOrderedNullBooleanTemporalJsonAndNewlineDigest()throws Exception {
        var projection=new NativeDailyProjection<>(Row.class,COLUMNS);var codec=new NativeDailyCodec<>(projection);
        var row=new Row(DAY,"202609",null,false,Instant.parse("2026-09-17T01:02:03.123456Z"));
        String expected="{\"trade_date\":\"2026-09-17T00:00:00Z\",\"month\":\"202609\",\"value\":null,\"flag\":false,\"updated_at\":\"2026-09-17T01:02:03.123456Z\"}";
        assertArrayEquals(expected.getBytes(StandardCharsets.UTF_8),codec.canonicalBytes(row));
        assertEquals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest((expected+"\n").getBytes(StandardCharsets.UTF_8))),projection.digest(List.of(row)));
        assertEquals(COLUMNS,new ArrayList<>(projection.values(row).keySet()));assertEquals(row,projection.row(projection.values(row)));
        assertEquals(expected.getBytes(StandardCharsets.UTF_8).length*3+1024,codec.estimatedTransportBytes(row,codec.canonicalBytes(row)));
        assertEquals(DAY,codec.key(row));assertEquals("\"trade_date\",\"month\",\"value\",\"flag\",\"updated_at\"",projection.quotedColumns());
    }

    @Test void projectionKeepsNonfiniteRowNormalizationAndExactMidnightRejection() {
        var projection=new NativeDailyProjection<>(Row.class,COLUMNS);
        assertNull(projection.row(Map.of("trade_date",DAY,"value",Double.NaN)).value());
        assertNull(projection.row(Map.of("trade_date",DAY,"value",Double.POSITIVE_INFINITY)).value());
        assertThrows(IllegalArgumentException.class,()->projection.values(new Row(DAY,null,Double.NaN,null,null)));
        assertThrows(IllegalArgumentException.class,()->projection.key(new Row(DAY.plusNanos(1000),null,1d,null,null)));
        assertThrows(IllegalArgumentException.class,()->projection.row(Map.of("trade_date",DAY.plusSeconds(1))));
        assertThrows(UnsupportedOperationException.class,()->projection.values(new Row(DAY,null,null,null,null)).put("x",1));
    }

    @Test void publicAdmissionWindowAndNegativeMicrosecondsRemainExact() {
        assertDoesNotThrow(()->NativeDailyWindowRules.requireTarget("formal","formal","java_native"));
        assertDoesNotThrow(()->NativeDailyWindowRules.requireTarget("java_native_stage_abc","formal","java_native"));
        assertThrows(IllegalArgumentException.class,()->NativeDailyWindowRules.requireTarget("other","formal","java_native"));
        LocalDate from=LocalDate.of(2026,1,1);assertDoesNotThrow(()->NativeDailyWindowRules.requireWindow(from,from.plusDays(365)));
        assertThrows(IllegalArgumentException.class,()->NativeDailyWindowRules.requireWindow(from,from.plusDays(366)));
        assertThrows(IllegalArgumentException.class,()->NativeDailyWindowRules.requireWindow(from,from.minusDays(1)));
        assertEquals(Instant.ofEpochSecond(-1,999999000),NativeDailyWindowRules.fromMicros(-1));
        assertEquals(-1,NativeDailyWindowRules.micros(Instant.ofEpochSecond(-1,999999000)));
        assertThrows(ArithmeticException.class,()->NativeDailyWindowRules.micros(Instant.MAX));
    }

    @ParameterizedTest @ValueSource(booleans={false,true})
    void typedWritersKeepNoIoConstructionIndependentSessionsAndOriginalClientSettings(boolean market)throws Exception {
        var source=mock(DataSource.class);var jdbc=new JdbcTemplate(source);
        NativeDailyWindowSession<?> first,second;
        if(market){
            var storage=new MarketSentimentDailyStorage(jdbc,"market_sentiment_daily");
            assertEquals(120,client(storage).getQueryTimeout());assertEquals(2048,client(storage).getFetchSize());
            var left=storage.newWriter();var right=storage.newWriter();assertNotSame(left,right);assertSame(MarketSentimentDailyWriteSession.CODEC,left.codec());
            first=left.nativePort();second=right.nativePort();assertEquals(53,first.columns().size());assertEquals(MarketSentimentDailyRow.class,first.rowType());
        }else{
            var storage=new RegimeFeaturesMonitorDailyStorage(jdbc);
            assertEquals(120,client(storage).getQueryTimeout());assertEquals(2048,client(storage).getFetchSize());
            first=storage.writer("regime_features_monitor_daily");second=storage.writer("regime_features_monitor_daily");
            assertEquals(21,first.columns().size());assertEquals(RegimeFeaturesMonitorDailyRow.class,first.rowType());
        }
        assertNotSame(first,second);assertNotSame(first.codec(),second.codec());
        assertEquals(120,client(first).getQueryTimeout());assertEquals(256,client(first).getFetchSize());
        assertEquals(-1,client(first).getMaxRows());assertThrows(NullPointerException.class,first::stage);assertThrows(NullPointerException.class,second::stage);
        assertEquals(-1,jdbc.getQueryTimeout());verifyNoInteractions(source);
    }

    @Test void invalidTargetOrBatchRejectsBeforeQueryingPhysicalStorage() {
        var source=mock(DataSource.class);var jdbc=new JdbcTemplate(source);
        assertThrows(IllegalArgumentException.class,()->new NativeDailyWindowWritePort<>(jdbc,"other","formal","java_native",Row.class,COLUMNS));
        var writer=new NativeDailyWindowWritePort<>(jdbc,"formal","formal","java_native",Row.class,COLUMNS);
        assertThrows(IllegalArgumentException.class,()->writer.send(List.of()));
        assertThrows(IllegalArgumentException.class,()->writer.send(Collections.nCopies(251,new Row(DAY,null,null,null,null))));
        assertThrows(IllegalArgumentException.class,()->writer.readback(List.of(DAY,DAY)));verifyNoInteractions(source);
    }

    private static JdbcTemplate client(Object instance)throws Exception {var field=instance.getClass().getDeclaredField("jdbc");field.setAccessible(true);return (JdbcTemplate)field.get(instance);}
}
