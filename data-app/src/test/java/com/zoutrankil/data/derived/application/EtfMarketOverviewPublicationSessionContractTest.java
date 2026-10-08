package com.zoutrankil.data.derived.application;

import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.derived.domain.*;
import com.zoutrankil.data.derived.port.*;
import com.zoutrankil.data.service.VerifiedBatchExecutor;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static com.zoutrankil.data.derived.application.EtfMarketOverviewCacheTestFixtures.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class EtfMarketOverviewPublicationSessionContractTest {
    @TempDir Path temporary;

    private EtfMarketOverviewCachePublicationEnvelope row() throws Exception {
        return envelope(temporary.resolve("preview.json"), true, true);
    }

    private VerifiedBatchExecutor.Submission submission(String slice) {
        return new VerifiedBatchExecutor.Submission(temporary.resolve("ledger.sqlite"), "run", slice, 4, UNIT);
    }

    @Test void constructionAndBindingNeverCallSourcePublisherOrPhysicalTarget() throws Exception {
        var source = mock(EtfMarketOverviewSource.class);
        var publisher = mock(EtfMarketOverviewPublisher.class);
        var target = mock(EtfMarketOverviewPublicationTarget.class);
        var row = row();
        var session = new DefaultEtfMarketOverviewPublicationSession(source, publisher, target, row);
        session.bind(row);
        assertFalse(session.unresolved());
        assertFalse(session.uncertainSenderStopped());
        verifyNoInteractions(source, publisher, target);
    }

    @Test void cancelledAttemptIsConsumedBeforeTargetIoAndCannotBeResetByBind() throws Exception {
        var source = mock(EtfMarketOverviewSource.class);
        var publisher = mock(EtfMarketOverviewPublisher.class);
        var target = mock(EtfMarketOverviewPublicationTarget.class);
        var row = row();
        var session = new DefaultEtfMarketOverviewPublicationSession(source, publisher, target, row);
        session.submissionRecorded(submission("slice"));
        session.cancellationProbe(() -> true);
        assertEquals("Cancelled before owner invocation", assertThrows(IllegalStateException.class,
                () -> session.send(List.of(row))).getMessage());
        assertFalse(session.unresolved());
        session.bind(row);
        session.cancellationProbe(() -> false);
        session.submissionRecorded(submission("slice"));
        assertEquals("Publication slice already attempted; reconcile without resend", assertThrows(
                IllegalStateException.class, () -> session.send(List.of(row))).getMessage());
        verifyNoInteractions(source, target);
        verify(publisher, never()).publish(any(), any(), any());
    }

    @Test void failedPhysicalPreflightAlsoConsumesAttemptWithoutInventingUnknownDelivery() throws Exception {
        var source = mock(EtfMarketOverviewSource.class);
        var publisher = mock(EtfMarketOverviewPublisher.class);
        var target = mock(EtfMarketOverviewPublicationTarget.class);
        var row = row();
        var session = new DefaultEtfMarketOverviewPublicationSession(source, publisher, target, row);
        var failure = new IOException("physical target changed");
        doThrow(failure).when(target).preflight(row);
        session.submissionRecorded(submission("slice"));
        assertSame(failure, assertThrows(IOException.class, () -> session.send(List.of(row))));
        assertFalse(session.unresolved());
        session.bind(row);
        session.submissionRecorded(submission("slice"));
        assertThrows(IllegalStateException.class, () -> session.send(List.of(row)));
        verify(target, times(1)).preflight(row);
        verify(publisher, never()).publish(any(), any(), any());
    }

    @Test void twoSessionsShareDependenciesButNeverAttemptCancellationOrUnknownState() throws Exception {
        var source = mock(EtfMarketOverviewSource.class);
        var publisher = mock(EtfMarketOverviewPublisher.class);
        var target = mock(EtfMarketOverviewPublicationTarget.class);
        var row = row();
        EtfMarketOverviewSessions sessions = initial ->
                new DefaultEtfMarketOverviewPublicationSession(source, publisher, target, initial);
        var first = sessions.open(row);
        var second = sessions.open(row);
        assertNotSame(first, second);
        first.cancellationProbe(() -> true);
        first.submissionRecorded(submission("first"));
        assertThrows(IllegalStateException.class, () -> first.send(List.of(row)));
        second.submissionRecorded(submission("second"));
        when(publisher.publish(eq(row), eq(submission("second")), any())).thenThrow(new IOException("ACK lost"));
        assertThrows(IOException.class, () -> second.send(List.of(row)));
        assertFalse(first.unresolved());
        assertTrue(second.unresolved());
        first.bind(row);
        second.bind(row);
        assertFalse(first.unresolved());
        assertTrue(second.unresolved());
        assertThrows(IllegalStateException.class, () -> second.submissionRecorded(submission("third")));
        verify(publisher, times(1)).publish(eq(row), eq(submission("second")), any());
    }

    @Test void hookThenPreflightThenPublicationPreservesUnknownBoundaryAndKnownStop() throws Exception {
        var source = mock(EtfMarketOverviewSource.class);
        var publisher = mock(EtfMarketOverviewPublisher.class);
        var target = mock(EtfMarketOverviewPublicationTarget.class);
        var row = row();
        var session = new DefaultEtfMarketOverviewPublicationSession(source, publisher, target, row);
        var submission = submission("slice");
        when(publisher.publish(eq(row), eq(submission), any())).thenAnswer(call -> {
            assertTrue(session.unresolved());
            assertFalse(session.uncertainSenderStopped());
            return new EtfMarketOverviewOwnerResult(null, null, null, SHA, true, 1, 0, "VERIFIED_READTHROUGH");
        });
        session.submissionRecorded(submission);
        session.send(List.of(row));
        var order = inOrder(publisher, target);
        order.verify(publisher).validateSubmission(row, submission);
        order.verify(target).preflight(row);
        order.verify(publisher).publish(eq(row), eq(submission), any());
        assertFalse(session.unresolved());
        assertTrue(session.uncertainSenderStopped());
        session.bind(row);
        assertFalse(session.uncertainSenderStopped());
        session.submissionRecorded(submission);
        assertThrows(IllegalStateException.class, () -> session.send(List.of(row)));
    }

    @Test void reconciliationContextSurvivesBindWithoutClearingUnknownOrAffectingOtherSession() throws Exception {
        var source = mock(EtfMarketOverviewSource.class);
        var publisher = mock(EtfMarketOverviewPublisher.class);
        var target = mock(EtfMarketOverviewPublicationTarget.class);
        var row = row();
        var first = new DefaultEtfMarketOverviewPublicationSession(source, publisher, target, row);
        var other = new DefaultEtfMarketOverviewPublicationSession(source, publisher, target, row);
        when(publisher.publish(any(), any(), any())).thenThrow(new IOException("unknown"));
        first.submissionRecorded(submission("slice"));
        assertThrows(IOException.class, () -> first.send(List.of(row)));
        Path ledger = temporary.resolve("ledger.sqlite").toAbsolutePath().normalize();
        first.reconciliationContext(ledger, "run");
        first.bind(row);
        when(publisher.writerStopped(ledger, "run")).thenReturn(true, false);
        assertTrue(first.uncertainSenderStopped());
        assertFalse(first.uncertainSenderStopped());
        assertTrue(first.unresolved());
        assertFalse(other.uncertainSenderStopped());
        verify(publisher, times(2)).writerStopped(ledger, "run");
    }

    @Test void completeKeyIsRejectedBeforeTargetAndKnownEmptyKeepsOnePublicationUnit() throws Exception {
        var source = mock(EtfMarketOverviewSource.class);
        var publisher = mock(EtfMarketOverviewPublisher.class);
        var target = mock(EtfMarketOverviewPublicationTarget.class);
        var empty = envelope(temporary.resolve("empty-preview.json"), true, false);
        var session = new DefaultEtfMarketOverviewPublicationSession(source, publisher, target, empty);
        assertThrows(IllegalArgumentException.class, () -> session.readback(
                List.of(new EtfMarketOverviewDailyCacheKey(DAY, "f".repeat(64)))));
        verifyNoInteractions(target);
        when(target.readback(empty, empty.key())).thenReturn(
                new EtfMarketOverviewObservedPublication(List.of(), List.of(empty.receipt())));
        assertEquals(List.of(empty), session.readback(List.of(empty.key())));
        assertEquals(0, empty.receipt().rowCount());
    }
}
