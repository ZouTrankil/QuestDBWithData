package com.zoutrankil.data.stock.application;

import com.zoutrankil.data.stock.port.StockStDailyTarget;
import com.zoutrankil.data.stock.port.StockDateWriteSession;

import com.zoutrankil.data.stock.domain.StockStDailyState;

import com.zoutrankil.data.service.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.repository.SyncRunLedger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import static com.zoutrankil.data.domain.SyncJobDefinition.*;

/** Streams receipt-backed daily ST pages after bounded annual namechange extraction. */
public final class StockStDailySyncAdapter implements SyncJobRunner.Adapter<StockStDaily,StockStDailyKey> {
    private final StockStDailySource source;
    private final StockStTradingDates tradingDates;
    private volatile VerifiedBatchExecutor.Port<StockStDaily,StockStDailyKey> port;
    private final StockDateWriteSession<StockStDaily, StockStDailyKey> originalPort;
    private final Path evidenceRoot;
    private final String runId;
    private final Path ledgerPath;
    private final String table;
    private final String logicalTarget;
    private final StockStDailyTarget target;

    public StockStDailySyncAdapter(StockStDailySource source, StockStTradingDates tradingDates,
            StockDateWriteSession<StockStDaily, StockStDailyKey> port, Path evidenceRoot) {
        this(source, tradingDates, port, evidenceRoot, null, null, null, null, null);
    }

    public StockStDailySyncAdapter(StockStDailySource source, StockStTradingDates tradingDates,
            StockDateWriteSession<StockStDaily, StockStDailyKey> port, Path evidenceRoot, String runId, Path ledgerPath, String table,
            String logicalTarget, StockStDailyTarget target) {
        this.source = Objects.requireNonNull(source); this.tradingDates = Objects.requireNonNull(tradingDates);
        this.originalPort = Objects.requireNonNull(port); this.port = port;
        this.evidenceRoot = evidenceRoot.toAbsolutePath().normalize(); this.runId = runId;
        this.ledgerPath = ledgerPath == null ? null : ledgerPath.toAbsolutePath().normalize();
        this.table = table; this.logicalTarget = logicalTarget; this.target = target;
        boolean completePublisher = runId != null && this.ledgerPath != null && table != null
                && logicalTarget != null && target != null;
        boolean absentPublisher = runId == null && this.ledgerPath == null && table == null
                && logicalTarget == null && target == null;
        if (!completePublisher && !absentPublisher) throw new IllegalArgumentException("Incomplete D012 staging publication context");
    }

    public static String encodeTradeDates(List<LocalDate> dates) {
        if (dates == null || dates.size() > StockStDailySyncJobOwner.MAX_WINDOW_DAYS
                || dates.stream().distinct().count() != dates.size() || !dates.equals(dates.stream().sorted().toList()))
            throw new IllegalArgumentException("Unique ascending bounded stk_st_daily dates required");
        String encoded = dates.isEmpty() ? "NONE" : String.join(",", dates.stream()
                .map(day -> day.format(DateTimeFormatter.BASIC_ISO_DATE)).toList());
        if (encoded.length() > 4000) throw new IllegalArgumentException("Frozen stk_st_daily dates exceed parameter bound");
        return encoded;
    }

    public static List<LocalDate> decodeTradeDates(SyncJobDefinition.FrozenRequest request) {
        Object raw = request.parameters().get("trade_dates");
        if (!(raw instanceof String encoded)) throw new IllegalArgumentException("Frozen stk_st_daily calendar required");
        if (encoded.equals("NONE")) return List.of();
        if (encoded.isBlank()) throw new IllegalArgumentException("Empty stk_st_daily date marker is invalid");
        List<LocalDate> dates = Arrays.stream(encoded.split(",", -1)).map(text -> {
            if (!text.matches("[0-9]{8}")) throw new IllegalArgumentException("BASIC_ISO_DATE required for stk_st_daily");
            return LocalDate.parse(text, DateTimeFormatter.BASIC_ISO_DATE);
        }).toList();
        if (dates.size() > StockStDailySyncJobOwner.MAX_WINDOW_DAYS
                || dates.stream().distinct().count() != dates.size() || !dates.equals(dates.stream().sorted().toList())
                || dates.stream().anyMatch(day -> day.isBefore(request.from()) || day.isAfter(request.to())))
            throw new IllegalArgumentException("Frozen stk_st_daily sessions are duplicate, unordered or out of range");
        return dates;
    }

