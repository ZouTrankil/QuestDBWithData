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
import java.time.Clock;

/** 串联持久化采集、纯函数归一化和经核验的 Parquet 存储。 */
public final class L2LiveProcessor {
    private final RawJournal journal;
    private final LocalLiveSink sink;
    private final AliyunL2WireParser parser;
    private final L2MinuteAccumulator accumulator;
    private final int batchSize;
    private final Set<String> expectedTopics;
    private final Set<String> expectedSymbols;
    private final Clock clock;

    public L2LiveProcessor(RawJournal journal, LocalLiveSink sink, AliyunL2WireParser parser,
            L2MinuteAccumulator accumulator, int batchSize) throws Exception {
        this(journal, sink, parser, accumulator, batchSize, Set.of());
    }

    public L2LiveProcessor(RawJournal journal, LocalLiveSink sink, AliyunL2WireParser parser,
            L2MinuteAccumulator accumulator, int batchSize, Set<String> expectedTopics) throws Exception {
        this(journal, sink, parser, accumulator, batchSize, expectedTopics, Clock.systemUTC());
    }

    public L2LiveProcessor(RawJournal journal, LocalLiveSink sink, AliyunL2WireParser parser,
            L2MinuteAccumulator accumulator, int batchSize, Set<String> expectedTopics, Clock clock) throws Exception {
        this.journal = journal;
        this.sink = sink;
        this.parser = parser;
        this.accumulator = accumulator;
        this.batchSize = batchSize;
        this.expectedTopics = Set.copyOf(expectedTopics);
        this.clock = java.util.Objects.requireNonNull(clock);
        this.expectedSymbols = expectedTopics.stream().map(topic ->
                AliyunL2WireParser.normalizeSymbol(topic.substring(topic.indexOf('/') + 1))).collect(Collectors.toUnmodifiableSet());
        var checkpoint = journal.committedAccumulatorState();
        if (!checkpoint.isEmpty()) accumulator.restore(checkpoint);
    }

    /**
     * 优先恢复已冻结批次；只有不存在待完成批次时才解析新的原始报文。
     * 这样在崩溃后重试 Parquet 提交时，不会把同一条成交再次累加到分钟状态。
     */
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
                }
                var now = clock.instant();
                for (var event : events) if (event.isValid()) {
                    var admitAfter = event.exchangeTs().minus(L2MinuteAccumulator.EVENT_CLOCK_TOLERANCE);
                    if (event.capture().recvTs().isAfter(admitAfter)) admitAfter = event.capture().recvTs();
                    // Keep a slightly early frame durable and pending until real time
                    // catches up. Never widen the accumulator's future-time gate.
                    // Bad timestamps beyond this bounded wait still reach that gate.
                    if (admitAfter.isAfter(now) && !admitAfter.isAfter(now.plusSeconds(5))) return 0;
                }
                for (int index = 0; index < events.size(); index++) {
                    var event = events.get(index);
                    if (event.isValid()) {
                        try { accumulator.accept(event).ifPresent(states::add); }
                        catch (ArithmeticException overflow) {
                            // 批次尚未冻结；恢复到计算前状态，再重算此前已接纳的报文，
                            // 将本条溢出报文仅保留为审计事件，不影响分钟累计。
                            accumulator.restore(beforeCalculation);
                            states.clear();
                            events.set(index, rejected(event, "aggregation_overflow", "Minute totals exceed exact numeric bounds"));
                            for (var prior : events.subList(0, index)) if (prior.isValid()) accumulator.accept(prior).ifPresent(states::add);
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
        // 写入失败或结果未决时复用已持久化的准确值，不重新累加。
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
