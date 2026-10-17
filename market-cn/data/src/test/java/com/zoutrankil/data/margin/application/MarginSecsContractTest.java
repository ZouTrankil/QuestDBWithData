package com.zoutrankil.data.margin.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.zoutrankil.data.calendar.port.ExchangeCalendarReadPort;
import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.margin.port.MarginSecsWriteSession;
import com.zoutrankil.data.service.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MarginSecsContractTest {
    @TempDir Path temp;
    static final LocalDate DAY=LocalDate.of(2026,9,17);
    static final String TARGET="static-v2-"+"a".repeat(64);
    @Test void freezesEverySseNaturalDateInOrderIncludingClosedDays() {
        var calendar=mock(ExchangeCalendarReadPort.class);
        when(calendar.findPage(any())).thenReturn(new DatasetReadPage<>("exchange_calendar",1,null,Instant.EPOCH,
                List.of(new ExchangeCalendar("SSE",DAY.plusDays(1),false,DAY),new ExchangeCalendar("SSE",DAY,true,DAY.minusDays(1))),null));
        var window=new MarginSecsTradingDates(calendar).read(DAY,DAY.plusDays(1));
        assertEquals(List.of(DAY),window.openDates());assertEquals(List.of("20260917:1","20260918:0"),window.encodedCalendarDays());
        assertEquals(List.of(DAY),MarginSecsTradingDates.validateFrozen(DAY,DAY.plusDays(1),window.encodedCalendarDays(),window.fingerprint(),List.of("20260917")));
        var query=org.mockito.ArgumentCaptor.forClass(DatasetReadQuery.class);verify(calendar).findPage(query.capture());
        assertEquals(new DatasetReadQuery(List.of("exchange","calendar_date","is_open","previous_trade_date"),Map.of("exchange","SSE"),"calendar_date",DAY,DAY.plusDays(2),100,null),query.getValue());
        assertThrows(UnsupportedOperationException.class,()->window.openDates().add(DAY));
        assertThrows(IllegalArgumentException.class,()->MarginSecsTradingDates.validateFrozen(DAY,DAY.plusDays(1),List.of("20260917:1","20260918:1"),window.fingerprint(),List.of("20260917")));
        assertThrows(IllegalArgumentException.class,()->MarginSecsTradingDates.validateFrozen(DAY,DAY.plusDays(1),window.encodedCalendarDays(),window.fingerprint(),List.of("20260918")));
    }
    @ParameterizedTest @ValueSource(strings={"missing","duplicate","wrongExchange"})
    void incompleteOrForeignCalendarCannotFreezeASourceWindow(String fault) {
        var calendar=mock(ExchangeCalendarReadPort.class);var first=new ExchangeCalendar("SSE",DAY,true,null);
        var rows=switch(fault){case "missing"->List.of(first);case "duplicate"->List.of(first,first);default->List.of(first,new ExchangeCalendar("SZSE",DAY.plusDays(1),false,DAY));};
        when(calendar.findPage(any())).thenReturn(new DatasetReadPage<>("exchange_calendar",1,null,Instant.EPOCH,rows,null));
        assertThrows(IllegalStateException.class,()->new MarginSecsTradingDates(calendar).read(DAY,DAY.plusDays(1)));
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void beforeKeysAreCheckedBeforeConsumerAndAckReadbackBeforeCompletion(boolean empty)throws Exception {
        var pages=new Pages(empty);var session=mock(MarginSecsWriteSession.class);var calendar=mock(ExchangeCalendarReadPort.class);
        var actual=empty?List.<MarginSecs>of():List.of(row("600000.SH","浦发银行"));
        when(session.readDateBefore(DAY)).thenReturn(actual);when(session.readDate(DAY)).thenReturn(actual);
        var steps=new ArrayList<String>();doAnswer(c->{steps.add("before");return actual;}).when(session).readDateBefore(DAY);
        doAnswer(c->{steps.add("readback");return actual;}).when(session).readDate(DAY);
        var adapter=new MarginSecsSyncAdapter(new MarginSecsSource(pages,temp.resolve("source")),calendar,session,temp);
        var result=adapter.fetch(request(),page->{steps.add("consumer");assertEquals(actual,page.rows());assertFalse(Files.exists(temp.resolve("complete-window.json")));},()->false);
        assertEquals(List.of("before","consumer","readback"),steps);assertEquals(empty?0:1,result.rows());assertTrue(result.complete());assertEquals(1,result.pages());
        var receipt=JobDefinitionJson.mapper().readTree(Path.of(result.evidence()).toFile());
        assertEquals(List.of("20260917:1"),JobDefinitionJson.mapper().convertValue(receipt.path("calendarDays"),List.class));
        assertEquals("2026-09-17",receipt.path("tradeDates").get(0).asText());assertEquals(TARGET,receipt.path("physicalTargetId").asText());
        verifyNoInteractions(calendar);assertEquals(1,pages.calls);
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void existingKeyOmissionEvenAnEmptyResponseCannotReachWrite(boolean empty)throws Exception {
        var session=mock(MarginSecsWriteSession.class);when(session.readDateBefore(DAY)).thenReturn(List.of(row("600001.SH",null)));
        var pages=new Pages(empty);var adapter=new MarginSecsSyncAdapter(new MarginSecsSource(pages,temp.resolve("source")),mock(ExchangeCalendarReadPort.class),session,temp);
        assertEquals("D030 source omitted an existing natural key; removal needs an explicit publication policy",assertThrows(IllegalStateException.class,
                ()->adapter.fetch(request(),p->fail("Omitted keys must not reach consumer"),()->false)).getMessage());
        verify(session,never()).readDate(any());assertFalse(Files.exists(temp.resolve("complete-window.json")));
    }
    @Test void allFieldMismatchAfterAckRetainsSourceButNoCompletion()throws Exception {
        var session=mock(MarginSecsWriteSession.class);when(session.readDateBefore(DAY)).thenReturn(List.of());when(session.readDate(DAY)).thenReturn(List.of(row("600000.SH","changed")));
        var pages=new Pages(false);var consumed=new ArrayList<SyncJobRunner.Page<MarginSecs>>();
        var adapter=new MarginSecsSyncAdapter(new MarginSecsSource(pages,temp.resolve("source")),mock(ExchangeCalendarReadPort.class),session,temp);
        assertEquals("D030 all-field date readback mismatch after ACK",assertThrows(IllegalStateException.class,()->adapter.fetch(request(),consumed::add,()->false)).getMessage());
        assertEquals(1,consumed.size());var page=consumed.getFirst();assertEquals(page,MarginSecsSource.reopen(Path.of(page.responseEvidence()),page.sourceFingerprint(),DAY));
        assertFalse(Files.exists(temp.resolve("complete-window.json")));
    }
    @Test void cancellationStopsBeforeSourceAndSessionAndDamagedReceiptCannotReopen()throws Exception {
        var session=mock(MarginSecsWriteSession.class);var pages=new Pages(false);
        var adapter=new MarginSecsSyncAdapter(new MarginSecsSource(pages,temp.resolve("source")),mock(ExchangeCalendarReadPort.class),session,temp);
        assertThrows(CancellationException.class,()->adapter.fetch(request(),p->fail(),()->true));assertEquals(0,pages.calls);verifyNoInteractions(session);
        var source=new MarginSecsSource(pages,temp.resolve("raw"));var page=source.fetch(DAY,()->false);
        assertEquals(page,MarginSecsSource.reopen(Path.of(page.responseEvidence()),page.sourceFingerprint(),DAY));
        Files.writeString(Path.of(page.responseEvidence()),"\n",StandardOpenOption.APPEND);
        assertThrows(IllegalStateException.class,()->MarginSecsSource.reopen(Path.of(page.responseEvidence()),page.sourceFingerprint(),DAY));
    }
    static SyncJobDefinition.FrozenRequest request()throws Exception {
        String fingerprint=com.zoutrankil.data.repository.FileEvidenceStore.sha256("20260917:1".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return MarginSecsSyncJobOwner.DEFINITION.freeze(SyncJobDefinition.Mode.INCREMENTAL,Map.of("targetId",TARGET,"physicalTargetId",TARGET,
                "targetRowsBefore",0,"targetFingerprint","b".repeat(64),"checkpointAnchor",DAY,"calendarDays","20260917:1","tradeDates","20260917","calendarFingerprint",fingerprint),DAY,DAY,DAY);
    }
    static MarginSecs row(String code,String name){return new MarginSecs(new MarginSecsKey(DAY,code),name,"SSE");}
    static final class Pages extends TusharePageService {
        final boolean empty;int calls;Pages(boolean empty){super(null);this.empty=empty;}
        @Override public PageExecutor.Fetcher fetcher(PageContract contract,BooleanSupplier cancelled){return params->{calls++;assertEquals(Map.of("trade_date","20260917"),params);
            var json=JobDefinitionJson.mapper();var row=new LinkedHashMap<String,JsonNode>();row.put("trade_date",json.valueToTree("20260917"));row.put("ts_code",json.valueToTree("600000.SH"));row.put("name",json.valueToTree("浦发银行"));row.put("exchange",json.valueToTree("SSE"));
            return new PageExecutor.Page(empty?List.of():List.of(row),null,false,null);};}
    }
}
