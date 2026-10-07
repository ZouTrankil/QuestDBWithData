package com.zoutrankil.data.derived.application;

import com.zoutrankil.data.service.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zoutrankil.data.domain.RetailSentimentDailyV1;
import com.zoutrankil.data.domain.SyncJobDefinition;
import com.zoutrankil.data.derived.port.RetailSentimentDailyV1Session;
import com.zoutrankil.data.derived.domain.RetailSentimentDailyV1Policy;
import com.zoutrankil.data.domain.RetailSentimentDailyV1Snapshot;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/** A whole bounded native daily aggregation is one source page and one bounded native WAL refresh batch. */
public final class RetailSentimentDailyV1MaterializeAdapter
        implements SyncJobRunner.Adapter<RetailSentimentDailyV1, LocalDate> {
    private static final ObjectMapper JSON = new ObjectMapper();
    public static final VerifiedBatchExecutor.Codec<RetailSentimentDailyV1, LocalDate> CODEC =
            new VerifiedBatchExecutor.Codec<>() {
                @Override public LocalDate key(RetailSentimentDailyV1 row) { return row.tradeDate(); }
                @Override public byte[] canonicalBytes(RetailSentimentDailyV1 row) {
                    var value = ByteBuffer.allocate(116);
                    value.putLong(row.tradeDate().toEpochDay());
                    number(value, row.avgRetailRatio());
                    number(value, row.avgRetailEntropy());
                    number(value, row.totalRetailAmountYi());
                    number(value, row.totalRetailNetInflowYi());
                    number(value, row.avgRelAggro());
                    integer(value, row.totalQ1());
                    integer(value, row.totalQ3());
                    number(value, row.avgWashTradeRatio());
                    integer(value, row.totalSpoofCount());
                    integer(value, row.totalManipulationCount());
                    number(value, row.avgMfiScore());
                    number(value, row.totalMainNetYi());
                    return value.array();
                }
                @Override public boolean equivalent(RetailSentimentDailyV1 left, RetailSentimentDailyV1 right) {
                    return RetailSentimentDailyV1Policy.equivalent(left, right);
                }
                private void integer(ByteBuffer output, Long value) {
                    output.put((byte) (value == null ? 0 : 1)).putLong(value == null ? 0 : value);
                }
                private void number(ByteBuffer output, Double value) {
                    output.put((byte) (value == null ? 0 : 1)).putDouble(value == null ? 0 : value);
                }
            };

    private final RetailSentimentDailyV1Session port;
    private final RetailSentimentDailyV1Snapshot frozen;
    private long sourceRawRows;
    private RetailSentimentDailyV1Snapshot lastVerificationSnapshot;

    public RetailSentimentDailyV1MaterializeAdapter(RetailSentimentDailyV1Session port, RetailSentimentDailyV1Snapshot frozen) {
        this.port = Objects.requireNonNull(port);
        this.frozen = Objects.requireNonNull(frozen);
    }

    @Override public DatasetIntervalLock.Scope conflictScope(SyncJobDefinition.FrozenRequest request) {
        // Native refresh affects the complete physical MV, even for a bounded verification window.
        return DatasetIntervalLock.Scope.allDates(request.definition().datasetId());
    }

    @Override public void preflight(SyncJobDefinition.FrozenRequest request) {
        requireRequest(request);
        if (full(request)) port.configureFullIsolated();
        port.bind(request.from(), request.to(), frozen);
        port.preflight();
        if (!frozen.sourceUnchanged(port.snapshot()))
            throw new IllegalStateException("D098 source changed after planning");
        requireCalendarVersion(request);
    }

    @Override public SyncJobRunner.SourceCompletion fetch(SyncJobDefinition.FrozenRequest request,
            SyncJobRunner.PageConsumer<RetailSentimentDailyV1> consumer, BooleanSupplier cancelled) throws Exception {
        requireRequest(request);
        check(cancelled);
        port.cancellationProbe(cancelled);
        port.bind(request.from(), request.to(), frozen);
        RetailSentimentDailyV1Snapshot before = port.snapshot();
        requireSourceReady(before, full(request));
        sourceRawRows = port.sourceRawRows(request.from(), request.to());
        var expected = port.expected(request.from(), request.to());
        if (request.mode() == SyncJobDefinition.Mode.INCREMENTAL) {
            requireCalendarVersion(request);
            requireCalendarCoverage(request.from(), request.to(),
                    expected.stream().map(RetailSentimentDailyV1::tradeDate).toList());
            requireCalendarVersion(request);
        }
        port.requireSourceCoverage(request.from(), request.to(), sourceRawRows, expected);
        RetailSentimentDailyV1Snapshot sourceAfter = port.snapshot();
        if (!before.sourceUnchanged(sourceAfter))
            throw new IllegalStateException("D098 source changed while reading its complete bounded aggregation");
        if ((sourceRawRows == 0) != expected.isEmpty())
            throw new IllegalStateException("D098 daily output does not cover all source rows in the frozen interval");
        if (full(request) && expected.isEmpty())
            throw new IllegalStateException("D098 FULL repair requires real nonempty source rows");
        if (expected.isEmpty()) {
            var actual = port.actual(request.from(), request.to());
            RetailSentimentDailyV1Snapshot afterEmptyRead = port.snapshot();
            if (!actual.isEmpty() || !before.equals(afterEmptyRead))
                throw new IllegalStateException("D098 empty source has stale output or its MV version changed");
            requireReady(afterEmptyRead);
            lastVerificationSnapshot = afterEmptyRead;
        }
        String fingerprint = fingerprint(expected, request);
        String evidence = evidence(request, expected.size(), fingerprint, sourceAfter);
        check(cancelled);
        consumer.accept(new SyncJobRunner.Page<>(expected, fingerprint, evidence,
                request.from() + "/" + request.to()));
        check(cancelled);
        RetailSentimentDailyV1Snapshot completed = port.snapshot();
        requireReady(completed);
        if (!frozen.sourceUnchanged(completed))
            throw new IllegalStateException("D098 source changed before verification was complete");
        if (!expected.isEmpty()) lastVerificationSnapshot = port.verifiedSnapshot();
        if (lastVerificationSnapshot == null || !lastVerificationSnapshot.equals(completed))
            throw new IllegalStateException("D098 MV version changed after its actual readback verification");
        if (full(request) && (port.outputRowCount() != expected.size() || !completed.equals(port.snapshot())))
            throw new IllegalStateException("D098 FULL output still contains rows outside the complete verified source window");
        requireCalendarVersion(request);
        return new SyncJobRunner.SourceCompletion(1, expected.size(), true,
                evidence(request, expected.size(), fingerprint, completed));
    }

    @Override public VerifiedBatchExecutor.Codec<RetailSentimentDailyV1, LocalDate> codec() { return CODEC; }

    /** Reconciliation verifies the original window without submitting another native refresh. */
    public SyncJobRunner.SourceCompletion revalidateOnly(SyncJobDefinition.FrozenRequest request,
                                                          BooleanSupplier cancelled) throws Exception {
        return fetch(request, page -> {
            if (page.rows().isEmpty()) return;
            var expected = page.rows();
            var actual = port.readback(expected.stream().map(RetailSentimentDailyV1::tradeDate).toList());
            if (actual.size() != expected.size()) throw new IllegalStateException("D098 reconciliation date coverage differs");
            for (int index = 0; index < expected.size(); index++)
                if (!CODEC.equivalent(expected.get(index), actual.get(index)))
                    throw new IllegalStateException("D098 reconciliation actual field values differ");
            if (!port.walSettled()) throw new IllegalStateException("D098 reconciliation MV changed or WAL is unsettled");
        }, cancelled);
    }

    @Override public RetailSentimentDailyV1Session port() { return port; }
    @Override public boolean recoveryRequired(String runId) { return port.unresolved(); }
    @Override public Duration visibilityTimeout() {
        var timeout = port.visibilityTimeout();
        return timeout == null ? Duration.ofMinutes(3) : timeout;
    }
    public long sourceRawRows() { return sourceRawRows; }
    public RetailSentimentDailyV1Snapshot lastVerificationSnapshot() { return lastVerificationSnapshot; }

    private void requireRequest(SyncJobDefinition.FrozenRequest request) {
        if (request == null || !"mv_retail_sentiment_daily_v1".equals(request.definition().datasetId())
                || request.definition().datasetVersion() != 1
                || !Set.of(SyncJobDefinition.Mode.INCREMENTAL, SyncJobDefinition.Mode.MATERIALIZE,
                           SyncJobDefinition.Mode.RECONCILE).contains(request.mode())
                || request.from() == null || request.to() == null
                || !frozen.sourceVersion().equals(request.parameters().get("source_version"))
                || !port.targetId().equals(request.parameters().get("target_id"))
                || request.mode() == SyncJobDefinition.Mode.INCREMENTAL
                    && !(request.parameters().get("calendar_version") instanceof String)
                || full(request) && request.mode() != SyncJobDefinition.Mode.MATERIALIZE)
            throw new IllegalArgumentException("Exact D098 frozen definition, source version and physical target required");
    }

    private void requireSourceReady(RetailSentimentDailyV1Snapshot state, boolean repair) {
        if ((!repair && !state.valid()) || "refreshing".equalsIgnoreCase(state.viewStatus())
                || !state.sourceSettled() || !state.mvSettled()
                || !frozen.sourceUnchanged(state) || frozen.mvId() != state.mvId()
                || !frozen.mvDirectory().equals(state.mvDirectory())
                || !frozen.definitionSha().equals(state.definitionSha()))
            throw new IllegalStateException("D098 source/MV is invalid, refreshing, unsettled or changed");
    }

    private void requireReady(RetailSentimentDailyV1Snapshot state) {
        if (!state.valid() || !state.caughtUp() || !state.sourceSettled() || !state.mvSettled()
                || !frozen.sourceUnchanged(state) || frozen.mvId() != state.mvId()
                || !frozen.mvDirectory().equals(state.mvDirectory())
                || !frozen.definitionSha().equals(state.definitionSha()))
            throw new IllegalStateException("D098 source/MV is invalid, lagging, unsettled or differs from the frozen target");
    }

    private String evidence(SyncJobDefinition.FrozenRequest request, int rows, String fingerprint,
                            RetailSentimentDailyV1Snapshot state) throws Exception {
        var details = new LinkedHashMap<String, Object>();
        details.put("nativeRefresh", full(request) ? "FULL_ISOLATED" : "INCREMENTAL");
        details.put("source", RetailSentimentDailyV1Policy.SOURCE);
        details.put("materializedView", RetailSentimentDailyV1Policy.OUTPUT);
        details.put("fromInclusive", request.from().toString());
        details.put("toExclusive", request.to().plusDays(1).toString());
        details.put("sourceRawRows", sourceRawRows);
        details.put("sourceAggregateRows", rows);
        details.put("sourceFingerprint", fingerprint);
        details.put("calendarVersion", request.parameters().get("calendar_version"));
        details.put("snapshot", state);
        details.put("sourceComplete", true);
        details.put("completenessMeaning", "all rows of the frozen physical base-table interval, aggregated without filtering");
        return JSON.writeValueAsString(details);
    }

    private void requireCalendarCoverage(LocalDate start, LocalDate end, List<LocalDate> expectedDates) {
        RetailSentimentDailyV1Policy.window(start, end);
        Objects.requireNonNull(expectedDates);
        String before = port.calendarVersion();
        var calendars = port.newCalendarReader();
        List<LocalDate> sessions = DailyTradingSessions.read(calendars, start, end);
        if (!sessions.equals(expectedDates))
            throw new IllegalStateException("D098 source daily buckets do not cover the complete real SSE trading calendar");
        if (!before.equals(port.calendarVersion()))
            throw new IllegalStateException("D098 exchange calendar changed during coverage verification");
    }

    private void requireCalendarVersion(SyncJobDefinition.FrozenRequest request) {
        if (request.mode() == SyncJobDefinition.Mode.INCREMENTAL
                && !port.calendarVersion().equals(request.parameters().get("calendar_version")))
            throw new IllegalStateException("D098 real exchange calendar differs from the frozen incremental version");
    }

    private String fingerprint(List<RetailSentimentDailyV1> expected,
                               SyncJobDefinition.FrozenRequest request) throws Exception {
        var digest = MessageDigest.getInstance("SHA-256");
        for (String part : List.of(frozen.sourceVersion(), frozen.definitionSha(), request.from().toString(),
                request.to().toString(), Long.toString(sourceRawRows),
                Objects.toString(request.parameters().get("calendar_version"), "not-incremental"),
                Objects.toString(request.parameters().get("native_refresh"), "INCREMENTAL"))) {
            digest.update(part.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
        }
        for (var row : expected) digest.update(CODEC.canonicalBytes(row));
        return HexFormat.of().formatHex(digest.digest());
    }

    private static void check(BooleanSupplier cancelled) {
        if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted())
            throw new CancellationException("D098 materialization cancelled");
    }
    private static boolean full(SyncJobDefinition.FrozenRequest request) {
        return request != null && "FULL_ISOLATED".equals(request.parameters().get("native_refresh"));
    }
}