    /** Reconstruct typed DATE parameters and reject any normalization drift from the stored request. */
    public static SyncJobDefinition.FrozenRequest restoreFrozenRequest(String frozenJson, String targetId) throws Exception {
        var json = JobDefinitionJson.mapper(); JsonNode saved = json.readTree(frozenJson);
        var definition = json.treeToValue(saved.path("definition"), SyncJobDefinition.class);
        var parameters = new LinkedHashMap<String,Object>(); JsonNode encoded = saved.path("parameters");
        if (!encoded.isObject()) throw new IllegalArgumentException("Frozen D012 parameter object required");
        for (var field : definition.parameters().entrySet()) {
            JsonNode value = encoded.get(field.getKey()); if (value == null || value.isNull()) continue;
            Object restored = switch (field.getValue().type()) {
                case DATE -> LocalDate.parse(value.asText());
                case STRING -> value.asText();
                case INTEGER -> value.asInt();
                case BOOLEAN -> value.asBoolean();
                case STRING_LIST -> json.convertValue(value, new com.fasterxml.jackson.core.type.TypeReference<List<String>>() {});
            };
            parameters.put(field.getKey(), restored);
        }
        Mode mode = Mode.valueOf(saved.path("mode").asText());
        LocalDate from = saved.path("from").isNull() ? null : LocalDate.parse(saved.path("from").asText());
        LocalDate to = saved.path("to").isNull() ? null : LocalDate.parse(saved.path("to").asText());
        LocalDate logicalDate = LocalDate.parse(saved.path("logicalDate").asText());
        var request = definition.freeze(mode, parameters, from, to, logicalDate);
        if (!SyncRequestIdentity.fingerprint(frozenJson, targetId)
                .equals(SyncRequestIdentity.fingerprint(request, targetId)))
            throw new IllegalArgumentException("Frozen D012 request cannot be reconstructed exactly");
        return request;
    }

