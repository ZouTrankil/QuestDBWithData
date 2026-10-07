package com.zoutrankil.data.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.mapper.EtfMarketOverviewDailyCacheMapper;
import com.zoutrankil.data.repository.*;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Prepared assertions use the ordinary group/SQLite lifecycle and one original-owner unit per day. */
class EtfMarketOverviewCachePreparedWriteAdapterTest {
    @TempDir Path root;
    static final LocalDate DAY = LocalDate.of(2026,9,17);
    static final String VERSION = "a".repeat(64), SOURCE = "b".repeat(64), TARGET = "questdb-" + "c".repeat(64);
    final EtfMarketOverviewDailyCacheMapper mapper = new EtfMarketOverviewDailyCacheMapper();
    record Setup(WriteGroupPlan plan, EtfMarketOverviewCacheDelegatedPort port,
                 EtfMarketOverviewCachePreparedWriteAdapter adapter, DatasetRegistry registry) {}

    EtfMarketOverviewDailyCache row(LocalDate date) { return new EtfMarketOverviewDailyCache(date,765,123456.789,-0.0,VERSION); }
    EtfMarketOverviewCachePublicationEnvelope envelope(LocalDate date) {
        var json=JobDefinitionJson.mapper();var sources=new LinkedHashMap<String,JsonNode>();var targets=new LinkedHashMap<String,JsonNode>();
        for(String table:EtfMarketOverviewCachePublicationEnvelope.SOURCE_TABLES)sources.put(table,json.createObjectNode());
        for(String table:EtfMarketOverviewCachePublicationEnvelope.TARGET_TABLES)targets.put(table,json.createObjectNode());
        String fingerprint=String.format("%064x",date.toEpochDay());
        return new EtfMarketOverviewCachePublicationEnvelope(date,VERSION,row(date),
                new MarketBarometerCacheCoverage(date,"etf_market_overview_daily",VERSION,1,"d".repeat(64)),
                sources,targets,SOURCE,"e".repeat(64),TARGET,fingerprint,100,true,
                root.resolve("preview.json"),"f".repeat(64),
                "{\"preview_path\":\"preview.json\",\"preview_sha256\":\""+"f".repeat(64)+"\"}",json.createObjectNode());
    }
    Setup setup(List<EtfMarketOverviewDailyCache> rows) throws Exception {
        var registry=new DatasetRegistry(List.of(()->EtfMarketOverviewDailyCacheDataset.DEFINITION,
                ()->EtfShareDataset.DEFINITION,()->EtfDailyDataset.DEFINITION,()->EtfBasicDataset.DEFINITION,
                ()->ExchangeCalendarDataset.DEFINITION));
        var request=new WriteGroupRequest("group-batch",DAY,List.of(new WriteGroupRequest.Member(
                "etf-member","etf_market_overview_daily_cache",1,"member-batch",rows.stream().map(mapper::values).toList())));
        var plan=WriteGroupPlan.prepare(request,registry,Map.of("etf_market_overview_daily_cache",TARGET));
        var port=mock(EtfMarketOverviewCacheDelegatedPort.class);
        when(port.requireExactEnvelope(any())).thenAnswer(call->envelope(((EtfMarketOverviewDailyCache)call.getArgument(0)).tradeDate()));
        when(port.preview(any())).thenAnswer(call->envelope(call.getArgument(0)));
        when(port.visibilityTimeout()).thenReturn(Duration.ofMillis(2));
        when(port.walSettled()).thenReturn(true);
        var bound=new AtomicReference<EtfMarketOverviewCachePublicationEnvelope>();
        var stored=new HashMap<EtfMarketOverviewDailyCacheKey,EtfMarketOverviewCachePublicationEnvelope>();
        doAnswer(call->{bound.set(call.getArgument(0));return null;}).when(port).bind(any());
        doAnswer(call->{List<EtfMarketOverviewCachePublicationEnvelope> batch=call.getArgument(0);assertEquals(1,batch.size());stored.put(batch.getFirst().key(),batch.getFirst());return null;}).when(port).send(anyList());
        when(port.readback(anyList())).thenAnswer(call->{List<EtfMarketOverviewDailyCacheKey> keys=call.getArgument(0);return keys.stream().map(stored::get).filter(Objects::nonNull).toList();});
        doAnswer(call->{
            var context=(VerifiedBatchExecutor.Submission)call.getArgument(0);
            var entry=SyncRunLedger.openReadOnly(context.ledgerPath()).get(context.sliceId());
            assertEquals(SyncRunState.SUBMITTED,entry.state());assertEquals(4,entry.revision());
            var evidence=JobDefinitionJson.mapper().readTree(bound.get().responseEvidence());
            Path input=Path.of(evidence.required("prepared_input_path").asText());
            assertTrue(Files.isRegularFile(input));
            String actual=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(input)));
            assertEquals(actual,evidence.required("prepared_input_sha256").asText());
            var payload=JobDefinitionJson.mapper().readTree(entry.payloadJson());
            assertEquals(bound.get().responseEvidence(),payload.required("responseEvidence").asText());
            return null;
        }).when(port).submissionRecorded(any());
        return new Setup(plan,port,new EtfMarketOverviewCachePreparedWriteAdapter(plan,"etf-member",port,root.resolve("input-evidence")),registry);
    }

    @Test void canonicalPreparedDefinitionAdmitsOnlyBoundedIngest() throws Exception {
        var setup=setup(List.of(row(DAY)));var definition=setup.adapter.request().definition();
        assertEquals("write.etf_market_overview_daily_cache",definition.jobId());
        assertEquals(Set.of(SyncJobDefinition.Mode.INGEST),definition.supportedModes());
        assertEquals(31,definition.budget().maxPages());
        assertEquals(DatasetIntervalLock.Scope.allDates("etf_market_overview_daily_cache"),setup.adapter.conflictScope(setup.adapter.request()));
        verifyNoInteractions(setup.port);
    }
    @Test void wrongFiveFieldCallerAssertionFailsBeforeAnyOwnerSend() throws Exception {
        var setup=setup(List.of(new EtfMarketOverviewDailyCache(DAY,999,123456.789,-0.0,VERSION)));
        assertThrows(IllegalArgumentException.class,()->setup.adapter.preflight(setup.adapter.request()));
        verify(setup.port,never()).send(anyList());verify(setup.port,never()).submissionRecorded(any());
    }
    @Test void oldSourceGenerationFailsBeforeSend() throws Exception {
        var setup=setup(List.of(new EtfMarketOverviewDailyCache(DAY,765,123456.789,-0.0,"1".repeat(64))));
        assertThrows(IllegalArgumentException.class,()->setup.adapter.preflight(setup.adapter.request()));
        verify(setup.port,never()).send(anyList());
    }
    @Test void signedZeroCannotBeChangedByCaller() throws Exception {
        var setup=setup(List.of(new EtfMarketOverviewDailyCache(DAY,765,123456.789,0.0,VERSION)));
        assertThrows(IllegalArgumentException.class,()->setup.adapter.preflight(setup.adapter.request()));
        verify(setup.port,never()).send(anyList());
    }
    @Test void missingSourceDayDoesNotInventPreparedCacheOrReceipt() throws Exception {
        var setup=setup(List.of(row(DAY)));doThrow(new IllegalArgumentException("Absent share day")).when(setup.port).requireExactEnvelope(any());
        assertThrows(IllegalArgumentException.class,()->setup.adapter.preflight(setup.adapter.request()));
        verify(setup.port,never()).send(anyList());
    }
    @Test void duplicateDayDifferentGenerationAndWindowOverflowAreRejected() throws Exception {
        assertThrows(IllegalArgumentException.class,()->setup(List.of(row(DAY),new EtfMarketOverviewDailyCache(DAY,765,123456.789,-0.0,"1".repeat(64)))));
        assertThrows(IllegalArgumentException.class,()->setup(List.of(row(DAY),row(DAY.plusDays(31)))));
    }
    @Test void sourceVectorChangeAcrossPreparedRowsStopsAllSubmissions() throws Exception {
        var setup=setup(List.of(row(DAY),row(DAY.plusDays(1))));var second=envelope(DAY.plusDays(1));
        var changed=new EtfMarketOverviewCachePublicationEnvelope(second.tradeDate(),second.sourceVersion(),second.cache(),second.receipt(),
                second.sources(),second.targets(),"9".repeat(64),second.targetsFingerprint(),second.targetId(),second.sourceFingerprint(),second.sourceRows(),
                second.knownSourceDate(),second.previewPath(),second.previewSha256(),second.responseEvidence(),second.previewResponse());
        doReturn(changed).when(setup.port).requireExactEnvelope(argThat(row->row != null && row.tradeDate().equals(DAY.plusDays(1))));
        assertThrows(IllegalStateException.class,()->setup.adapter.preflight(setup.adapter.request()));
        verify(setup.port,never()).send(anyList());
    }
    @Test void realPersistentGroupBindsEveryPublicationToCanonicalInputAndSubmittedAuthority() throws Exception {
        var setup=setup(List.of(row(DAY.plusDays(1)),row(DAY)));
        Path ledger=root.resolve("group.sqlite3");
        var result=new PersistentWriteGroupRunner(ledger,root.resolve("group-evidence"),setup.registry)
                .run("group-real",setup.plan,Map.of("etf-member",setup.adapter),null);
        assertEquals(SyncRunState.VERIFIED,result.state(),result.toString());
        verify(setup.port,times(2)).send(anyList());verify(setup.port,times(2)).submissionRecorded(any());
        try(var files=Files.list(root.resolve("input-evidence"))){
            var input=JobDefinitionJson.mapper().readTree(Files.readAllBytes(files.findFirst().orElseThrow()));
            assertEquals(setup.plan.members().getFirst().batch().fingerprint(),input.required("payloadFingerprint").asText());
            assertEquals(2,input.required("canonicalPayloadLines").size());
            assertEquals(DAY.plusDays(1).toString(),input.required("rows").get(0).required("trade_date").asText());
            assertTrue(input.required("canonicalPayloadLines").get(0).asText().contains("\"total_size_yi\":-0.0"));
        }
    }
}
