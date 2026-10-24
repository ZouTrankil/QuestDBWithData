package com.zoutrankil.data.margin.application;

import static com.zoutrankil.data.domain.SyncJobDefinition.Mode;

import com.zoutrankil.data.domain.JobDefinitionJson;
import com.zoutrankil.data.domain.SyncJobDefinition.FrozenRequest;
import com.zoutrankil.data.domain.stock.margin.MarginAll;
import com.zoutrankil.data.domain.stock.margin.MarginAllDataset;
import com.zoutrankil.data.domain.stock.margin.MarginAllKey;
import com.zoutrankil.data.margin.domain.MarginAllRows;
import com.zoutrankil.data.margin.port.MarginAllTables;
import com.zoutrankil.data.margin.port.MarginAllWriteSession;
import com.zoutrankil.data.repository.FileEvidenceStore;
import com.zoutrankil.data.service.SyncJobRunner;
import com.zoutrankil.data.service.VerifiedBatchExecutor;

import java.nio.file.Path;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import java.util.stream.Collectors;

/** D028 fetches bounded source chunks, writes only the replacement stage, verifies it, then publishes. */
public final class MarginAllSyncAdapter implements SyncJobRunner.Adapter<MarginAll, MarginAllKey> {
    private final MarginAllSource source;
    private final MarginAllWriteSession port;
    private final MarginAllStaging staging;
    private final MarginAllPublication publication;
    private final Path runEvidence;
    private final MarginAllTables tables;
    private volatile String activeRunId;

    public MarginAllSyncAdapter(
            MarginAllSource source,
            MarginAllWriteSession port,
            MarginAllStaging staging,
            MarginAllPublication publication,
            Path runEvidence,
            MarginAllTables tables) {
        this.source = Objects.requireNonNull(source);
        this.port = Objects.requireNonNull(port);
        this.staging = Objects.requireNonNull(staging);
        this.publication = Objects.requireNonNull(publication);
        this.runEvidence = Objects.requireNonNull(runEvidence).toAbsolutePath().normalize();
        this.tables = Objects.requireNonNull(tables);
    }

    @Override
    public void preflight(FrozenRequest request) throws Exception {
        validateRequest(request);
        port.preflight();
        if ("margin_all".equals(port.formalTable()) != (request.definition().version() == 2))
            throw new IllegalArgumentException("汇总物理目标与请求版本不匹配");
        var target = tables.open(port.formalTable()).snapshot();
        String current = tables.physicalTargetId(port.formalTable(), target.identity());
        if (!current.equals(request.parameters().get("physicalTargetId"))
                || target.rows().size() != ((Integer) request.parameters().get("targetRowsBefore"))
                || !target.fingerprint().equals(request.parameters().get("targetFingerprint")))
            throw new IllegalStateException(
                    "D028 physical target generation or full snapshot changed after planning");
    }

