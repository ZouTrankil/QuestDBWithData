package com.zoutrankil.data.margin.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.margin.port.*;
import com.zoutrankil.data.repository.FileEvidenceStore;
import com.zoutrankil.data.service.*;
import java.nio.file.*;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MarginSourceOwnerContractTest {
    static final LocalDate DAY=LocalDate.of(2025,7,1);
    @TempDir Path temp;

    @ParameterizedTest @ValueSource(booleans={false,true})
    void sourceUsesExactEndpointParametersAndReopensTheSameImmutableBytes(boolean zrz)throws Exception {
        var pages=mock(TusharePageService.class);var cancelled=(BooleanSupplier)()->false;
        var raw=row(zrz,"20250701");var params=new ArrayList<Map<String,Object>>();
        when(pages.fetcher(eq(zrz?MarginZrzSource.CONTRACT:MarginAllSource.CONTRACT),same(cancelled))).thenReturn(request->{params.add(request);return new PageExecutor.Page(List.of(raw),null,false,"fixture-v1");});
        SyncJobRunner.Page<?> first=zrz?new MarginZrzSource(pages,temp).fetch(DAY,DAY,cancelled):new MarginAllSource(pages,temp).fetch(DAY,cancelled);
        assertEquals(List.of(zrz?Map.of("start_date","20250701","end_date","20250701"):Map.of("trade_date","20250701")),params);
        assertEquals(1,first.rows().size());Path path=Path.of(first.responseEvidence());byte[] bytes=Files.readAllBytes(path);
        assertEquals(FileEvidenceStore.sha256(bytes),first.sourceFingerprint());var body=JobDefinitionJson.mapper().readTree(bytes);
        assertEquals(zrz?"slb_len":"margin",body.path("endpoint").asText());assertEquals("fixture-v1",body.path("sourceVersion").asText());assertTrue(body.path("sourceComplete").asBoolean());
        var again=zrz?MarginZrzSource.reopen(path,first.sourceFingerprint(),DAY,DAY):MarginAllSource.reopen(path,first.sourceFingerprint(),DAY);
        assertEquals(first,again);assertArrayEquals(bytes,Files.readAllBytes(path));
        if(zrz)assertNull(((MarginZrz)first.rows().getFirst()).repoAmount());
        Files.writeString(path,"{}");assertThrows(IllegalStateException.class,()->{if(zrz)MarginZrzSource.reopen(path,first.sourceFingerprint(),DAY,DAY);else MarginAllSource.reopen(path,first.sourceFingerprint(),DAY);});
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void cancellationAfterFetchRetainsTheAlreadyReceivedRawRows(boolean zrz)throws Exception {
        var pages=mock(TusharePageService.class);var cancel=new AtomicBoolean();BooleanSupplier cancelled=cancel::get;
        when(pages.fetcher(any(),same(cancelled))).thenReturn(request->{cancel.set(true);return new PageExecutor.Page(List.of(row(zrz,"20250701")),null,false,null);});
        assertThrows(PageExecutor.Incomplete.class,()->{if(zrz)new MarginZrzSource(pages,temp).fetch(DAY,DAY,cancelled);else new MarginAllSource(pages,temp).fetch(DAY,cancelled);});
        try(var files=Files.list(temp)){var receipts=files.toList();assertEquals(1,receipts.size());assertTrue(receipts.getFirst().getFileName().toString().startsWith("incomplete-"));var proof=JobDefinitionJson.mapper().readTree(receipts.getFirst().toFile());assertFalse(proof.path("sourceComplete").asBoolean());assertEquals(1,proof.path("returnedRows").asInt());assertEquals(1,proof.path("rawRows").size());}
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void invalidProviderRowsAreRetainedBeforeValidationFailure(boolean zrz)throws Exception {
        var pages=mock(TusharePageService.class);var raw=row(zrz,"20250630");when(pages.fetcher(any(),any())).thenReturn(request->new PageExecutor.Page(List.of(raw),null,false,null));
        assertThrows(PageExecutor.Incomplete.class,()->{if(zrz)new MarginZrzSource(pages,temp).fetch(DAY,DAY,()->false);else new MarginAllSource(pages,temp).fetch(DAY,()->false);});
        try(var files=Files.list(temp)){var proof=JobDefinitionJson.mapper().readTree(files.findFirst().orElseThrow().toFile());assertEquals("20250630",proof.path("rawRows").get(0).path("trade_date").asText());assertFalse(proof.path("sourceComplete").asBoolean());}
    }
    @Test void zrzRemainsRetiredAndRejectsForwardOrUnboundedPlansBeforeTargetIo()throws Exception {
        assertFalse(MarginZrzSyncJobOwner.DEFINITION.enabled());assertFalse(MarginZrzSyncJobOwner.DEFINITION.dailyEligible());assertEquals(SyncJobDefinition.Frequency.MANUAL,MarginZrzSyncJobOwner.DEFINITION.frequency());
        assertEquals(LocalDate.of(2025,7,25),MarginZrzJobService.LAST_AUDITED_SOURCE_DATE);
        var target=mock(MarginZrzTarget.class);when(target.tableName()).thenReturn("java_d031_margin_zrz_contract");var jobs=mock(SyncJobRegistry.class);var pages=mock(TusharePageService.class);
        var owner=new MarginZrzJobService(jobs,pages,target,temp.resolve("ledger.sqlite3"));clearInvocations(target);
        assertThrows(NullPointerException.class,()->owner.plan(SyncJobDefinition.Mode.BACKFILL,DAY,null,LocalDate.of(2025,8,1)));
        assertThrows(IllegalArgumentException.class,()->owner.plan(SyncJobDefinition.Mode.BACKFILL,DAY,LocalDate.of(2025,7,26),LocalDate.of(2025,8,1)));
        verifyNoInteractions(target,jobs,pages);assertFalse(Files.exists(temp.resolve("ledger.sqlite3")));
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void ownerConstructionOnlyReadsConfiguredTableAndRunValidationPrecedesIo(boolean zrz)throws Exception {
        var jobs=mock(SyncJobRegistry.class);var pages=mock(TusharePageService.class);Path ledger=temp.resolve("ledger.sqlite3");
        if(zrz){var target=mock(MarginZrzTarget.class);when(target.tableName()).thenReturn("java_d031_margin_zrz_contract");var owner=new MarginZrzJobService(jobs,pages,target,ledger);verify(target).tableName();clearInvocations(target);assertThrows(IllegalArgumentException.class,()->owner.runRequest(null,null));verifyNoInteractions(target);}
        else{var target=mock(MarginAllTarget.class);when(target.tableName()).thenReturn("java_d028_margin_all_contract");var owner=new MarginAllJobService(jobs,pages,target,ledger);verify(target).tableName();clearInvocations(target);assertThrows(IllegalArgumentException.class,()->owner.runRequest(null,null));verifyNoInteractions(target);}
        verifyNoInteractions(jobs,pages);assertFalse(Files.exists(ledger));
    }
    static Map<String,JsonNode> row(boolean zrz,String day){
        var result=new LinkedHashMap<String,JsonNode>();for(String field:zrz?MarginZrzSource.FIELDS:MarginAllSource.FIELDS)result.put(field,JobDefinitionJson.mapper().valueToTree(field.equals("trade_date")?day:field.equals("exchange_id")?"SSE":field.equals("repo_amount")?null:5.25));return result;
    }
}
