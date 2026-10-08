package com.zoutrankil.data.derived.application;

import com.zoutrankil.data.calendar.port.ExchangeCalendarReadPort;
import com.zoutrankil.data.derived.port.RetailSentimentDailyV1Session;
import com.zoutrankil.data.domain.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Application coverage preserves the original physical version / typed page / version protocol. */
class RetailSentimentDailyV1CalendarContractTest {
    private static final LocalDate DAY = LocalDate.of(2026, 9, 17);
    private static final String TARGET = "questdb-" + "a".repeat(64);
    private static final String VERSION = "calendar-fixed";
    private final RetailSentimentDailyV1Session session = mock(RetailSentimentDailyV1Session.class);
    private final ExchangeCalendarReadPort calendar = mock(ExchangeCalendarReadPort.class);
    private final List<String> calls = new ArrayList<>();
    private final RetailSentimentDailyV1Snapshot snapshot = new RetailSentimentDailyV1Snapshot(12, "source", 8, 8, 8, true,
            22, "mv", 20, 20, 20, true, true, true, "definition", "start", "finished", 8, 8, "DAY", "valid");
    private final RetailSentimentDailyV1MaterializeAdapter adapter;
    private final SyncJobDefinition.FrozenRequest request;

    RetailSentimentDailyV1CalendarContractTest() {
        when(session.targetId()).thenReturn(TARGET);when(session.snapshot()).thenReturn(snapshot);
        when(session.sourceRawRows(DAY, DAY)).thenReturn(2L);
        when(session.expected(DAY, DAY)).thenReturn(List.of(new RetailSentimentDailyV1(DAY, 0.5, 1.2, 10.0, -2.0, 0.6, 2L, 1L, 0.2, 1L, 3L, 51.0, -1.0)));
        when(session.verifiedSnapshot()).thenReturn(snapshot);
        when(session.calendarVersion()).thenAnswer(invocation -> {calls.add("version");return VERSION;});
        when(session.newCalendarReader()).thenAnswer(invocation -> {calls.add("reader");return calendar;});
        when(calendar.findPage(any())).thenAnswer(invocation -> {
            calls.add("page");DatasetReadQuery query = invocation.getArgument(0);
            assertEquals(Map.of("exchange", "SSE"), query.equalities());
            assertEquals("calendar_date", query.rangeColumn());
            assertEquals(DAY, query.fromInclusive());assertEquals(DAY.plusDays(1), query.toExclusive());
            assertEquals(500, query.pageSize());assertNull(query.cursor());
            assertEquals(ExchangeCalendarDataset.DEFINITION.columns().stream().map(DatasetDefinition.Column::logicalName).toList(),query.columns());
            return MaterializationCalendarFixture.page(query, List.of(DAY));
        });
        adapter = new RetailSentimentDailyV1MaterializeAdapter(session, snapshot);
        request = RetailSentimentDailyV1JobService.definition().freeze(SyncJobDefinition.Mode.INCREMENTAL,
                Map.of("source_version", snapshot.sourceVersion(), "target_id", TARGET,
                        "bootstrap_from", DAY, "calendar_version", VERSION),DAY,DAY,DAY.plusDays(1));
    }

    @Test void everyExistingVersionCheckRemainsOnTheSameSessionAroundTypedCoverage() throws Exception {
        var completion = adapter.fetch(request, page -> calls.add("consumer"), () -> false);
        assertEquals(List.of("version", "version", "reader", "page", "version", "version", "consumer", "version"),calls);
        assertTrue(completion.complete());assertSame(snapshot, adapter.lastVerificationSnapshot());
        verify(session, never()).send(anyList());
    }

    @Test void innerCalendarDriftRejectsBeforePageDeliveryAndLaterVersionChecks() {
        var versions = new AtomicInteger();
        when(session.calendarVersion()).thenAnswer(invocation -> {
            calls.add("version");return versions.incrementAndGet() == 3 ? "changed" : VERSION;
        });
        calls.clear();
        var failure = assertThrows(IllegalStateException.class,
                () -> adapter.fetch(request, page -> fail("No source page can escape calendar drift"), () -> false));
        assertEquals("D098 exchange calendar changed during coverage verification", failure.getMessage());
        assertEquals(List.of("version", "version", "reader", "page", "version"), calls);
        verify(session, never()).send(anyList());
    }

    @Test void incompleteNaturalDayPageFailsBeforeCoverageAfterVersion() {
        doAnswer(invocation -> {
            calls.add("page");return new DatasetReadPage<ExchangeCalendar>("exchange_calendar",1,null,Instant.EPOCH,List.of(),null);
        }).when(calendar).findPage(any());
        var failure = assertThrows(IllegalStateException.class,
                () -> adapter.fetch(request, page -> fail("Incomplete calendar cannot deliver source"), () -> false));
        assertEquals("Exchange calendar does not cover every requested calendar day", failure.getMessage());
        assertEquals(List.of("version", "version", "reader", "page"), calls);
    }
}