    @Override
    public SyncJobRunner.SourceCompletion fetch(
            FrozenRequest request,
            SyncJobRunner.PageConsumer<MarginAll> consumer,
            BooleanSupplier cancelled)
            throws Exception {
        String runId = runEvidence.getFileName().toString();
        activeRunId = runId;
        try (MarginAllPublication.Operation operation = publication.beginOperation(runId)) {
            check(cancelled);
            String logical = (String) request.parameters().get("targetId"),
                    physical = (String) request.parameters().get("physicalTargetId");
            var calendar = request.definition().version() == 2 ? frozenCalendar(request) : null;
            var prepared =
                    staging.prepare(
                            port.formalTable(),
                            logical,
                            physical,
                            runId,
                            request,
                            runEvidence,
                            cancelled);
            port.useStage(prepared.stage(), prepared.stagePhysicalTarget());
            var collected = new ArrayList<SyncJobRunner.Page<MarginAll>>();
            var seen = new HashSet<MarginAllKey>();
            int rowCount = 0;
            LocalDate from = request.from();
            while (!from.isAfter(request.to())) {
                check(cancelled);
                LocalDate sliceDate = from;
                var page = source.fetch(sliceDate, cancelled);
                if (calendar != null)
                    MarginAllCalendar.requireCoverage(calendar, sliceDate, page.rows());
                for (var row : page.rows())
                    if (!row.tradeDate().equals(sliceDate) || !seen.add(row.key()))
                        throw new IllegalStateException(
                                "D028 duplicate natural key or out-of-date source row across slices");
                var preexisting =
                        prepared.before().rows().stream()
                                .filter(row -> row.tradeDate().equals(sliceDate))
                                .map(MarginAll::key)
                                .collect(Collectors.toSet());
                var sourceKeys =
                        page.rows().stream().map(MarginAll::key).collect(Collectors.toSet());
                if (!sourceKeys.containsAll(preexisting))
                    throw new IllegalStateException(
                            "D028 source omitted an existing natural key; non-DEDUP replacement has no approved deletion policy");
                consumer.accept(page);
                var actual = port.readWindow(prepared.stage(), sliceDate, sliceDate);
                if (!MarginAllRows.sameRows(page.rows(), actual))
                    throw new IllegalStateException("D028 stage slice all-field readback mismatch");
                collected.add(page);
                rowCount = Math.addExact(rowCount, page.rows().size());
                from = sliceDate.plusDays(1);
            }
            var verified = staging.verify(prepared, collected, cancelled);
            var published = publication.publish(operation, verified, cancelled);
            Path completion = runEvidence.resolve("source-complete.json");
            var body = new LinkedHashMap<String, Object>();
            body.put("jobId", MarginAllSyncJobOwner.DEFINITION.jobId());
            body.put("jobVersion", request.definition().version());
            if (calendar != null) {
                body.put("calendarDays", calendar.encoded());
                body.put("calendarFingerprint", calendar.fingerprint());
            }
            body.put("datasetId", "margin_all");
            body.put("mode", request.mode());
            body.put("logicalTargetId", logical);
            body.put("physicalTargetBefore", physical);
            body.put(
                    "publishedPhysicalId",
                    tables.physicalTargetId(port.formalTable(), published.target().identity()));
            body.put("fromInclusive", request.from());
            body.put("toInclusive", request.to());
            body.put("logicalDate", request.logicalDate());
            body.put("sourceSlices", verified.sourceReceipts());
            body.put("sourceRows", rowCount);
            body.put("stageReceipt", verified.receipt());
            body.put("publicationState", published.entry().state());
            body.put("publicationJournalId", published.entry().intent().id());
            body.put("complete", true);
            byte[] bytes = JobDefinitionJson.canonicalMapper().writeValueAsBytes(body);
            if (bytes.length > 2 * 1024 * 1024)
                throw new IllegalStateException("D028 source completion receipt exceeds 2 MiB");
            FileEvidenceStore.writeNew(completion, bytes);
            return new SyncJobRunner.SourceCompletion(
                    collected.size(), rowCount, true, completion.toString());
        }
    }

    @Override
    public VerifiedBatchExecutor.Codec<MarginAll, MarginAllKey> codec() {
        return port.codec();
    }

    @Override
    public VerifiedBatchExecutor.Port<MarginAll, MarginAllKey> port() {
        return port;
    }

    @Override
    public boolean recoveryRequired(String runId) throws Exception {
        // 物理发布成功之后，完成凭证或账本仍可能失败，原锁必须留给显式收尾。
        return MarginAllStaging.hasStageIntent(runEvidence);
    }

