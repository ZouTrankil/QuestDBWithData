package com.zoutrankil.data.stock.application;

import com.zoutrankil.data.config.QuestDbProperties;
import com.zoutrankil.data.domain.StockBasic;
import com.zoutrankil.data.domain.StockBasicSnapshot;
import com.zoutrankil.data.domain.StockBasicSnapshotKey;
import com.zoutrankil.data.domain.temporal.BusinessTime;
import com.zoutrankil.data.service.VerifiedBatchExecutor;
import com.zoutrankil.data.stock.port.StockBasicTarget;
import com.zoutrankil.data.stock.storage.StockBasicWritePort;
import com.zoutrankil.data.sync.port.VerifiedWriteSession;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class StockBasicWriteServicePortContractTest {
    private static final Instant SNAPSHOT = Instant.parse("2020-05-03T16:00:00Z");
    private static final StockBasic STOCK = new StockBasic("000001.SZ", "000001", "name", null, "bank", LocalDate.of(1991, 4, 3));

    @Test void eachCallCreatesItsOwnConfiguredWriterAndPreservesSnapshotValues() throws Exception {
        var target = mock(StockBasicTarget.class); var time = mock(BusinessTime.class); var properties = properties();
        var sessions = new ArrayList<VerifiedWriteSession<StockBasicSnapshot, StockBasicSnapshotKey>>();
        var submitted = new ArrayList<List<StockBasicSnapshot>>();
        when(target.newConfiguredWriter(8192, Duration.ofSeconds(3))).thenAnswer(invocation -> {
            var session = session(); var sent = new AtomicReference<List<StockBasicSnapshot>>();
            doAnswer(call -> { var rows = List.copyOf(call.<List<StockBasicSnapshot>>getArgument(0)); submitted.add(rows); sent.set(rows); return null; })
                    .when(session).send(anyList());
            when(session.readback(anyList())).thenAnswer(call -> sent.get()); when(session.walSettled()).thenReturn(true);
            sessions.add(session); return session;
        });
        var service = new StockBasicWriteService(target, properties, time);
        for (int i = 0; i < 2; i++) {
            var result = service.writeDetailed(List.of(STOCK), SNAPSHOT);
            assertEquals(VerifiedBatchExecutor.Status.VERIFIED, result.status()); assertEquals(1, result.verifiedRows());
        }
        assertEquals(List.of(List.of(new StockBasicSnapshot(SNAPSHOT, STOCK)), List.of(new StockBasicSnapshot(SNAPSHOT, STOCK))), submitted);
        assertNotSame(sessions.get(0), sessions.get(1));
        for (var session : sessions) {
            var order = inOrder(session); order.verify(session).codec(); order.verify(session).preflight();
            order.verify(session).send(List.of(new StockBasicSnapshot(SNAPSHOT, STOCK)));
            order.verify(session).readback(List.of(new StockBasicSnapshot(SNAPSHOT, STOCK).key())); order.verify(session).walSettled();
            verifyNoMoreInteractions(session);
        }
        verify(target, times(2)).newConfiguredWriter(8192, Duration.ofSeconds(3)); verifyNoMoreInteractions(target); verifyNoInteractions(time);
    }

    @Test void snapshotMarkerAndApplicationReportRemainOwnedByTheService() throws Exception {
        var target = mock(StockBasicTarget.class); var time = mock(BusinessTime.class); var session = session();
        when(time.todaySnapshotMarker()).thenReturn(SNAPSHOT);
        when(target.newConfiguredWriter(8192, Duration.ofSeconds(3))).thenReturn(session);
        when(session.readback(anyList())).thenReturn(List.of(new StockBasicSnapshot(SNAPSHOT, STOCK)));
        when(session.walSettled()).thenReturn(true);
        var report = new StockBasicWriteService(target, properties(), time).storeAndVerify(List.of(STOCK));
        assertEquals(1, report.submittedRows()); assertEquals(1, report.visibleRows()); assertEquals(SNAPSHOT, report.snapshotTimestamp());
        verify(time).todaySnapshotMarker(); verify(session).send(List.of(new StockBasicSnapshot(SNAPSHOT, STOCK)));
    }

    @Test void preflightFailureReturnsPartialAndStoreAndVerifyRejectsItWithoutSending() throws Exception {
        var target = mock(StockBasicTarget.class); var time = mock(BusinessTime.class); var session = session();
        when(target.newConfiguredWriter(8192, Duration.ofSeconds(3))).thenReturn(session);
        when(time.todaySnapshotMarker()).thenReturn(SNAPSHOT); doThrow(new IllegalStateException("schema mismatch")).when(session).preflight();
        var service = new StockBasicWriteService(target, properties(), time);
        var partial = service.writeDetailed(List.of(STOCK), SNAPSHOT);
        assertEquals(VerifiedBatchExecutor.Status.PARTIAL, partial.status()); assertEquals(0, partial.submittedRows());
        assertTrue(assertThrows(IllegalStateException.class, () -> service.storeAndVerify(List.of(STOCK))).getMessage().contains("QuestDB batch not verified: PARTIAL"));
        verify(session, never()).send(anyList()); verify(session, never()).readback(anyList()); verify(session, never()).walSettled();
    }

    @Test void emptySourceHasNoPreflightOrSendAndInvalidPolicyHasNoWriterConstruction() throws Exception {
        var target = mock(StockBasicTarget.class); var time = mock(BusinessTime.class); var session = session(); var properties = properties();
        when(target.newConfiguredWriter(8192, Duration.ofSeconds(3))).thenReturn(session);
        var service = new StockBasicWriteService(target, properties, time);
        assertEquals(VerifiedBatchExecutor.Status.EMPTY, service.writeDetailed(List.of(), SNAPSHOT).status());
        verify(session).codec(); verifyNoMoreInteractions(session); verifyNoInteractions(time);
        clearInvocations(target); properties.setWriteBatchRows(0);
        assertThrows(IllegalArgumentException.class, () -> service.writeDetailed(List.of(STOCK), SNAPSHOT));
        verifyNoInteractions(target);
    }

    @Test void connectionProbeDelegatesToTargetWithoutCreatingASessionOrSnapshot() {
        var target = mock(StockBasicTarget.class); var time = mock(BusinessTime.class);
        new StockBasicWriteService(target, properties(), time).verifyConnection();
        verify(target).verifyConnection(); verifyNoMoreInteractions(target); verifyNoInteractions(time);
    }

    @SuppressWarnings("unchecked")
    private static VerifiedWriteSession<StockBasicSnapshot, StockBasicSnapshotKey> session() {
        var session = (VerifiedWriteSession<StockBasicSnapshot, StockBasicSnapshotKey>) mock(VerifiedWriteSession.class);
        when(session.codec()).thenReturn(StockBasicWritePort.CODEC); return session;
    }
    private static QuestDbProperties properties() {
        var properties = new QuestDbProperties(); properties.setWriteBatchBytes(8192);
        properties.setVisibilityTimeout(Duration.ofSeconds(3)); properties.setPollInterval(Duration.ofMillis(1)); return properties;
    }
}
