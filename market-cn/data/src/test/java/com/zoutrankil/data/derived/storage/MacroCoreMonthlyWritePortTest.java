package com.zoutrankil.data.derived.storage;

import com.zoutrankil.data.repository.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import com.zoutrankil.data.config.QuestDbProperties;
import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.derived.mapper.MacroCoreMonthlyMapper;
import io.questdb.client.Sender;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import java.time.YearMonth;
import java.util.*;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

class MacroCoreMonthlyWritePortTest {
    private static final YearMonth MONTH=YearMonth.of(2026,6);
    private static final String TABLE="java_d104_macro_core_monthly_unit";
    private MacroCoreMonthly row(YearMonth month) {
        var values=new LinkedHashMap<String,Object>();values.put("month",month.atDay(1));
        MacroCoreMonthlyDataset.STORAGE_COLUMNS.subList(1,9).forEach(f->values.put(f,1.2345678901234567));
        values.put("cpi_yoy",-0.0);values.put("gdp_yoy",null);
        return new MacroCoreMonthlyMapper().fromValues(values);
    }
    private static class SendingPort extends MacroCoreMonthlyWritePort {
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
        String target = MacroCoreMonthlyWritePort.targetIdentity(endpoint, schema);
        assertEquals(69, target.length()); assertTrue(target.matches("d104-[0-9a-f]{64}"));
        assertDoesNotThrow(() -> SyncJobDefinition.name(target));
        assertEquals(target, MacroCoreMonthlyWritePort.targetIdentity(endpoint, schema));
        assertNotEquals(target, MacroCoreMonthlyWritePort.targetIdentity("static-v2-" + "c".repeat(64), schema));
        assertNotEquals(target, MacroCoreMonthlyWritePort.targetIdentity(endpoint, "c".repeat(64)));
        assertThrows(IllegalArgumentException.class, () -> MacroCoreMonthlyWritePort.targetIdentity("arbitrary", schema));
    }
    @Test void onlyExplicitIsolatedNameIsAdmittedBeforeAnyConnectionOrSender() {
        var ds=mock(DataSource.class);var jdbc=new JdbcTemplate(ds);var factory=mock(Supplier.class);
        for(String invalid:List.of("macro_core_monthly","java_d104_macro_core_monthly_","java_d104_macro_core_monthly_A",
                "java_d104_macro_core_monthly_unit;DROP_TABLE","other"))
            assertThrows(IllegalArgumentException.class,()->new MacroCoreMonthlyWritePort(jdbc,new QuestDbProperties(),invalid,factory));
        assertEquals(TABLE,MacroCoreMonthlyWritePort.requireIsolatedTable(TABLE));
        verifyNoInteractions(ds,factory);
    }
    @Test void batchIsOneToTwelveUniqueMonthsAndRejectsEmptyNullDuplicateAndOverflowBeforeSend() {
        var sender=sender();var port=new SendingPort(()->sender);
        assertThrows(IllegalArgumentException.class,()->port.send(List.of()));
        assertThrows(IllegalArgumentException.class,()->port.send(null));
        assertThrows(NullPointerException.class,()->port.send(Arrays.asList((MacroCoreMonthly)null)));
        assertThrows(IllegalArgumentException.class,()->port.send(List.of(row(MONTH),row(MONTH))));
        var thirteen=new ArrayList<MacroCoreMonthly>();for(int i=0;i<13;i++)thirteen.add(row(MONTH.plusMonths(i)));
        assertThrows(IllegalArgumentException.class,()->port.send(thirteen));
        assertEquals(0,port.preflights);verifyNoInteractions(sender);
    }
    @Test void allNullHistoricalRowCannotBecomeTimestampOnlyIlpButRemainsTypedReadable() {
        var values=new LinkedHashMap<>(new MacroCoreMonthlyMapper().values(row(MONTH)).asMap());
        MacroCoreMonthlyDataset.STORAGE_COLUMNS.subList(1,9).forEach(f->values.put(f,null));
        var historical=new MacroCoreMonthlyMapper().fromValues(values);
        assertEquals(9,MacroCoreMonthlyWritePort.CODEC.canonicalBytes(historical).length);
        assertThrows(IllegalArgumentException.class,()->MacroCoreMonthlyWritePort.requireBatch(List.of(historical)));
    }
    @Test void eachMissingRequiredMonthlyFieldRejectsBeforeAnyPreflightOrSender() {
        var sender=sender();var port=new SendingPort(()->sender);
        for(String field:MacroCoreMonthlyDataset.REQUIRED_MONTHLY_FIELDS) {
            var values=new LinkedHashMap<>(new MacroCoreMonthlyMapper().values(row(MONTH)).asMap());values.put(field,null);
            var historical=new MacroCoreMonthlyMapper().fromValues(values);
            assertThrows(IllegalArgumentException.class,()->port.send(List.of(historical)),field);
        }
        assertEquals(0,port.preflights);verifyNoInteractions(sender);
    }
    @Test void optionalQuarterlyGdpAndTwelveObservationRatioCanBothBeNullOnCompleteWrite() throws Exception {
        var values=new LinkedHashMap<>(new MacroCoreMonthlyMapper().values(row(MONTH)).asMap());
        values.put("gdp_yoy",null);values.put("social_financing_yoy",null);
        var sender=sender();var port=new SendingPort(()->sender);port.send(List.of(new MacroCoreMonthlyMapper().fromValues(values)));
        verify(sender,never()).doubleColumn(eq("gdp_yoy"),anyDouble());
        verify(sender,never()).doubleColumn(eq("social_financing_yoy"),anyDouble());
        verify(sender,times(1)).flush();assertTrue(port.uncertainSenderStopped());assertFalse(port.unresolved());
    }
    @Test void explicitAcknowledgedFlushOccursOnceAndCloseDoesNotSubmitAgain() throws Exception {
        var sender=sender();var port=new SendingPort(()->sender);port.send(List.of(row(MONTH)));
        verify(sender,times(1)).table(TABLE);verify(sender,times(1)).flush();
        verify(sender,times(1)).reset();verify(sender,times(1)).close();
        verify(sender).at(new MacroCoreMonthlyKey(MONTH).storageCarrier());
        verify(sender).doubleColumn("cpi_yoy",-0.0);verify(sender,never()).doubleColumn(eq("gdp_yoy"),anyDouble());
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
        var sender=sender();doThrow(new IllegalArgumentException("row rejected")).when(sender).doubleColumn(eq("cpi_yoy"),anyDouble());
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
        var rows=new ArrayList<MacroCoreMonthly>();long size=0;
        for(int i=0;i<12;i++){var row=row(MONTH.plusMonths(i));rows.add(row);var bytes=MacroCoreMonthlyWritePort.CODEC.canonicalBytes(row);
            size+=MacroCoreMonthlyWritePort.CODEC.estimatedTransportBytes(row,bytes);}
        assertTrue(size<MacroCoreMonthlyWritePort.MAX_BYTES);assertDoesNotThrow(()->MacroCoreMonthlyWritePort.requireBatch(rows));
        assertEquals(12,rows.stream().map(MacroCoreMonthlyWritePort.CODEC::key).distinct().count());
    }
    private com.zoutrankil.data.domain.MacroCoreMonthlyTargetSnapshot snapshot(Long txn,Long wal,long seq,long writer,long pending,long buffered,
                                                           boolean suspended,long count,Long metadataRows) {
        return new com.zoutrankil.data.domain.MacroCoreMonthlyTargetSnapshot("bound-id",3,"target~3","schema-sha",txn,wal,seq,writer,pending,buffered,suspended,count,metadataRows);
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
        assertFalse(snapshot(7L,null,3,3,0,0,false,2,2L).settled());
        assertFalse(snapshot(7L,2L,3,3,0,0,false,2,2L).settled());
        assertFalse(snapshot(7L,3L,3,2,0,0,false,2,2L).settled());
        assertFalse(snapshot(7L,3L,3,3,1,0,false,2,2L).settled());
        assertFalse(snapshot(7L,3L,3,3,0,1,false,2,2L).settled());
        assertFalse(snapshot(7L,3L,3,3,0,0,true,2,2L).settled());
        assertThrows(IllegalArgumentException.class,()->snapshot(-1L,null,0,0,0,0,false,0,null));
    }
    @Test void initializedMissingWalFrontierRequiresActualEmptyRowsAndEveryZeroWalFieldWithoutNormalizingNull() {
        var empty=snapshot(0L,null,0,0,0,0,false,0,null);
        assertTrue(empty.settled());assertEquals(0L,empty.physicalTxn());assertNull(empty.walTxn());assertNull(empty.metadataRowCount());
        assertFalse(snapshot(7L,null,0,0,0,0,false,0,0L).settled());
        assertFalse(snapshot(7L,null,0,0,0,0,false,1,null).settled());
        assertFalse(snapshot(7L,null,1,1,0,0,false,0,null).settled());
        assertFalse(snapshot(7L,null,0,0,1,0,false,0,null).settled());
        assertFalse(snapshot(7L,null,0,0,0,1,false,0,null).settled());
        assertFalse(snapshot(7L,null,0,0,0,0,true,0,null).settled());
        assertFalse(snapshot(7L,null,0,0,0,0,false,0,1L).settled());
        assertTrue(snapshot(0L,0L,0,0,0,0,false,0,null).settled());
        assertFalse(snapshot(7L,3L,3,3,0,0,false,2,null).settled());
    }
    @Test void positivePhysicalTxnCannotUseNullableNewEmptyFrontierProof() {
        for(long physical:List.of(1L,7L,Long.MAX_VALUE)) {
            assertFalse(snapshot(physical,null,0,0,0,0,false,0,null).settled());
            assertFalse(snapshot(physical,null,0,0,0,0,false,0,0L).settled());
            assertFalse(snapshot(physical,0L,0,0,0,0,false,0,null).settled());
        }
        assertTrue(snapshot(null,null,0,0,0,0,false,0,null).settled());
        assertTrue(snapshot(0L,null,0,0,0,0,false,0,0L).settled());
        assertTrue(snapshot(0L,0L,0,0,0,0,false,0,null).settled());
        assertTrue(snapshot(7L,3L,3,3,0,0,false,2,2L).settled());
    }
    @Test void invalidReadbackKeysAndRangeFailBeforeJdbc() {
        var ds=mock(DataSource.class);var port=new MacroCoreMonthlyWritePort(new JdbcTemplate(ds),new QuestDbProperties(),TABLE,()->sender());
        assertThrows(IllegalArgumentException.class,()->port.readback(List.of()));
        assertThrows(IllegalArgumentException.class,()->port.readback(List.of(MONTH,MONTH)));
        assertThrows(NullPointerException.class,()->port.readback(Arrays.asList((YearMonth)null)));
        assertThrows(IllegalArgumentException.class,()->port.readActualRange(MONTH,MONTH.plusMonths(12)));
        assertThrows(IllegalArgumentException.class,()->port.readActualRange(MONTH,MONTH.minusMonths(1)));
        verifyNoInteractions(ds);
    }
}