    public static void validateFrozenRequest(SyncJobDefinition.FrozenRequest request) {
        if (request == null || !request.definition().equals(StockStDailySyncJobOwner.DEFINITION)
                || !request.definition().datasetId().equals(StockStDailyDataset.DEFINITION.datasetId())
                || request.definition().datasetVersion() != StockStDailyDataset.DEFINITION.schemaVersion()
                || !Set.of(Mode.INCREMENTAL, Mode.BACKFILL).contains(request.mode())
                || request.from() == null || request.to() == null || request.to().isAfter(request.logicalDate()))
            throw new IllegalArgumentException("Frozen bounded stk_st_daily request required");
        var params = request.parameters();
        Set<String> allowed = Set.of("targetId", "physicalTargetId", "trade_dates", "checkpointAnchor", "checkpointBefore",
                "targetMinBefore", "targetMaxBefore");
        if (!params.keySet().containsAll(Set.of("targetId", "physicalTargetId", "trade_dates"))
                || !allowed.containsAll(params.keySet()))
            throw new IllegalArgumentException("Unexpected or missing stk_st_daily parameters");
        if (!(params.get("targetId") instanceof String logicalTarget)
                || !logicalTarget.matches("d012-logical-v1-[0-9a-f]{64}")
                || !(params.get("physicalTargetId") instanceof String physicalTarget)
                || !physicalTarget.matches("static-v2-[0-9a-f]{64}"))
            throw new IllegalArgumentException("Frozen D012 logical and physical target identities required");
        if (request.mode() == Mode.INCREMENTAL) {
            if (!(params.get("checkpointAnchor") instanceof LocalDate anchor)
                    || anchor.isAfter(request.to()) || request.from().isBefore(anchor))
                throw new IllegalArgumentException("INCREMENTAL stk_st_daily requires its bootstrap anchor");
        } else if (params.get("checkpointAnchor") != null || params.get("checkpointBefore") != null) {
            throw new IllegalArgumentException("Only INCREMENTAL stk_st_daily may carry checkpoint metadata");
        }
        for (String field : List.of("checkpointBefore", "targetMinBefore", "targetMaxBefore")) {
            Object date = params.get(field);
            if (date != null && !(date instanceof LocalDate)) throw new IllegalArgumentException("Invalid frozen stk_st_daily date: " + field);
        }
        LocalDate min = (LocalDate) params.get("targetMinBefore"), max = (LocalDate) params.get("targetMaxBefore");
        if ((min == null) != (max == null) || min != null && min.isAfter(max) || max != null && max.isAfter(request.logicalDate()))
            throw new IllegalArgumentException("Invalid frozen stk_st_daily physical range");
        LocalDate before = (LocalDate) params.get("checkpointBefore");
        if (request.mode() == Mode.INCREMENTAL && before != null) {
            LocalDate overlap = before.minusDays(StockStDailySyncJobOwner.REVISION_DAYS - 1L);
            LocalDate anchor = (LocalDate) params.get("checkpointAnchor");
            if (overlap.isBefore(anchor)) overlap = anchor;
            if (before.isBefore((LocalDate) params.get("checkpointAnchor")) || before.isAfter(request.to())
                    || !request.from().equals(overlap))
                throw new IllegalArgumentException("Frozen stk_st_daily incremental overlap differs from its checkpoint");
        }
        if (request.from().isBefore(StockStDailySource.HISTORY_ANCHOR))
            throw new IllegalArgumentException("stk_st_daily does not infer namechange state before its 20100101 source anchor");
        decodeTradeDates(request);
    }

    @Override public void preflight(SyncJobDefinition.FrozenRequest request) {
        validateFrozenRequest(request);
        if (runId != null && !logicalTarget.equals(request.parameters().get("targetId")))
            throw new IllegalArgumentException("D012 logical target differs from frozen request");
        List<LocalDate> dates = decodeTradeDates(request);
        if (!dates.equals(tradingDates.read(request.from(), request.to())))
            throw new IllegalStateException("D001 SSE calendar changed after stk_st_daily planning");
        originalPort.preflight();
    }

