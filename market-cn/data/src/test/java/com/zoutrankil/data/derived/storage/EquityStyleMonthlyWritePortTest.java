package com.zoutrankil.data.derived.storage;

import com.zoutrankil.data.repository.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import com.zoutrankil.data.config.QuestDbProperties;
import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.derived.mapper.EquityStyleMonthlyMapper;
import io.questdb.client.Sender;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import java.time.YearMonth;
import java.util.*;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

class EquityStyleMonthlyWritePortTest {
    private static final YearMonth MONTH=YearMonth.of(2026,6);
    private static final String TABLE="java_d103_equity_style_monthly_unit";
    private EquityStyleMonthly row(YearMonth month) {
        var values=new LinkedHashMap<String,Object>();values.put("month",month.atDay(1));
        EquityStyleMonthlyDataset.STORAGE_COLUMNS.subList(1,30).forEach(f->values.put(f,1.2345678901234567));
        values.put("hs300_ret_1m",-0.0);values.put("value_ret_1m",null);
        return new EquityStyleMonthlyMapper().fromValues(values);
    }
    private static class SendingPort extends EquityStyleMonthlyWritePort {
        int preflights;
        SendingPort(Supplier<Sender> factory) {
            super(new JdbcTemplate(mock(DataSource.class)),new QuestDbProperties(),TABLE,factory);
        }
        @Override public synchronized void preflight() {
            preflights++;
            if(unresolved())throw new IllegalStateException("Unknown submission cannot be replayed");
        }
    }
    private Sender sender() { return mock(Sender.class,RETURNS_SELF); }
    @Test void freshPortHasNoHistoricalWriterStoppedProof() {
        var sender=sender();var port=new SendingPort(()->sender);
        assertFalse(port.uncertainSenderStopped());assertFalse(port.unresolved());verifyNoInteractions(sender);
    }
    @Test void targetIdentityIsStableLegalBoundedAndChangesWithEndpointSchemaOrGeneration() {
        String endpoint = "static-v2-" + "a".repeat(64), schema = "b".repeat(64);
        String target = EquityStyleMonthlyWritePort.targetIdentity(endpoint, schema);
        assertEquals(69, target.length()); assertTrue(target.matches("d103-[0-9a-f]{64}"));
        assertDoesNotThrow(() -> SyncJobDefinition.name(target));
        assertEquals(target, EquityStyleMonthlyWritePort.targetIdentity(endpoint, schema));
        assertNotEquals(target, EquityStyleMonthlyWritePort.targetIdentity("static-v2-" + "c".repeat(64), schema));
        assertNotEquals(target, EquityStyleMonthlyWritePort.targetIdentity(endpoint, "c".repeat(64)));
        assertThrows(IllegalArgumentException.class, () -> EquityStyleMonthlyWritePort.targetIdentity("arbitrary", schema));
    }
    @Test void onlyExplicitIsolatedNameIsAdmittedBeforeAnyConnectionOrSender() {
        var ds=mock(DataSource.class);var jdbc=new JdbcTemplate(ds);var factory=mock(Supplier.class);
        for(String invalid:List.of("equity_style_monthly","java_d103_equity_style_monthly_","java_d103_equity_style_monthly_A",
                "java_d103_equity_style_monthly_unit;DROP_TABLE","other"))
            assertThrows(IllegalArgumentException.class,()->new EquityStyleMonthlyWritePort(jdbc,new QuestDbProperties(),invalid,factory));
        assertEquals(TABLE,EquityStyleMonthlyWritePort.requireIsolatedTable(TABLE));
        verifyNoInteractions(ds,factory);
    }
    @Test void batchIsOneToTwelveUniqueMonthsAndRejectsEmptyNullDuplicateAndOverflowBeforeSend() {
        var sender=sender();var port=new SendingPort(()->sender);
        assertThrows(IllegalArgumentException.class,()->port.send(List.of()));
        assertThrows(IllegalArgumentException.class,()->port.send(null));
        assertThrows(NullPointerException.class,()->port.send(Arrays.asList((EquityStyleMonthly)null)));
        assertThrows(IllegalArgumentException.class,()->port.send(List.of(row(MONTH),row(MONTH))));
        var thirteen=new ArrayList<EquityStyleMonthly>();for(int i=0;i<13;i++)thirteen.add(row(MONTH.plusMonths(i)));
        assertThrows(IllegalArgumentException.class,()->port.send(thirteen));
        assertEquals(0,port.preflights);verifyNoInteractions(sender);
    }
    @Test void allNullHistoricalRowCannotBecomeTimestampOnlyIlpButRemainsTypedReadable() {
        var values=new LinkedHashMap<>(new EquityStyleMonthlyMapper().values(row(MONTH)).asMap());
        EquityStyleMonthlyDataset.STORAGE_COLUMNS.subList(1,30).forEach(f->values.put(f,null));
        var historical=new EquityStyleMonthlyMapper().fromValues(values);
        assertEquals(9,EquityStyleMonthlyWritePort.CODEC.canonicalBytes(historical).length);
        assertThrows(IllegalArgumentException.class,()->EquityStyleMonthlyWritePort.requireBatch(List.of(historical)));
    }
    @Test void explicitAcknowledgedFlushOccursOnceAndCloseDoesNotSubmitAgain() throws Exception {
        var sender=sender();var port=new SendingPort(()->sender);port.send(List.of(row(MONTH)));
        verify(sender,times(1)).table(TABLE);verify(sender,times(1)).flush();
        verify(sender,times(1)).reset();verify(sender,times(1)).close();
        verify(sender).at(new EquityStyleMonthlyKey(MONTH).storageCarrier());
        verify(sender).doubleColumn("hs300_ret_1m",-0.0);verify(sender,never()).doubleColumn(eq("value_ret_1m"),anyDouble());
        verify(sender,never()).flushAndGetSequence();verify(sender,never()).awaitAckedFsn(anyLong(),anyLong());
        assertTrue(port.uncertainSenderStopped());assertFalse(port.unresolved());assertEquals(1,port.preflights);
    }
    @Test void unknownAckRetainsUnresolvedAndNeverAutomaticallyFlushesOrReplays() {
        var sender=sender();doThrow(new IllegalStateException("Unknown acknowledgement")).when(sender).flush();
        var port=new SendingPort(()->sender);
        assertThrows(IllegalStateException.class,()->port.send(List.of(row(MONTH))));
        assertTrue(port.unresolved());assertTrue(port.uncertainSenderStopped());
        assertThrows(IllegalStateException.class,()->port.send(List.of(row(MONTH))));
        verify(sender,times(1)).flush();verify(sender,times(1)).reset();verify(sender,times(1)).close();
    }
    @Test void closeFailureCannotClaimWriterStoppedEvenWhenFlushAcknowledged() {
        var sender=sender();doThrow(new IllegalStateException("close failed")).when(sender).close();
        var port=new SendingPort(()->sender);
        assertThrows(IllegalStateException.class,()->port.send(List.of(row(MONTH))));
        assertTrue(port.unresolved());assertFalse(port.uncertainSenderStopped());
        assertThrows(IllegalStateException.class,()->port.send(List.of(row(MONTH))));
        verify(sender,times(1)).flush();verify(sender,times(1)).close();
    }
    @Test void constructingRowsFailureDiscardsBufferWithoutCallingFlush() {
        var sender=sender();doThrow(new IllegalArgumentException("row rejected")).when(sender).doubleColumn(eq("hs300_ret_1m"),anyDouble());
        var port=new SendingPort(()->sender);
        assertThrows(IllegalArgumentException.class,()->port.send(List.of(row(MONTH))));
        verify(sender,never()).flush();verify(sender).reset();verify(sender).close();
        assertTrue(port.uncertainSenderStopped());assertFalse(port.unresolved());
    }
    @Test void senderConstructionFailureHasNoSubmissionAndLeavesStoppedState() {
        var port=new SendingPort(()->{throw new IllegalStateException("not created");});
        assertThrows(IllegalStateException.class,()->port.send(List.of(row(MONTH))));
        assertTrue(port.uncertainSenderStopped());assertFalse(port.unresolved());
    }
    @Test void twelveRowsRemainBelowOneMiBBudgetAndDistinctCanonicalKeys() {
        var rows=new ArrayList<EquityStyleMonthly>();long size=0;
        for(int i=0;i<12;i++){var row=row(MONTH.plusMonths(i));rows.add(row);var bytes=EquityStyleMonthlyWritePort.CODEC.canonicalBytes(row);
            size+=EquityStyleMonthlyWritePort.CODEC.estimatedTransportBytes(row,bytes);}
        assertTrue(size<EquityStyleMonthlyWritePort.MAX_BYTES);assertDoesNotThrow(()->EquityStyleMonthlyWritePort.requireBatch(rows));
        assertEquals(12,rows.stream().map(EquityStyleMonthlyWritePort.CODEC::key).distinct().count());
    }
    private com.zoutrankil.data.domain.EquityStyleMonthlyTargetSnapshot snapshot(Long txn,Long wal,long seq,long writer,long pending,long buffered,
                                                           boolean suspended,long count,Long metadataRows) {
        return new com.zoutrankil.data.domain.EquityStyleMonthlyTargetSnapshot("bound-id",3,"target~3","schema-sha",txn,wal,seq,writer,pending,buffered,suspended,count,metadataRows);
    }
    @Test void emptyPhysicalTxnRemainsNullAndRequiresIndependentZeroRowsAndZeroWal() {
        var empty=snapshot(null,null,0,0,0,0,false,0,null);
        assertNull(empty.physicalTxn());assertNull(empty.metadataRowCount());assertTrue(empty.settled());
        assertTrue(snapshot(null,0L,0,0,0,0,false,0,0L).settled());
        assertFalse(snapshot(null,null,0,0,0,0,false,1,null).settled());
        assertFalse(snapshot(null,null,1,1,0,0,false,0,null).settled());
        assertFalse(snapshot(null,null,0,0,0,0,false,0,1L).settled());
    }
    @Test void nonemptySnapshotsPreservePhysicalWalDifferencesAndRejectPendingOrSuspended() {
        assertTrue(snapshot(7L,3L,3,3,0,0,false,2,2L).settled());
        assertTrue(snapshot(7L,null,3,3,0,0,false,2,2L).settled());
        assertFalse(snapshot(7L,2L,3,3,0,0,false,2,2L).settled());
        assertFalse(snapshot(7L,3L,3,2,0,0,false,2,2L).settled());
        assertFalse(snapshot(7L,3L,3,3,1,0,false,2,2L).settled());
        assertFalse(snapshot(7L,3L,3,3,0,1,false,2,2L).settled());
        assertFalse(snapshot(7L,3L,3,3,0,0,true,2,2L).settled());
        assertThrows(IllegalArgumentException.class,()->snapshot(-1L,null,0,0,0,0,false,0,null));
    }
    @Test void invalidReadbackKeysAndRangeFailBeforeJdbc() {
        var ds=mock(DataSource.class);var port=new EquityStyleMonthlyWritePort(new JdbcTemplate(ds),new QuestDbProperties(),TABLE,()->sender());
        assertThrows(IllegalArgumentException.class,()->port.readback(List.of()));
        assertThrows(IllegalArgumentException.class,()->port.readback(List.of(MONTH,MONTH)));
        assertThrows(NullPointerException.class,()->port.readback(Arrays.asList((YearMonth)null)));
        assertThrows(IllegalArgumentException.class,()->port.readActualRange(MONTH,MONTH.plusMonths(12)));
        assertThrows(IllegalArgumentException.class,()->port.readActualRange(MONTH,MONTH.minusMonths(1)));
        verifyNoInteractions(ds);
    }
}
