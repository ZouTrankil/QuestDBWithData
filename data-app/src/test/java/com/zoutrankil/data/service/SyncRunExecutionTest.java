package com.zoutrankil.data.service;

import com.zoutrankil.data.domain.SyncJobDefinition;
import com.zoutrankil.data.domain.SyncRunState;
import com.zoutrankil.data.etf.application.EtfDailySyncJobOwner;
import com.zoutrankil.data.repository.SyncRunLedger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import java.io.IOException;
import java.nio.file.*;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.*;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@SuppressWarnings({"rawtypes", "unchecked"})
class SyncRunExecutionTest {
    @TempDir Path temp;

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void preservesLedgerFactoryLocksRunnerOrderAndRunOrResumeArguments(boolean resume) throws Exception {
        var path = temp.resolve("run.sqlite");
        var request = request();
        var adapter = (SyncJobRunner.Adapter<String,String>)mock(SyncJobRunner.Adapter.class);
        var expected = new SyncJobRunner.Result("run-1", SyncRunState.VERIFIED_EMPTY, 0, 0, null);
        var order = new ArrayList<String>();
        try (var ledgers = mockConstruction(SyncRunLedger.class, (ledger, context) -> {
                 order.add("ledger"); assertEquals(List.of(path), context.arguments());
             });
             var locks = mockConstruction(DatasetIntervalLock.class, (lock, context) -> {
                 order.add("locks"); assertEquals(List.of(path), context.arguments());
             });
             var runners = mockConstruction(SyncJobRunner.class, (runner, context) -> {
                 order.add("runner");
                 assertSame(ledgers.constructed().getFirst(), context.arguments().get(0));
                 assertSame(locks.constructed().getFirst(), context.arguments().get(1));
                 when(runner.run(anyString(), isNull(), anyString(), any(), any(), any())).thenReturn(expected);
                 when(runner.resume(anyString(), anyString(), anyString(), anyString(), any(), any(), any())).thenReturn(expected);
             })) {
            var result = SyncRunExecution.execute(path, "run-1", resume ? "prior-1" : null, "target-1", request,
                    "Cannot read ETF cancellation state", () -> { order.add("factory"); return adapter; });
            assertSame(expected, result);
            assertEquals(List.of("ledger", "factory", "locks", "runner"), order);
            var stopped = ArgumentCaptor.forClass(BooleanSupplier.class);
            var runner = runners.constructed().getFirst();
            if (resume) {
                verify(runner).resume(eq("run-1"), eq("prior-1"), eq("prior-1"), eq("target-1"),
                        same(request), same(adapter), stopped.capture());
                verify(runner, never()).run(anyString(), any(), anyString(), any(), any(), any());
            } else {
                verify(runner).run(eq("run-1"), isNull(), eq("target-1"), same(request), same(adapter), stopped.capture());
                verify(runner, never()).resume(anyString(), anyString(), anyString(), anyString(), any(), any(), any());
            }
            var ledger = ledgers.constructed().getFirst();
            assertFalse(stopped.getValue().getAsBoolean());
            when(ledger.cancellationRequested("run-1")).thenReturn(true);
            assertTrue(stopped.getValue().getAsBoolean());
            verify(ledger, times(2)).cancellationRequested("run-1");
        }
        assertFalse(Files.exists(path));
    }

    @Test void everyExecutionOpensItsOwnLedgerLocksRunnerAndAdapter() throws Exception {
        var adapters = new ArrayList<SyncJobRunner.Adapter<String,String>>();
        try (var ledgers = mockConstruction(SyncRunLedger.class);
             var locks = mockConstruction(DatasetIntervalLock.class);
             var runners = mockConstruction(SyncJobRunner.class)) {
            SyncRunExecution.AdapterFactory<String,String> factory = () -> {
                var adapter = (SyncJobRunner.Adapter<String,String>)mock(SyncJobRunner.Adapter.class);
                adapters.add(adapter); return adapter;
            };
            SyncRunExecution.execute(temp.resolve("run.sqlite"), "first", null, "target-1", request(), "cancel", factory);
            SyncRunExecution.execute(temp.resolve("run.sqlite"), "second", "first", "target-1", request(), "cancel", factory);
            assertEquals(2, ledgers.constructed().size());
            assertEquals(2, locks.constructed().size());
            assertEquals(2, runners.constructed().size());
            assertEquals(2, adapters.size());
            assertNotSame(adapters.getFirst(), adapters.getLast());
            verify(runners.constructed().getFirst()).run(eq("first"), isNull(), eq("target-1"), any(), same(adapters.getFirst()), any());
            verify(runners.constructed().getLast()).resume(eq("second"), eq("first"), eq("first"), eq("target-1"), any(), same(adapters.getLast()), any());
        }
    }

