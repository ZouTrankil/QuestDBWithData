package com.zoutrankil.data.l2live.bootstrap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.anyList;
import static org.mockito.Mockito.anyMap;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.zoutrankil.data.l2live.domain.AliyunL2WireParser;
import com.zoutrankil.data.l2live.domain.L2CleanEvent;
import com.zoutrankil.data.l2live.domain.L2MinuteAccumulator;
import com.zoutrankil.data.l2live.domain.L2MinuteState;
import com.zoutrankil.data.l2live.domain.RawCapture;
import com.zoutrankil.data.l2live.storage.LocalLiveSink;
import com.zoutrankil.data.l2live.storage.RawJournal;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

class L2LiveProcessorTest {
    @Test
    void slightlyFutureRawRemainsPendingUntilRealClockAllowsIt() throws Exception {
        var journal = mock(RawJournal.class);
        var sink = mock(LocalLiveSink.class);
        var result = new AtomicReference<RawJournal.StoredBatch>();
        RawCapture base = capture();
        RawCapture raw =
                new RawCapture(
                        base.captureId(),
                        base.sequence(),
                        base.sessionId(),
                        base.topic(),
                        base.payloadBase64(),
                        base.recvTs().minusSeconds(2),
                        base.captureDate(),
                        0,
                        false,
                        false);
        stubNewBatch(journal, List.of(raw), result);
        var time = new AtomicReference<>(raw.recvTs());
        Clock clock = mock(Clock.class);
        when(clock.instant()).thenAnswer(call -> time.get());
        var accumulator = new L2MinuteAccumulator(Duration.ofSeconds(2), 10, clock);
        var processor =
                new L2LiveProcessor(
                        journal, sink, new AliyunL2WireParser(), accumulator, 100, Set.of(), clock);
        var before = accumulator.snapshot();
        assertEquals(0, processor.processOnce());
        assertEquals(before, accumulator.snapshot());
        assertNull(result.get());
        verifyNoInteractions(sink);
        time.set(raw.recvTs().plusSeconds(2).plusMillis(10));
        assertEquals(1, processor.processOnce());
        assertEquals(1, result.get().states().size());
        assertEquals(time.get(), result.get().states().getFirst().availableAt());
        verify(journal, times(1)).prepareBatch(anyList(), anyList(), anyMap());
    }

    @Test
    void implausibleFutureTimeStillFailsTheOriginalGateWithoutStallingTheJournal()
            throws Exception {
        var journal = mock(RawJournal.class);
        var sink = mock(LocalLiveSink.class);
        var result = new AtomicReference<RawJournal.StoredBatch>();
        var base = capture();
        var raw =
                new RawCapture(
                        base.captureId(),
                        base.sequence(),
                        base.sessionId(),
                        base.topic(),
                        base.payloadBase64(),
                        base.recvTs().minusSeconds(10),
                        base.captureDate(),
                        0,
                        false,
                        false);
        stubNewBatch(journal, List.of(raw), result);
        var clock = Clock.fixed(raw.recvTs(), ZoneOffset.UTC);
        var accumulator = new L2MinuteAccumulator(Duration.ofSeconds(2), 10, clock);
        assertEquals(
                1,
                new L2LiveProcessor(
                                journal,
                                sink,
                                new AliyunL2WireParser(),
                                accumulator,
                                100,
                                Set.of(),
                                clock)
                        .processOnce());
        assertTrue(result.get().states().isEmpty());
        assertEquals(1L, accumulator.diagnosticCounters().get("future_event_time_rejected"));
    }