    public static void validateRequest(FrozenRequest request) {
        if (request == null
                || !(request.definition().equals(MarginAllSyncJobOwner.DEFINITION)
                        || request.definition().equals(MarginAllSyncJobOwner.FORMAL_DEFINITION))
                || request.definition().datasetVersion()
                        != MarginAllDataset.DEFINITION.schemaVersion()
                || !Set.of(Mode.INCREMENTAL, Mode.BACKFILL, Mode.RECONCILE).contains(request.mode())
                || request.from() == null
                || request.to() == null
                || request.from().isAfter(request.to())
                || request.to().isAfter(request.logicalDate())
                || ChronoUnit.DAYS.between(request.from(), request.to()) + 1
                        > MarginAllSyncJobOwner.MAX_WINDOW_DAYS)
            throw new IllegalArgumentException("Frozen bounded D028 request required");
        Map<String, Object> p = request.parameters();
        Set<String> allowed = request.definition().parameters().keySet();
        if (request.definition().version() == 2) {
            if (request.mode() != Mode.BACKFILL)
                throw new IllegalArgumentException("正式汇总v2只允许有界补数");
            frozenCalendar(request);
        }
        if (!p.keySet()
                        .containsAll(
                                Set.of(
                                        "targetId",
                                        "physicalTargetId",
                                        "targetFingerprint",
                                        "targetRowsBefore"))
                || !allowed.containsAll(p.keySet()))
            throw new IllegalArgumentException(
                    "Unexpected/missing D028 frozen target and checkpoint fields");
        for (String field : List.of("targetId", "physicalTargetId"))
            if (!(p.get(field) instanceof String value) || !value.matches("static-v2-[0-9a-f]{64}"))
                throw new IllegalArgumentException("Frozen D028 target identity required");
        if (!(p.get("targetFingerprint") instanceof String fingerprint)
                || !fingerprint.matches("[0-9a-f]{64}")
                || !(p.get("targetRowsBefore") instanceof Integer rows)
                || rows < 0)
            throw new IllegalArgumentException("Frozen D028 full target baseline required");
        for (String field :
                List.of(
                        "targetMinBefore",
                        "targetMaxBefore",
                        "checkpointAnchor",
                        "checkpointBefore"))
            if (p.get(field) != null && !(p.get(field) instanceof LocalDate))
                throw new IllegalArgumentException("Invalid D028 frozen date parameter");
        if ((p.get("targetMinBefore") == null) != (p.get("targetMaxBefore") == null))
            throw new IllegalArgumentException("D028 frozen target bounds must be paired");
        if (request.mode() == Mode.INCREMENTAL) {
            if (!(p.get("checkpointAnchor") instanceof LocalDate anchor)
                    || request.from().isBefore(anchor)
                    || p.get("checkpointBefore") != null
                            && (!(p.get("checkpointBefore") instanceof LocalDate before)
                                    || before.isBefore(anchor)
                                    || before.isAfter(request.to())
                                    || !request.from()
                                            .equals(
                                                    before.minusDays(
                                                                            MarginAllSyncJobOwner
                                                                                    .REVISION_DAYS)
                                                                    .isBefore(anchor)
                                                            ? anchor
                                                            : before.minusDays(
                                                                    MarginAllSyncJobOwner
                                                                            .REVISION_DAYS))))
                throw new IllegalArgumentException(
                        "D028 incremental requires explicit frozen anchor/checkpoint");
        } else if (p.get("checkpointAnchor") != null || p.get("checkpointBefore") != null)
            throw new IllegalArgumentException(
                    "D028 non-incremental request cannot carry checkpoint fields");
    }

    public static MarginAllCalendar.Window frozenCalendar(FrozenRequest request) {
        if (!MarginAllCalendar.POLICY.equals(request.parameters().get("calendarPolicy")))
            throw new IllegalArgumentException("正式汇总日历规则未知");
        Object raw = request.parameters().get("calendarDays"),
                hash = request.parameters().get("calendarFingerprint");
        if (!(raw instanceof String text) || !(hash instanceof String fingerprint))
            throw new IllegalArgumentException("正式汇总缺冻结日历");
        return MarginAllCalendar.validate(
                request.from(), request.to(), List.of(text.split(",", -1)), fingerprint);
    }

    private static void check(BooleanSupplier cancelled) {
        if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted())
            throw new CancellationException("D028 cancelled between bounded source slices");
    }
}