    @Test void adapterFactoryFailurePropagatesBeforeLocksOrRunnerAreCreated() throws Exception {
        var failure = new IOException("factory failed");
        try (var ledgers = mockConstruction(SyncRunLedger.class);
             var locks = mockConstruction(DatasetIntervalLock.class);
             var runners = mockConstruction(SyncJobRunner.class)) {
            var thrown = assertThrows(IOException.class, () -> SyncRunExecution.execute(temp.resolve("run.sqlite"),
                    "run-1", null, "target-1", request(), "cancel", () -> { throw failure; }));
            assertSame(failure, thrown);
            assertEquals(1, ledgers.constructed().size());
            assertTrue(locks.constructed().isEmpty());
            assertTrue(runners.constructed().isEmpty());
        }
    }

    @Test void cancellationLookupFailureKeepsTheFamilyMessageAndSqlCause() throws Exception {
        var sql = new SQLException("lookup failed");
        try (var ledgers = mockConstruction(SyncRunLedger.class, (ledger, context) ->
                     when(ledger.cancellationRequested("run-1")).thenThrow(sql));
             var locks = mockConstruction(DatasetIntervalLock.class);
             var runners = mockConstruction(SyncJobRunner.class)) {
            SyncRunExecution.execute(temp.resolve("run.sqlite"), "run-1", null, "target-1", request(),
                    "Cannot read etf_factor cancellation state", () -> mock(SyncJobRunner.Adapter.class));
            var stopped = ArgumentCaptor.forClass(BooleanSupplier.class);
            verify(runners.constructed().getFirst()).run(anyString(), isNull(), anyString(), any(), any(), stopped.capture());
            var failure = assertThrows(IllegalStateException.class, () -> stopped.getValue().getAsBoolean());
            assertEquals("Cannot read etf_factor cancellation state", failure.getMessage());
            assertSame(sql, failure.getCause());
        }
    }

    @Test void interruptionShortCircuitsLedgerCancellationLookupAndRetainsTheFlag() throws Exception {
        assertFalse(Thread.currentThread().isInterrupted());
        try (var ledgers = mockConstruction(SyncRunLedger.class);
             var locks = mockConstruction(DatasetIntervalLock.class);
             var runners = mockConstruction(SyncJobRunner.class)) {
            SyncRunExecution.execute(temp.resolve("run.sqlite"), "run-1", null, "target-1", request(), "cancel",
                    () -> mock(SyncJobRunner.Adapter.class));
            var stopped = ArgumentCaptor.forClass(BooleanSupplier.class);
            verify(runners.constructed().getFirst()).run(anyString(), isNull(), anyString(), any(), any(), stopped.capture());
            try {
                Thread.currentThread().interrupt();
                assertTrue(stopped.getValue().getAsBoolean());
                assertTrue(Thread.currentThread().isInterrupted());
            } finally { Thread.interrupted(); }
            verify(ledgers.constructed().getFirst(), never()).cancellationRequested(anyString());
        }
    }

    private static SyncJobDefinition.FrozenRequest request() {
        var date = LocalDate.of(2026, 9, 28);
        return EtfDailySyncJobOwner.DEFINITION.freeze(SyncJobDefinition.Mode.BACKFILL,
                Map.of("targetId", "target-1", "trade_dates", "20260928"), date, date, date);
    }
}