    @Test
    void failedPublicationResumesExactFrozenBatchWithoutCountingTradeAgain() throws Exception {
        var journal = mock(RawJournal.class);
        var sink = mock(LocalLiveSink.class);
        var parser = spy(new AliyunL2WireParser());
        var accumulator =
                new L2MinuteAccumulator(
                        Duration.ofSeconds(2),
                        10,
                        Clock.fixed(Instant.parse("2026-10-14T01:30:00.001Z"), ZoneOffset.UTC));
        RawCapture capture = capture();
        var frozen = new AtomicReference<RawJournal.StoredBatch>();
        when(journal.committedAccumulatorState()).thenReturn(Map.of());
        when(journal.pending(100)).thenReturn(List.of(capture));
        when(journal.preparedBatch()).thenAnswer(call -> Optional.ofNullable(frozen.get()));
        when(journal.prepareBatch(anyList(), anyList(), anyMap()))
                .thenAnswer(
                        call -> {
                            var batch =
                                    new RawJournal.StoredBatch(
                                            "fixed-batch",
                                            call.getArgument(0),
                                            call.getArgument(1),
                                            call.getArgument(2));
                            frozen.set(batch);
                            return batch;
                        });
        doThrow(new IllegalStateException("uncertain fixture publication"))
                .doNothing()
                .when(sink)
                .write(anyList(), anyList());
        var processor = new L2LiveProcessor(journal, sink, parser, accumulator, 100);
        assertThrows(IllegalStateException.class, processor::processOnce);
        Map<String, Object> afterFirstCalculation = accumulator.snapshot();
        assertNotNull(frozen.get());
        assertEquals(1, processor.processOnce());
        assertEquals(afterFirstCalculation, accumulator.snapshot());
        verify(parser, times(1)).parse(capture);
        verify(journal, times(1)).prepareBatch(anyList(), anyList(), anyMap());
        verify(journal, times(1)).completeBatch("fixed-batch");
        verify(sink, times(2)).write(frozen.get().events(), frozen.get().states());
        var publicationOrder = inOrder(sink);
        publicationOrder.verify(sink, times(2)).write(frozen.get().events(), frozen.get().states());
    }

    @Test
    void restartUsesPreparedAvailabilityAndRestoresCheckpointInsteadOfReparsing() throws Exception {
        RawCapture capture = capture();
        var first =
                new L2MinuteAccumulator(
                        Duration.ofSeconds(2),
                        10,
                        Clock.fixed(Instant.parse("2026-10-14T01:30:00.001Z"), ZoneOffset.UTC));
        L2CleanEvent event = new AliyunL2WireParser().parse(capture);
        L2MinuteState state = first.accept(event).orElseThrow();
        var batch =
                new RawJournal.StoredBatch(
                        "existing", List.of(event), List.of(state), first.snapshot());
        var journal = mock(RawJournal.class);
        var sink = mock(LocalLiveSink.class);
        var parser = mock(AliyunL2WireParser.class);
        when(journal.committedAccumulatorState()).thenReturn(Map.of());
        when(journal.preparedBatch()).thenReturn(Optional.of(batch));
        var resumed =
                new L2MinuteAccumulator(
                        Duration.ofSeconds(2),
                        10,
                        Clock.fixed(Instant.parse("2026-10-14T02:30:00Z"), ZoneOffset.UTC));
        assertEquals(1, new L2LiveProcessor(journal, sink, parser, resumed, 100).processOnce());
        assertEquals(first.snapshot(), resumed.snapshot());
        assertEquals(
                Instant.parse("2026-10-14T01:30:00.001Z"), batch.states().getFirst().availableAt());
        verifyNoInteractions(parser);
        verify(journal).completeBatch("existing");
    }

    @Test
    void outsideSubscriptionAndUnexpectedQosRemainAuditEventsWithoutMinuteEffects()
            throws Exception {
        var outside = trade("outside", 1, "600000", "100", 0);
        var qos = trade("qos", 2, "563360", "100", 1);
        var valid = trade("valid", 3, "563360", "100", 0);
        var oldSession = trade("old-session", 4, "563360", "100", 0);
        var unconfirmed =
                new RawCapture(
                        oldSession.captureId(),
                        oldSession.sequence(),
                        oldSession.sessionId(),
                        oldSession.topic(),
                        oldSession.payloadBase64(),
                        oldSession.recvTs(),
                        oldSession.captureDate(),
                        oldSession.qos(),
                        oldSession.retained(),
                        oldSession.duplicate(),
                        false);
        var journal = mock(RawJournal.class);
        var sink = mock(LocalLiveSink.class);
        var result = new AtomicReference<RawJournal.StoredBatch>();
        stubNewBatch(journal, List.of(outside, qos, valid, unconfirmed), result);
        var accumulator = accumulator();
        assertEquals(
                4,
                new L2LiveProcessor(
                                journal,
                                sink,
                                new AliyunL2WireParser(),
                                accumulator,
                                100,
                                Set.of("trans/563360"))
                        .processOnce());
        assertEquals(
                List.of("scope_rejected", "qos_rejected", "ok", "subscription_unconfirmed"),
                result.get().events().stream().map(L2CleanEvent::parseStatus).toList());
        assertEquals(1, result.get().states().size());
        assertEquals(100L, result.get().states().getFirst().metrics().get("volume_1m"));
    }