    @Override public SyncJobRunner.SourceCompletion fetch(SyncJobDefinition.FrozenRequest request,
            SyncJobRunner.PageConsumer<StockStDaily> consumer, BooleanSupplier cancelled) throws Exception {
        List<LocalDate> dates = decodeTradeDates(request);
        if (runId == null || ledgerPath == null || table == null || logicalTarget == null || target == null)
            throw new IllegalStateException("D012 source execution requires its staging publication context");
        var publisher = new StockStDailyPublication(target.newPublicationTables(), ledgerPath);
        publisher.requireNoPendingPublication();
        var receipts = new ArrayList<Map<String,Object>>();
        var pages = new ArrayList<SyncJobRunner.Page<StockStDaily>>();
        int[] emitted = {0}, rows = {0};
        int historyWindows = dates.isEmpty() ? 0 : request.to().getYear() - StockStDailySource.HISTORY_ANCHOR.getYear() + 1;
        source.fetchWindow(request.from(), request.to(), dates, page -> {
            checkCancelled(cancelled);
            LocalDate day = LocalDate.parse(page.cursor(), DateTimeFormatter.BASIC_ISO_DATE);
            var reopened = StockStDailySource.reopen(Path.of(page.responseEvidence()), page.sourceFingerprint(), day);
            if (!sameRows(page.rows(), reopened.rows()))
                throw new IllegalStateException("stk_st_daily emitted page differs from its reopened raw-source receipt");
            pages.add(page);
            emitted[0]++; rows[0] = Math.addExact(rows[0], page.rows().size());
            receipts.add(Map.of("tradeDate", day.toString(), "path", page.responseEvidence(),
                    "fingerprint", page.sourceFingerprint(), "rows", page.rows().size()));
        }, cancelled);
        if (emitted[0] != dates.size()) throw new IllegalStateException("D012 did not emit one receipt per SSE trade date");
        // Even a frozen window with no SSE sessions is authoritative: publish its empty date
        // range so stale/non-calendar rows inside the request are removed while outside rows stay intact.
        var allRows = pages.stream().flatMap(page -> page.rows().stream()).toList();
        String sourceFingerprint = fingerprint(pages.stream().map(page -> page.sourceFingerprint()).toList());
        String physicalTarget = request.parameters().get("physicalTargetId").toString();
        var staging = target.newStaging();
        var prepared = staging.prepare(table, physicalTarget, request.from(), request.to());
        var stage = staging.write(prepared, allRows, sourceFingerprint, receipts, evidenceRoot, cancelled);
        this.port = target.stageWriter(stage.table(), stage.physicalTarget());
        for (var page : pages) {
            checkCancelled(cancelled);
            consumer.accept(page);
        }
        try {
            publisher.publish(runId, logicalTarget, physicalTarget, table, prepared, stage, cancelled);
        } catch (StockStDailyPublication.Uncertain uncertain) {
            markPublicationUncertain(request, pages, stage, uncertain);
            throw uncertain;
        }
        Files.createDirectories(evidenceRoot);
        Path completion = evidenceRoot.resolve("complete-" + UUID.randomUUID() + ".json");
        var proof = new LinkedHashMap<String,Object>();
        proof.put("sourceKind", "tushare"); proof.put("endpoint", "namechange");
        proof.put("from", request.from()); proof.put("to", request.to()); proof.put("historyAnchor", StockStDailySource.HISTORY_ANCHOR);
        proof.put("annualHistoryWindows", historyWindows); proof.put("tradeDates", dates);
        proof.put("sessionPages", emitted[0]); proof.put("sourceRows", rows[0]); proof.put("sessionReceipts", receipts);
        proof.put("complete", true);
        proof.put("publication", publisher.forRun(runId).intent().id());
        JobDefinitionJson.canonicalMapper()
                .writeValue(completion.toFile(), proof);
        if (Files.size(completion) > StockStDailySource.MAX_RESPONSE_BYTES)
            throw new IllegalArgumentException("D012 completion evidence exceeds 16 MiB");
        return new SyncJobRunner.SourceCompletion(emitted[0], rows[0], true, completion.toString());
    }

    @Override public VerifiedBatchExecutor.Codec<StockStDaily,StockStDailyKey> codec() { return originalPort.codec(); }
    @Override public VerifiedBatchExecutor.Port<StockStDaily,StockStDailyKey> port() { return port; }

