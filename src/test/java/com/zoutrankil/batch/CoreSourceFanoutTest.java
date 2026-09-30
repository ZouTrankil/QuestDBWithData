package com.zoutrankil.batch;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CoreSourceFanoutTest {
    @TempDir Path archive;
    private static final LocalDate DATE=LocalDate.of(2026,9,28);
    private static final Instant AT=Instant.parse("2026-09-28T12:00:00Z");

    @Test void planMustContainExactlyAllCoreTablesAndTheCalendar() {
        var sources=requiredSources();
        assertEquals(PostCloseGraph.CORE_TABLES.size()+1,sources.size());
        assertEquals(PostCloseSourcePlan.REQUIRED,sources.stream().map(PostCloseSourcePlan.Source::dataset).collect(java.util.stream.Collectors.toSet()));
        assertThrows(IllegalArgumentException.class,()->new PostCloseSourcePlan.Manifest(1,DATE,"calendar-v1","Asia/Shanghai",sources.subList(1,sources.size())));
    }

    @Test void sourceDefinitionVersionIsPinnedByTheFrozenPlan() {
        assertThrows(IllegalArgumentException.class,()->new PostCloseSourcePlan.Source("daily","daily-v999",Set.of("000001.SZ"),
                "stocks-v1","0",null,null));
    }

    @Test void fanoutLaunchesEveryFrozenSourceOnlyAfterHashAndScopeValidation() throws Exception {
        var request=writePlan();
        var ledger=mock(SqliteLedger.class);when(ledger.state(anyString())).thenReturn(BusinessState.VERIFIED);
        var sources=mock(NativeSourceService.class);
        when(sources.collect(anyString(),any())).thenAnswer(call->{
            var source=(SourceCollector.Request)call.getArgument(1);
            var collected=new SourceCollector.Collected(RunRequest.hash(source.dataset(),"fixture"),"fixture://"+source.dataset(),
                    1,1,true,RunRequest.hash(source.dataset(),source.universeVersion(),String.join("\n",new TreeSet<>(source.expectedCodes()))),BusinessState.VERIFYING);
            return Map.of("state","VERIFYING","result_json",Json.write(collected));
        });
        var launched=new ArrayList<RunRequest>();
        var result=new CoreSourceFanout(ledger,sources,archive).execute(request,child->{launched.add(child);return Map.of("disposition","fixture");});
        assertTrue(result.ready(),result.reason());
        assertEquals(PostCloseSourcePlan.REQUIRED,launched.stream().map(r->r.job().substring("source_".length())).collect(java.util.stream.Collectors.toSet()));
        assertEquals(PostCloseSourcePlan.REQUIRED.size(),launched.size());
        assertTrue(launched.stream().allMatch(child->child.logicalDate().equals(DATE)&&child.calendarVersion().equals("calendar-v1")));
        verify(sources,times(PostCloseSourcePlan.REQUIRED.size())).collect(anyString(),any());
    }

    @Test void missingOrChangedPlanBlocksBeforeAnySourceProbe() throws Exception {
        var request=writePlan();
        String changedFingerprint=RunRequest.hash("wrong-plan");
        Path changedPlan=archive.resolve("post-close-plans").resolve(changedFingerprint+".json");
        Files.copy(archive.resolve("post-close-plans").resolve(request.inputFingerprint()+".json"),changedPlan);
        var wrong=new RunRequest(request.requestId(),request.job(),request.logicalDate(),request.rangeStart(),request.rangeEnd(),
                request.definitionVersion(),request.revision(),request.supersedes(),request.revisionReason(),changedFingerprint,
                request.calendarVersion(),request.zone(),request.scheduledAt(),request.triggeredAt(),changedFingerprint);
        var ledger=mock(SqliteLedger.class);var sources=mock(NativeSourceService.class);
        var result=new CoreSourceFanout(ledger,sources,archive).execute(wrong,child->fail("No child job should start"));
        assertFalse(result.ready());assertEquals(BusinessState.BLOCKED,result.state());
        assertTrue(result.reason().contains("fingerprint"));verifyNoInteractions(sources);
    }

    @Test void newTriggerRetriesReadProbesWithoutChangingBusinessInstanceIdentity() throws Exception {
        var first=writePlan();var second=new RunRequest("post-close-retry",first.job(),first.logicalDate(),first.rangeStart(),first.rangeEnd(),
                first.definitionVersion(),first.revision(),first.supersedes(),first.revisionReason(),first.inputFingerprint(),
                first.calendarVersion(),first.zone(),first.scheduledAt(),first.triggeredAt(),first.scopeIdentity());
        assertEquals(first.instanceId(),second.instanceId());
        var ledger=mock(SqliteLedger.class);when(ledger.state(anyString())).thenReturn(BusinessState.VERIFIED);
        var sources=mock(NativeSourceService.class);
        when(sources.collect(anyString(),any())).thenAnswer(call->{
            var source=(SourceCollector.Request)call.getArgument(1);
            var collected=new SourceCollector.Collected(RunRequest.hash(source.dataset(),"fixture"),"fixture://"+source.dataset(),1,1,true,
                    RunRequest.hash(source.dataset(),source.universeVersion(),String.join("\n",new TreeSet<>(source.expectedCodes()))),BusinessState.VERIFYING);
            return Map.of("state","VERIFYING","result_json",Json.write(collected));
        });
        var keys=new ArrayList<String>();var childRequests=new ArrayList<RunRequest>();
        var fanout=new CoreSourceFanout(ledger,sources,archive);
        assertTrue(fanout.execute(first,child->{keys.add("first:"+child.job());childRequests.add(child);return Map.of();}).ready());
        assertTrue(fanout.execute(first,child->{keys.add("duplicate:"+child.job());childRequests.add(child);return Map.of();}).ready());
        assertTrue(fanout.execute(second,child->{keys.add("second:"+child.job());childRequests.add(child);return Map.of();}).ready());
        var captured=org.mockito.ArgumentCaptor.forClass(String.class);
        verify(sources,times(PostCloseSourcePlan.REQUIRED.size()*3)).collect(captured.capture(),any());
        var idempotencyKeys=captured.getAllValues();
        assertEquals(PostCloseSourcePlan.REQUIRED.size()*2,new HashSet<>(idempotencyKeys).size());
        assertEquals(PostCloseSourcePlan.REQUIRED.size()*3,keys.size());
        assertEquals(PostCloseSourcePlan.REQUIRED.size()*2,childRequests.stream().map(RunRequest::requestId).distinct().count());
        assertEquals(PostCloseSourcePlan.REQUIRED.size(),childRequests.stream().map(RunRequest::instanceId).distinct().count());
    }

    private RunRequest writePlan() throws Exception {
        var manifest=new PostCloseSourcePlan.Manifest(1,DATE,"calendar-v1","Asia/Shanghai",requiredSources());
        byte[] bytes=Json.write(manifest).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String fingerprint=PostCloseSourcePlan.fingerprint(bytes);
        Path plan=archive.resolve("post-close-plans").resolve(fingerprint+".json");
        Files.createDirectories(plan.getParent());Files.write(plan,bytes);
        return new RunRequest("post-close-fixture","post_close",DATE,DATE,DATE,"post-close-v1","0",null,null,
                fingerprint,"calendar-v1","Asia/Shanghai",AT,AT,fingerprint);
    }

    private static List<PostCloseSourcePlan.Source> requiredSources() {
        return PostCloseSourcePlan.REQUIRED.stream().map(dataset->{
            var contract=SourceContract.load(dataset);
            Set<String> codes=contract.isMarketAggregate()?Set.of():dataset.equals("exchange_calendar")?Set.of("SSE","SZSE"):
                    dataset.equals("etf_basic")?Set.of("E"):dataset.equals("etf_portfolio")?Set.of("510300.SH"):Set.of("000001.SZ");
            return new PostCloseSourcePlan.Source(dataset,contract.version(),codes,"universe-v1","0",null,null);
        }).toList();
    }
}