    @Test
    void incompatibleFrozenScopeDoesNotModifyOrPublishTheRetainedBatch() throws Exception {
        var event = new AliyunL2WireParser().parse(trade("old-scope", 1, "600000", "100", 0));
        var journal = mock(RawJournal.class);
        var sink = mock(LocalLiveSink.class);
        when(journal.committedAccumulatorState()).thenReturn(Map.of());
        when(journal.preparedBatch())
                .thenReturn(
                        Optional.of(
                                new RawJournal.StoredBatch(
                                        "frozen", List.of(event), List.of(), Map.of())));
        var error =
                assertThrows(
                        IllegalStateException.class,
                        () ->
                                new L2LiveProcessor(
                                                journal,
                                                sink,
                                                new AliyunL2WireParser(),
                                                accumulator(),
                                                100,
                                                Set.of("trans/563360"))
                                        .processOnce());
        assertEquals("FROZEN_BATCH_SCOPE_MISMATCH", error.getMessage());
        verifyNoInteractions(sink);
        verify(journal, never()).completeBatch(anyString());
        verify(journal, never()).prepareBatch(anyList(), anyList(), anyMap());
    }

    @Test
    void overflowCaptureIsQuarantinedAndFollowingRepresentableTradeStillAdvances()
            throws Exception {
        var first = trade("first", 1, "563360", Long.toString(Long.MAX_VALUE - 2), 0);
        var overflow = trade("overflow", 2, "563360", "10", 0);
        var after = trade("after", 3, "563360", "1", 0);
        var journal = mock(RawJournal.class);
        var sink = mock(LocalLiveSink.class);
        var result = new AtomicReference<RawJournal.StoredBatch>();
        stubNewBatch(journal, List.of(first, overflow, after), result);
        assertEquals(
                3,
                new L2LiveProcessor(journal, sink, new AliyunL2WireParser(), accumulator(), 100)
                        .processOnce());
        assertEquals(
                List.of("ok", "aggregation_overflow", "ok"),
                result.get().events().stream().map(L2CleanEvent::parseStatus).toList());
        assertEquals(2, result.get().states().size());
        assertEquals(
                Long.MAX_VALUE - 1, result.get().states().getLast().metrics().get("volume_1m"));
        assertEquals(2L, result.get().states().getLast().metrics().get("trade_count_1m"));
    }

    private static void stubNewBatch(
            RawJournal journal,
            List<RawCapture> captures,
            AtomicReference<RawJournal.StoredBatch> result) {
        when(journal.committedAccumulatorState()).thenReturn(Map.of());
        when(journal.pending(100)).thenReturn(captures);
        when(journal.preparedBatch()).thenReturn(Optional.empty());
        when(journal.prepareBatch(anyList(), anyList(), anyMap()))
                .thenAnswer(
                        call -> {
                            var batch =
                                    new RawJournal.StoredBatch(
                                            "fixed-batch",
                                            call.getArgument(0),
                                            call.getArgument(1),
                                            call.getArgument(2));
                            result.set(batch);
                            return batch;
                        });
    }

    private static L2MinuteAccumulator accumulator() {
        return new L2MinuteAccumulator(
                Duration.ofSeconds(2),
                10,
                Clock.fixed(Instant.parse("2026-10-14T01:30:00.001Z"), ZoneOffset.UTC));
    }

    private static RawCapture trade(
            String id, long sequence, String symbol, String quantity, int qos) {
        return new RawCapture(
                id,
                sequence,
                "fixture-session",
                "trans/" + symbol,
                Base64.getEncoder()
                        .encodeToString(
                                (symbol + ",B,093000000,1,10000," + quantity + ",1001,1002")
                                        .getBytes(StandardCharsets.UTF_8)),
                Instant.parse("2026-10-14T01:30:00Z"),
                LocalDate.parse("2026-10-14"),
                qos,
                false,
                false);
    }

    private static RawCapture capture() {
        return new RawCapture(
                "fixture-capture",
                1,
                "fixture-session",
                "trans/563360",
                Base64.getEncoder()
                        .encodeToString(
                                "563360,B,093000000,1,10000,100,1001,1002"
                                        .getBytes(StandardCharsets.UTF_8)),
                Instant.parse("2026-10-14T01:30:00Z"),
                LocalDate.parse("2026-10-14"),
                0,
                false,
                false);
    }
}
