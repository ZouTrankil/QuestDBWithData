package com.zoutrankil.data.flow.application;

import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.flow.port.*;
import com.zoutrankil.data.flow.storage.*;
import com.zoutrankil.data.margin.application.*;
import com.zoutrankil.data.margin.mapper.MarginDetailMapper;
import com.zoutrankil.data.margin.port.MarginDetailWriteSession;
import com.zoutrankil.data.margin.storage.MarginDetailWritePort;
import com.zoutrankil.data.repository.FileEvidenceStore;
import com.zoutrankil.data.service.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import static com.zoutrankil.data.flow.application.UpsertSourceContractTest.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@SuppressWarnings({"unchecked","rawtypes"})
class UpsertAdapterEvidenceContractTest {
    @TempDir Path temp;

    @ParameterizedTest @EnumSource(Family.class)
    void existingSourceKeyOmissionStopsBeforeConsumerForEveryFamily(Family family)throws Exception {
        var provider=new Pages(List.of(raw(family,"000001.SZ"),raw(family,"600000.SH")));
        var existing=fetch(family,new Pages(List.of(raw(family,"000002.SZ"),raw(family,"600000.SH"))),temp.resolve("existing"),()->false).rows();
        var adapter=adapter(family,provider,existing);var calls=new AtomicInteger();
        assertThrows(IllegalStateException.class,()->adapter.fetch(request(family),p->calls.incrementAndGet(),()->false));
        assertEquals(0,calls.get());assertFalse(Files.exists(temp.resolve("run/complete-window.json")));
        assertEquals(1,provider.requests.size());
    }

    @Test void detailPhysicalBeforeBytesExistBeforeConsumerAndAreReferencedByCompletion()throws Exception {
        var raw=List.of(raw(Family.DETAIL,"000001.SZ"),raw(Family.DETAIL,"600000.SH"));
        var page=fetch(Family.DETAIL,new Pages(raw),temp.resolve("prior"),()->false);
        var existing=(List<MarginDetail>)(List<?>)page.rows();var port=mock(MarginDetailWriteSession.class);
        when(port.readDate(DAY)).thenReturn(existing);when(port.codec()).thenReturn(MarginDetailWritePort.CODEC);
        var root=temp.resolve("run");var source=new MarginDetailSource(new Pages(raw),root.resolve("source"));
        var adapter=new MarginDetailSyncAdapter(source,new MarginDetailTradingDates(UpsertOwnerPortContractTest.calendar()),port,root,UpsertOwnerPortContractTest.ID,false);
        var captured=new ArrayList<Path>();var result=adapter.fetch(request(Family.DETAIL),emitted->{
            try(var files=Files.list(root)){captured.addAll(files.filter(p->p.getFileName().toString().startsWith("physical-before-")).toList());}
            assertEquals(1,captured.size());byte[] bytes=Files.readAllBytes(captured.getFirst());
            var expected=new LinkedHashMap<String,Object>();expected.put("dataset","margin_detail");expected.put("targetId",UpsertOwnerPortContractTest.ID);expected.put("tradeDate",DAY);
            expected.put("rowCount",2);expected.put("physicalRows",existing.stream().map(r->new MarginDetailMapper().values(r).asMap()).toList());
            expected.put("sourceFingerprint",emitted.sourceFingerprint());expected.put("responseEvidence",emitted.responseEvidence());
            assertArrayEquals(JobDefinitionJson.mapper().copy().configure(com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS,true).writeValueAsBytes(expected),bytes);
        },()->false);
        assertTrue(result.complete());assertEquals(2,result.rows());
        var proof=JobDefinitionJson.mapper().readTree(Path.of(result.evidence()).toFile());var ref=proof.path("sourceReceipts").get(0);
        assertEquals(captured.getFirst().toString(),ref.path("physicalBeforeEvidence").asText());
        assertEquals(FileEvidenceStore.sha256(Files.readAllBytes(captured.getFirst())),ref.path("physicalBeforeFingerprint").asText());
        verify(port,never()).send(anyList());
    }

    @Test void thsWholeDateComparisonUsesTheSessionCodecAndRejectsDuplicateReadback()throws Exception {
        var raw=List.of(raw(Family.THS,"000001.SZ"));var sourcePage=fetch(Family.THS,new Pages(raw),temp.resolve("prior"),()->false);
        var row=(MoneyflowThs)sourcePage.rows().getFirst();var port=mock(MoneyflowThsWriteSession.class);
        var codec=spy(MoneyflowThsWritePort.CODEC);when(port.codec()).thenReturn(codec);
        when(port.readDate(DAY)).thenReturn(List.of(),List.of(row));
        var adapter=new MoneyflowThsSyncAdapter(new MoneyflowThsSource(new Pages(raw),temp.resolve("run/source")),UpsertOwnerPortContractTest.calendar(),port,temp.resolve("run"));
        var result=adapter.fetch(request(Family.THS),p->{},()->false);assertTrue(result.complete());verify(codec,times(2)).canonicalBytes(row);
        assertFalse(MoneyflowThsCoverage.sameRows(List.of(row,row),List.of(row,row),codec));
        assertSame(codec,adapter.codec());assertSame(port,adapter.port());
    }

    static SyncJobDefinition.FrozenRequest request(Family f){return UpsertOwnerPortContractTest.request(UpsertOwnerPortContractTest.Family.valueOf(f.name()));}
    SyncJobRunner.Adapter adapter(Family f,TusharePageService pages,List<?> existing){var root=temp.resolve("run");return switch(f){
        case MONEYFLOW->{var p=mock(MoneyflowWriteSession.class);when(p.readDate(DAY)).thenReturn((List)existing);yield new MoneyflowSyncAdapter(pages,new MoneyflowTradingDates(UpsertOwnerPortContractTest.calendar()),p,root,UpsertOwnerPortContractTest.ID);}
        case DC->{var p=mock(MoneyflowDcWriteSession.class);when(p.readDate(DAY)).thenReturn((List)existing);yield new MoneyflowDcSyncAdapter(pages,new MoneyflowDcTradingDates(UpsertOwnerPortContractTest.calendar()),p,root,UpsertOwnerPortContractTest.ID);}
        case THS->{var p=mock(MoneyflowThsWriteSession.class);when(p.readDate(DAY)).thenReturn((List)existing);yield new MoneyflowThsSyncAdapter(new MoneyflowThsSource(pages,root.resolve("source")),UpsertOwnerPortContractTest.calendar(),p,root);}
        case DETAIL->{var p=mock(MarginDetailWriteSession.class);when(p.readDate(DAY)).thenReturn((List)existing);yield new MarginDetailSyncAdapter(new MarginDetailSource(pages,root.resolve("source")),new MarginDetailTradingDates(UpsertOwnerPortContractTest.calendar()),p,root,UpsertOwnerPortContractTest.ID,false);}
    };}
}
