package com.zoutrankil.data.derived.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyList;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.zoutrankil.data.calendar.port.ExchangeCalendarReadPort;
import com.zoutrankil.data.derived.port.RetailSentimentDailySession;
import com.zoutrankil.data.domain.DatasetDefinition;
import com.zoutrankil.data.domain.DatasetReadPage;
import com.zoutrankil.data.domain.DatasetReadQuery;
import com.zoutrankil.data.domain.SyncJobDefinition;
import com.zoutrankil.data.domain.derived.RetailSentimentDaily;
import com.zoutrankil.data.domain.derived.RetailSentimentDailySnapshot;
import com.zoutrankil.data.domain.stock.basic.ExchangeCalendar;
import com.zoutrankil.data.domain.stock.basic.ExchangeCalendarDataset;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/** Application coverage preserves the original physical version / typed page / version protocol. */
class RetailSentimentDailyCalendarContractTest {
    private static final LocalDate DAY = LocalDate.of(2026, 9, 17);
    private static final String TARGET = "questdb-" + "a".repeat(64);
    private static final String VERSION = "calendar-fixed";
    private final RetailSentimentDailySession session = mock(RetailSentimentDailySession.class);
    private final ExchangeCalendarReadPort calendar = mock(ExchangeCalendarReadPort.class);
    private final List<String> calls = new ArrayList<>();
    private final RetailSentimentDailySnapshot snapshot =
            new RetailSentimentDailySnapshot(
                    12,
                    "source",
                    8,
                    8,
                    8,
                    true,
                    22,
                    "mv",
                    20,
                    20,
                    20,
                    true,
                    true,
                    true,
                    "definition",
                    "start",
                    "finished",
                    8,
                    8,
                    "DAY",
                    "valid");
    private final RetailSentimentDailyMaterializeAdapter adapter;
    private final SyncJobDefinition.FrozenRequest request;

    RetailSentimentDailyCalendarContractTest() {
        when(session.targetId()).thenReturn(TARGET);
        when(session.snapshot()).thenReturn(snapshot);
        when(session.sourceRawRows(DAY, DAY)).thenReturn(2L);
        when(session.expected(DAY, DAY))
                .thenReturn(
                        List.of(
                                new RetailSentimentDaily(
                                        DAY, 0.5, 1.2, 10.0, -2.0, 0.6, 2L, 1L, 0.2, 1L, 3L, 51.0,
                                        -1.0)));
        when(session.verifiedSnapshot()).thenReturn(snapshot);
        when(session.calendarVersion())
                .thenAnswer(
                        invocation -> {
                            calls.add("version");
                            return VERSION;
                        });
        when(session.newCalendarReader())
                .thenAnswer(
                        invocation -> {
                            calls.add("reader");
                            return calendar;
                        });
        when(calendar.findPage(any()))
                .thenAnswer(
                        invocation -> {
                            calls.add("page");
                            DatasetReadQuery query = invocation.getArgument(0);
                            assertEquals(Map.of("exchange", "SSE"), query.equalities());
                            assertEquals("calendar_date", query.rangeColumn());
                            assertEquals(DAY, query.fromInclusive());
                            assertEquals(DAY.plusDays(1), query.toExclusive());
                            assertEquals(500, query.pageSize());
                            assertNull(query.cursor());
                            assertEquals(
                                    ExchangeCalendarDataset.DEFINITION.columns().stream()
                                            .map(DatasetDefinition.Column::logicalName)
                                            .toList(),
                                    query.columns());
                            return MaterializationCalendarFixture.page(query, List.of(DAY));
                        });
        adapter = new RetailSentimentDailyMaterializeAdapter(session, snapshot);
        request =
                RetailSentimentDailyJobService.definition()
                        .freeze(
                                SyncJobDefinition.Mode.INCREMENTAL,
                                Map.of(
                                        "source_version",
                                        snapshot.sourceVersion(),
                                        "target_id",
                                        TARGET,
                                        "bootstrap_from",
                                        DAY,
                                        "calendar_version",
                                        VERSION),
                                DAY,
                                DAY,
                                DAY.plusDays(1));
    }

    @Test
    void everyExistingVersionCheckRemainsOnTheSameSessionAroundTypedCoverage() throws Exception {
        var completion = adapter.fetch(request, page -> calls.add("consumer"), () -> false);
        assertEquals(
                List.of(
                        "version",
                        "version",
                        "reader",
                        "page",
                        "version",
                        "version",
                        "consumer",
                        "version"),
                calls);
        assertTrue(completion.complete());
        assertSame(snapshot, adapter.lastVerificationSnapshot());
        verify(session, never()).send(anyList());
    }

    @Test
    void innerCalendarDriftRejectsBeforePageDeliveryAndLaterVersionChecks() {
        var versions = new AtomicInteger();
        when(session.calendarVersion())
                .thenAnswer(
                        invocation -> {
                            calls.add("version");
                            return versions.incrementAndGet() == 3 ? "changed" : VERSION;
                        });
        calls.clear();
        var failure =
                assertThrows(
                        IllegalStateException.class,
                        () ->
                                adapter.fetch(
                                        request,
                                        page -> fail("No source page can escape calendar drift"),
                                        () -> false));
        assertEquals(
                "D098 exchange calendar changed during coverage verification",
                failure.getMessage());
        assertEquals(List.of("version", "version", "reader", "page", "version"), calls);
        verify(session, never()).send(anyList());
    }

    @Test
    void incompleteNaturalDayPageFailsBeforeCoverageAfterVersion() {
        doAnswer(
                        invocation -> {
                            calls.add("page");
                            return new DatasetReadPage<ExchangeCalendar>(
                                    "exchange_calendar", 1, null, Instant.EPOCH, List.of(), null);
                        })
                .when(calendar)
                .findPage(any());
        var failure =
                assertThrows(
                        IllegalStateException.class,
                        () ->
                                adapter.fetch(
                                        request,
                                        page -> fail("Incomplete calendar cannot deliver source"),
                                        () -> false));
        assertEquals(
                "Exchange calendar does not cover every requested calendar day",
                failure.getMessage());
        assertEquals(List.of("version", "version", "reader", "page"), calls);
    }
}