    private void markPublicationUncertain(SyncJobDefinition.FrozenRequest request,
            List<SyncJobRunner.Page<StockStDaily>> pages, StockStDailyState.Verified stage,
            StockStDailyPublication.Uncertain uncertain) throws Exception {
        var ledger = new SyncRunLedger(ledgerPath);
        var entries = new ArrayList<SyncRunLedger.Entry>(); String cursor = null;
        while (true) {
            var page = ledger.entries(runId, cursor, 1000); entries.addAll(page);
            if (page.size() < 1000) break;
            cursor = page.getLast().id();
        }
        var attempt = entries.stream().filter(entry -> entry.kind() == SyncRunLedger.Kind.ATTEMPT
                && runId.equals(entry.parentId()) && runId.equals(entry.runId())).toList();
        if (attempt.size() != 1) throw new IllegalStateException("D012 publication cannot identify its unique generic attempt");
        String combined = fingerprint(pages.stream().map(page -> page.sourceFingerprint()).toList());
        var verification = Map.of("passed", true, "expectedRows", stage.sourceRows(), "actualRows", stage.sourceRows(),
                "matchedRows", stage.sourceRows(), "mismatchedRows", 0, "duplicateKeys", 0, "missingKeys", 0,
                "readbackEvidence", stage.receipt(), "sourceFingerprint", combined, "writerStopped", true);
        var runProof = new LinkedHashMap<String,Object>();
        runProof.put("sourceComplete", true); runProof.put("returnedRows", stage.sourceRows());
        runProof.put("submittedRows", stage.sourceRows()); runProof.put("responseEvidence", stage.receipt());
        runProof.put("verification", verification); runProof.put("publicationId", uncertain.publicationId());
        var json = JobDefinitionJson.mapper();
        for (var entry : entries) {
            if (entry.kind() != SyncRunLedger.Kind.SLICE || entry.state() != SyncRunState.FETCHED) continue;
            JsonNode fetched = null;
            for (var event : ledger.events(entry.id(), -1, 100)) if (event.state() == SyncRunState.FETCHED) {
                if (fetched != null) throw new IllegalStateException("D012 empty source slice has duplicate FETCHED events");
                fetched = json.readTree(event.payloadJson());
            }
            if (fetched == null || fetched.path("returnedRows").asInt(-1) != 0) continue;
            var sliceProof = new LinkedHashMap<String,Object>();
            sliceProof.put("sourceComplete", true); sliceProof.put("returnedRows", 0); sliceProof.put("submittedRows", 0);
            sliceProof.put("responseEvidence", fetched.path("responseEvidence").asText());
            sliceProof.put("publicationId", uncertain.publicationId());
            sliceProof.put("verification", Map.of("passed", true, "expectedRows", 0, "actualRows", 0, "matchedRows", 0,
                    "mismatchedRows", 0, "duplicateKeys", 0, "missingKeys", 0, "readbackEvidence", stage.receipt(),
                    "sourceFingerprint", fetched.path("sourceFingerprint").asText(), "writerStopped", true));
            // An empty page is still FETCHED in the generic runner. Its stage window was
            // independently verified before publication; only the run/attempt publication is uncertain.
            ledger.transition(entry.id(), entry.revision(), SyncRunState.VERIFIED_EMPTY, json.writeValueAsString(sliceProof));
        }
        var attemptEntry = attempt.getFirst();
        if (attemptEntry.state() == SyncRunState.RUNNING)
            ledger.transition(attemptEntry.id(), attemptEntry.revision(), SyncRunState.IN_DOUBT, json.writeValueAsString(runProof));
        var run = ledger.get(runId);
        if (run.state() == SyncRunState.RUNNING)
            ledger.transition(runId, run.revision(), SyncRunState.IN_DOUBT, json.writeValueAsString(runProof));
    }

    private static String fingerprint(List<String> parts) throws Exception {
        var digest = MessageDigest.getInstance("SHA-256");
        for (String part : parts) { digest.update(part.getBytes(java.nio.charset.StandardCharsets.UTF_8)); digest.update((byte) 0); }
        return HexFormat.of().formatHex(digest.digest());
    }

    private boolean sameRows(List<StockStDaily> left, List<StockStDaily> right) {
        if (left.size() != right.size()) return false;
        var a = new HashMap<StockStDailyKey,byte[]>(); var b = new HashMap<StockStDailyKey,byte[]>();
        for (var row : left) if (a.putIfAbsent(row.key(), originalPort.codec().canonicalBytes(row)) != null) return false;
        for (var row : right) if (b.putIfAbsent(row.key(), originalPort.codec().canonicalBytes(row)) != null) return false;
        return a.keySet().equals(b.keySet()) && a.keySet().stream().allMatch(key -> Arrays.equals(a.get(key), b.get(key)));
    }
    private static void checkCancelled(BooleanSupplier cancelled) {
        if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted())
            throw new CancellationException("stk_st_daily fetch cancelled");
    }
}
