package com.zoutrankil.data.l2live.bootstrap;

import com.zoutrankil.data.l2live.domain.AliyunL2WireParser;
import com.zoutrankil.data.l2live.domain.L2CleanEvent;
import com.zoutrankil.data.l2live.domain.L2MinuteAccumulator;
import com.zoutrankil.data.l2live.domain.L2MinuteState;
import com.zoutrankil.data.l2live.storage.RawJournal;
import com.zoutrankil.data.l2live.storage.LocalLiveSink;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Set;
import java.util.stream.Collectors;

/** Composition of durable captures, pure normalization and verified publication. */
public final class L2LiveProcessor {
    private final RawJournal journal;
    private final LocalLiveSink sink;
    private final AliyunL2WireParser parser;
    private final L2MinuteAccumulator accumulator;
    private final int batchSize;
    private final Set<String> expectedTopics;
    private final Set<String> expectedSymbols;

    public L2LiveProcessor(RawJournal journal, LocalLiveSink sink, AliyunL2WireParser parser,
            L2MinuteAccumulator accumulator, int batchSize) throws Exception {
        this(journal, sink, parser, accumulator, batchSize, Set.of());
    }

    public L2LiveProcessor(RawJournal journal, LocalLiveSink sink, AliyunL2WireParser parser,
            L2MinuteAccumulator accumulator, int batchSize, Set<String> expectedTopics) throws Exception {
        this.journal = journal;
        this.sink = sink;
        this.parser = parser;
        this.accumulator = accumulator;
        this.batchSize = batchSize;
        this.expectedTopics = Set.copyOf(expectedTopics);
        this.expectedSymbols = expectedTopics.stream().map(topic ->
                AliyunL2WireParser.normalizeSymbol(topic.substring(topic.indexOf('/') + 1))).collect(Collectors.toUnmodifiableSet());
        var checkpoint = journal.committedAccumulatorState();
        if (!checkpoint.isEmpty()) accumulator.restore(checkpoint);
    }

    public int processOnce() throws Exception {
        RawJournal.StoredBatch batch = journal.preparedBatch().orElse(null);
        if (batch == null) {
            var captures = journal.pending(batchSize);
            if (captures.isEmpty()) return 0;
            var events = new ArrayList<L2CleanEvent>();
            var states = new ArrayList<L2MinuteState>();
            var beforeCalculation = accumulator.snapshot();
            try {
                long captureBytes = 0;
                for (var capture : captures) {
                    long estimatedBytes = capture.payloadBase64().length() + 8192L;
                    if (!events.isEmpty() && captureBytes + estimatedBytes > 400_000) break;
                    captureBytes += estimatedBytes;
                    L2CleanEvent event = parser.parse(capture);
                    if (capture.qos() != 0) event = rejected(event, "qos_rejected", "Expected subscription QoS 0");
                    else if (!capture.subscriptionAuthorized())
                        event = rejected(event, "subscription_unconfirmed", "Full topic subscription was not confirmed when this capture arrived");
                    else if (!expectedTopics.isEmpty() && (!expectedTopics.contains(capture.topic())
                            || event.isValid() && !expectedSymbols.contains(event.symbol())))
                        event = rejected(event, "scope_rejected", "Capture differs from the configured subscription scope");
                    events.add(event);
                    if (event.isValid()) {
                        try { accumulator.accept(event).ifPresent(states::add); }
                        catch (ArithmeticException overflow) {
                            // The batch has not been frozen. Restore and recompute earlier admitted captures,
                            // retaining this offending envelope as an audit event without minute effects.
                            accumulator.restore(beforeCalculation);
                            states.clear();
                            events.set(events.size() - 1, rejected(event, "aggregation_overflow", "Minute totals exceed exact numeric bounds"));
                            for (var prior : events) if (prior.isValid()) accumulator.accept(prior).ifPresent(states::add);
                        }
                    }
                }
                batch = journal.prepareBatch(events, states, accumulator.snapshot());
            } catch (Exception error) {
                accumulator.restore(beforeCalculation);
                throw error;
            }
        }
        if (batch.events().stream().anyMatch(event -> event.isValid()
                && (event.capture().qos() != 0 || !event.capture().subscriptionAuthorized())))
            throw new IllegalStateException("FROZEN_BATCH_AUTHORIZATION_MISMATCH");
        if (!expectedTopics.isEmpty() && (batch.events().stream().anyMatch(event -> event.isValid()
                && (event.capture().qos() != 0 || !expectedTopics.contains(event.capture().topic())
                    || !expectedSymbols.contains(event.symbol())))
                || batch.states().stream().anyMatch(state -> !expectedSymbols.contains(state.symbol()))))
            throw new IllegalStateException("FROZEN_BATCH_SCOPE_MISMATCH");
        // A failed/unknown write is retried using these exact persisted values, never new accumulation.
        sink.write(batch.events(), batch.states());
        journal.completeBatch(batch.id());
        accumulator.restore(batch.accumulatorState());
        return batch.events().size();
    }

    private static L2CleanEvent rejected(L2CleanEvent event, String status, String error) {
        var fields = new LinkedHashMap<>(event.fields());
        Object quality = fields.get("quality_flags");
        fields.put("quality_flags", quality == null || quality.toString().isEmpty() ? status : quality + "|" + status);
        return new L2CleanEvent(event.capture(), event.symbol(), event.exchange(), event.channel(), event.tradeDate(),
                event.exchangeTs(), status, error, fields);
    }
}
