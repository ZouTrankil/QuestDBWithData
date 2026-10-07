package com.zoutrankil.data.index.storage;

import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.index.domain.*;
import io.questdb.client.QuestDB;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import javax.sql.DataSource;
import java.sql.ResultSet;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class IndexWindowTargetContractTest {
    static final String PHYSICAL="static-v2-"+"1".repeat(64);
    @Test void constructorsAndSessionFactoriesDoNotConnectAndAlwaysReturnFreshState()throws Exception {
        var ds=mock(DataSource.class);var jdbc=new JdbcTemplate(ds);var questdb=mock(QuestDB.class);
        var monthly=new QuestDbIndexMonthlyTarget("java_d022_index_monthly_contract",jdbc,questdb);
        var dc=new QuestDbDcIndexTarget("java_d023_dc_index_contract",jdbc,questdb);
        var first=monthly.newWriter(PHYSICAL);var second=monthly.newWriter(PHYSICAL);
        assertNotSame(first,second);assertSame(first.codec(),second.codec());
        first.useStagingTarget("java_d022_index_monthly_contract_stage_first",PHYSICAL);
        first.configureFinalPublication(List.of(monthlyRow()),()->{fail("Factory/configuration must not publish");return PHYSICAL;});
        assertFalse(first.publicationComplete());assertFalse(second.publicationComplete());
        assertThrows(IllegalArgumentException.class,()->second.configureFinalPublication(List.of(monthlyRow()),()->PHYSICAL));
        var dcFirst=dc.newWriter(PHYSICAL);var dcSecond=dc.newWriter(PHYSICAL);
        assertNotSame(dcFirst,dcSecond);assertSame(dcFirst.codec(),dcSecond.codec());
        var stage=dcFirst.forTarget("java_dc_index_stage_"+"f".repeat(32),PHYSICAL);
        assertNotSame(dcFirst,stage);assertNotSame(stage,dc.stageWriter("java_dc_index_stage_"+"f".repeat(32),PHYSICAL));
        assertNotSame(monthly.newStaging(),monthly.newStaging());assertNotSame(dc.newStaging(),dc.newStaging());
        verifyNoInteractions(ds,questdb);
    }
    @Test void externalTargetAdmissionAndDirectFormalSendAreRejectedBeforeDatabaseCalls()throws Exception {
        var ds=mock(DataSource.class);var jdbc=new JdbcTemplate(ds);var questdb=mock(QuestDB.class);
        assertThrows(IllegalArgumentException.class,()->new QuestDbIndexMonthlyTarget("index_monthly",jdbc,questdb));
        assertThrows(IllegalStateException.class,()->new QuestDbDcIndexTarget("dc_index",jdbc,questdb));
        var dc=new QuestDbDcIndexTarget("java_d023_dc_index_contract",jdbc,questdb);
        assertThrows(IllegalStateException.class,()->dc.newWriter(PHYSICAL).send(List.of(dcRow())));
        var monthly=new QuestDbIndexMonthlyTarget("java_d022_index_monthly_contract",jdbc,questdb);
        assertThrows(IllegalStateException.class,()->monthly.newWriter(PHYSICAL).send(List.of(monthlyRow())));
        verifyNoInteractions(ds,questdb);
    }
    @Test void dcRangeRetainsExactSqlAndMicrosecondDateInterpretation()throws Exception {
        var jdbc=mock(JdbcTemplate.class);var rows=mock(ResultSet.class);when(rows.next()).thenReturn(true);
        long micros=LocalDate.of(2026,9,17).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()*1000;
        when(rows.getObject(1)).thenReturn(micros);when(rows.getObject(2)).thenReturn(micros+86_400_000_000L);
        doAnswer(call->((ResultSetExtractor<?>)call.getArgument(1)).extractData(rows)).when(jdbc).query(anyString(),any(ResultSetExtractor.class));
        var target=new QuestDbDcIndexTarget("java_d023_dc_index_contract",jdbc,mock(QuestDB.class));
        assertEquals(new DcIndexState.DateRange(LocalDate.of(2026,9,17),LocalDate.of(2026,9,18)),target.range());
        verify(jdbc).query(eq("SELECT cast(min(trade_date) AS long) AS min_micros,cast(max(trade_date) AS long) AS max_micros FROM \"java_d023_dc_index_contract\""),any(ResultSetExtractor.class));
        when(rows.getObject(1)).thenReturn(null);when(rows.getObject(2)).thenReturn(null);
        assertEquals(new DcIndexState.DateRange(null,null),target.range());
        when(rows.getObject(2)).thenReturn("2026-09-18");
        assertEquals("D023 target date is not timestamp",assertThrows(IllegalStateException.class,target::range).getMessage());
        when(rows.next()).thenReturn(false);
        assertEquals("D023 target range unavailable",assertThrows(IllegalStateException.class,target::range).getMessage());
    }
    @Test void pureLogicalIdentityRetainsDefaultPortCaseAndEscapedDatabasePath()throws Exception {
        String endpoint="example.org:5432/db%20name",table="java_d023_dc_index_contract";
        byte[] body=("dc_index-logical-v1\n"+endpoint+"\n"+table).getBytes(StandardCharsets.UTF_8);
        String expected="static-v2-"+HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(body));
        assertEquals(expected,DcIndexTargetIdentity.logical("jdbc:postgresql://EXAMPLE.ORG/db%20name?ssl=false",table));
        assertEquals(expected,DcIndexTargetIdentity.logical("jdbc:postgresql://example.org:5432/db%20name?user=ignored",table));
        assertNotEquals(expected,DcIndexTargetIdentity.logical("jdbc:postgresql://example.org:8812/db%20name",table));
        assertThrows(IllegalArgumentException.class,()->DcIndexTargetIdentity.logical("jdbc:sqlite:local",table));
    }
    @Test void extractedCanonicalRowsKeepOriginalOrderedFieldsNullsAndSignedZero() {
        String monthly="{\"ts_code\":\"000300.SH\",\"trade_date\":\"2026-01-30\",\"close\":-0.0,\"open\":null,\"high\":null,\"low\":null,\"pre_close\":null,\"change\":null,\"pct_chg\":null,\"vol\":null,\"amount\":null,\"layer\":\"macro_core\",\"bucket\":\"broad_base\",\"update_time\":\"2026-02-01T00:00:00.123456Z\"}";
        String dc="{\"ts_code\":\"BK001.DC\",\"trade_date\":\"2026-09-17\",\"name\":null,\"leading\":null,\"leading_code\":null,\"pct_change\":-0.0,\"leading_pct\":null,\"total_mv\":null,\"turnover_rate\":null,\"up_num\":null,\"down_num\":null}";
        assertArrayEquals(monthly.getBytes(StandardCharsets.UTF_8),IndexMonthlyRows.canonicalBytes(monthlyRow()));
        assertArrayEquals(dc.getBytes(StandardCharsets.UTF_8),DcIndexRows.canonicalBytes(dcRow()));
        assertArrayEquals(IndexMonthlyRows.canonicalBytes(monthlyRow()),IndexMonthlyWritePort.CODEC.canonicalBytes(monthlyRow()));
        assertArrayEquals(DcIndexRows.canonicalBytes(dcRow()),DcIndexWritePort.CODEC.canonicalBytes(dcRow()));
    }
    static IndexMonthly monthlyRow(){return new IndexMonthly("000300.SH",LocalDate.of(2026,1,30),-0.0,null,null,null,null,null,null,null,null,"macro_core","broad_base",Instant.parse("2026-02-01T00:00:00.123456Z"));}
    static DcIndex dcRow(){return new DcIndex("BK001.DC",LocalDate.of(2026,9,17),null,null,null,-0.0,null,null,null,null,null);}
}
