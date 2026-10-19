package com.zoutrankil.data.derived.application;

import com.zoutrankil.data.domain.JobDefinitionJson;
import com.zoutrankil.data.derived.domain.*;
import com.zoutrankil.data.derived.port.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MonthlySourceBracketContractTest {
    static final LocalDate JUNE=LocalDate.of(2026,6,1),JULY=LocalDate.of(2026,7,1);

    @Test void equityFacadeBracketsTheCompleteCensusAndRejectsTheSecondSnapshotDrift() {
        var reads=mock(EquityStyleMonthlySourceReadPort.class);
        var before=new EquityStyleMonthlySourceData.Snapshot("index_monthly",17,"index_monthly~17",1,1,"a".repeat(64));
        when(reads.snapshot()).thenReturn(before);when(reads.readWindow(JUNE,JUNE)).thenReturn(EquityStyleMonthlySourceTest.month(JUNE));
        var source=new EquityStyleMonthlySource(reads);var batch=source.read(JUNE,JUNE);
        assertEquals(16,batch.rawRows());assertEquals(1,batch.rows().size());assertEquals(before,batch.snapshot());
        var order=inOrder(reads);order.verify(reads).snapshot();order.verify(reads).readWindow(JUNE,JUNE);order.verify(reads).snapshot();
        when(reads.snapshot()).thenReturn(before,new EquityStyleMonthlySourceData.Snapshot("index_monthly",17,"index_monthly~17",2,2,"a".repeat(64)));
        assertEquals("D103 source changed during complete bounded read",assertThrows(IllegalStateException.class,()->source.read(JUNE,JUNE)).getMessage());
    }

    @Test void macroFacadeSharesOneDeadlineAcrossBothVectorsAndEveryOrderedQuery() {
        var reads=mock(MacroCoreMonthlySourceReadPort.class);var fixture=new MacroCoreMonthlySourceTest.MutableSource();
        var snapshot=fixture.snapshot();var calls=new ArrayList<String>();var deadlines=new ArrayList<Long>();
        when(reads.snapshot(anyLong())).thenAnswer(i->{calls.add("snapshot");deadlines.add(i.getArgument(0));return snapshot;});
        when(reads.readWindow(anyString(),eq(JUNE),eq(JULY),anyLong())).thenAnswer(i->{String table=i.getArgument(0);calls.add(table);deadlines.add(i.getArgument(3));return fixture.window.get(table);});
        var descending=List.copyOf(fixture.context.reversed());
        when(reads.readPrecedingSocialFinancingDescending(eq(JUNE),anyLong())).thenAnswer(i->{calls.add("context");deadlines.add(i.getArgument(1));return descending;});
        long start=System.nanoTime();var batch=new MacroCoreMonthlySource(reads).read(JUNE,JULY);
        assertEquals(List.of("snapshot","cn_cpi","cn_ppi","cn_pmi","cn_m","cn_gdp","sf_month","context","snapshot"),calls);
        assertEquals(1,new HashSet<>(deadlines).size());assertTrue(deadlines.getFirst()-start>=19_000_000_000L);assertEquals(23,batch.rawRows());assertEquals(12,batch.sfContextRows());
        assertEquals(List.copyOf(fixture.context.reversed()),descending);assertEquals(MacroCoreMonthlySourceData.SOURCE_TABLES,List.copyOf(batch.windowRowsBySource().keySet()));
    }

    @Test void extractedPhysicalRecordKeepsTheExactToStringVersionFrameAndJsonComponents()throws Exception {
        var physical=new ArrayList<MacroCoreMonthlySourceData.PhysicalSnapshot>();var frame=new StringBuilder("[");int id=0;
        for(String table:MacroCoreMonthlySourceData.SOURCE_TABLES){
            if(id>0)frame.append(", ");physical.add(new MacroCoreMonthlySourceData.PhysicalSnapshot(table,id,table+"~unit",null,null,0,0,0,0,null,"a".repeat(64)));
            frame.append("PhysicalSnapshot[table=").append(table).append(", tableId=").append(id++).append(", directory=").append(table).append("~unit, physicalTxn=null, walTxn=null, sequenceTxn=0, writerTxn=0, pendingRows=0, bufferedTxns=0, metadataRowCount=null, schemaHash=").append("a".repeat(64)).append("]");
        }
        frame.append("]");var snapshot=new MacroCoreMonthlySourceData.Snapshot(physical);
        assertEquals(frame.toString(),snapshot.sources().toString());assertEquals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(frame.toString().getBytes(StandardCharsets.UTF_8))),snapshot.version());
        assertEquals(List.of("table","tableId","directory","physicalTxn","walTxn","sequenceTxn","writerTxn","pendingRows","bufferedTxns","metadataRowCount","schemaHash"),
                Arrays.stream(MacroCoreMonthlySourceData.PhysicalSnapshot.class.getRecordComponents()).map(java.lang.reflect.RecordComponent::getName).toList());
        assertEquals(snapshot,JobDefinitionJson.mapper().readValue(JobDefinitionJson.mapper().writeValueAsBytes(snapshot),MacroCoreMonthlySourceData.Snapshot.class));
    }
}
