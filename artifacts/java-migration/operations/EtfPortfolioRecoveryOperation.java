import com.fasterxml.jackson.databind.JsonNode;
import com.zoutrankil.questdbwithdata.QuestDbWithDataApplication;
import com.zoutrankil.questdbwithdata.domain.EtfPortfolio;
import com.zoutrankil.questdbwithdata.domain.EtfPortfolioKey;
import com.zoutrankil.questdbwithdata.domain.JobDefinitionJson;
import com.zoutrankil.questdbwithdata.domain.SyncJobDefinition;
import com.zoutrankil.questdbwithdata.domain.SyncRunState;
import com.zoutrankil.questdbwithdata.mapper.EtfPortfolioMapper;
import com.zoutrankil.questdbwithdata.repository.EtfPortfolioWritePort;
import com.zoutrankil.questdbwithdata.repository.SyncRunLedger;
import com.zoutrankil.questdbwithdata.service.EtfPortfolioJobService;
import com.zoutrankil.questdbwithdata.service.EtfPortfolioSyncAdapter;
import com.zoutrankil.questdbwithdata.service.EtfPortfolioSyncJobOwner;
import com.zoutrankil.questdbwithdata.service.EtfPortfolioSource;
import com.zoutrankil.questdbwithdata.service.SyncJobRunner;
import com.zoutrankil.questdbwithdata.service.DatasetIntervalLock;
import com.zoutrankil.questdbwithdata.service.VerifiedBatchExecutor;
import io.questdb.client.QuestDB;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.core.env.MapPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

/** D018-only live recovery drill: cancel after chunk 0, reuse 10,000 rows, write only the remaining chunk. */
final class EtfPortfolioRecoveryOperation {
    private static final int EXPECTED_ROWS = 18_718;
    private static final int EXPECTED_REUSED_ROWS = 10_000;
    private static final int EXPECTED_REMAINING_ROWS = EXPECTED_ROWS - EXPECTED_REUSED_ROWS;
    private static final List<String> PHYSICAL_COLUMNS = List.of("ts_code", "ann_date", "end_date", "symbol",
            "mkv", "amount", "stk_mkv_ratio", "stk_float_ratio", "update_time");

    private EtfPortfolioRecoveryOperation() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Pass one completed D018-live-acceptance.json report");
        Path reportPath = Path.of(args[0]).toAbsolutePath().normalize();
        JsonNode report = JobDefinitionJson.mapper().readTree(Files.readAllBytes(reportPath));
        String table = requiredText(report, "table");
        EtfPortfolioJobService.requireIsolatedTableName(table);
        Path ledgerValue = Path.of(requiredText(report, "ledger"));
        Path ledgerPath = ledgerValue.isAbsolute() ? ledgerValue : reportPath.getParent().resolve(ledgerValue).normalize();
        if (!Files.isRegularFile(ledgerPath)) throw new IllegalArgumentException("D018 acceptance ledger does not exist");
        LocalDate date = LocalDate.parse(requiredText(report.path("requestWindows"), "incrementalTo"));
        var reference = latestVerifiedRevision(reportPath.getParent(), requiredText(report, "table"), ledgerPath);
        String originalRunId = reference == null
                ? requiredText(report.path("runs").path(3), "runId") : reference.runId();
        var ledger = SyncRunLedger.openReadOnly(ledgerPath);
        var originalEntry = ledger.get(originalRunId);
        var originalRun = ledger.getRun(originalRunId);
        if (originalEntry.state() != SyncRunState.VERIFIED || !table.startsWith("java_d018_etf_portfolio_")
                || !EtfPortfolioSyncJobOwner.DEFINITION.jobId().equals(originalRun.jobId())
                || originalRun.jobVersion() != EtfPortfolioSyncJobOwner.DEFINITION.version())
            throw new IllegalStateException("D018 verified incremental acceptance run required");
        JsonNode originalFrozen = JobDefinitionJson.mapper().readTree(originalRun.frozenJson());
        if (!"INCREMENTAL".equals(originalFrozen.path("mode").asText())
                || !date.toString().equals(originalFrozen.path("to").asText())
                || !originalRun.targetId().equals(originalFrozen.path("parameters").path("targetId").asText()))
            throw new IllegalStateException("runs[3] is not the exact D018 incremental window/target selected for recovery");
        String observedAtText = requiredText(originalFrozen.path("parameters"), "observedAt");
        Instant observedAt = Instant.parse(observedAtText);
        if (!observedAt.toString().equals(observedAtText)) throw new IllegalStateException("Frozen observedAt is not canonical");
        LocalDate logicalDate = LocalDate.parse(requiredText(originalFrozen, "logicalDate"));
        String requestedDates = requiredText(originalFrozen.path("parameters"), "ann_dates");
        String dateToken = date.format(DateTimeFormatter.BASIC_ISO_DATE);
        if (!List.of(requestedDates.split(",", -1)).contains(dateToken))
            throw new IllegalStateException("Selected incremental run does not cover requestWindows.incrementalTo");

        var app = new SpringApplication(local.market.EtfPortfolioOperationApplication.class);
        app.setWebApplicationType(WebApplicationType.NONE);
        app.setLogStartupInfo(false);
        app.addInitializers(context -> context.getEnvironment().getPropertySources().addFirst(new MapPropertySource(
                "d018-recovery-operation", Map.of("app.sync.ledger-path", ledgerPath.toString(),
                "app.sync.etf-portfolio-table", table, "app.tushare.concurrency", 1))));
        var json = JobDefinitionJson.mapper();
        try (var context = app.run("list-sync-jobs")) {
            var jdbc = context.getBean(JdbcTemplate.class);
            var questdb = context.getBean(QuestDB.class);
            var jobs = context.getBean(EtfPortfolioJobService.class);
            var pages = context.getBean(com.zoutrankil.questdbwithdata.service.TusharePageService.class);
            var plan = jobs.plan(SyncJobDefinition.Mode.BACKFILL, date, date, logicalDate);
            if (!plan.targetId().equals(originalRun.targetId()))
                throw new IllegalStateException("D018 target identity changed since runs[3]");
            var frozenParameters = new LinkedHashMap<>(plan.request().parameters());
            frozenParameters.put("observedAt", observedAtText);
            var request = EtfPortfolioSyncJobOwner.DEFINITION.freeze(SyncJobDefinition.Mode.BACKFILL,
                    frozenParameters, date, date, logicalDate);
            String prefix = "d018-recovery-" + UUID.randomUUID();
            Path evidenceBase = ledgerPath.toAbsolutePath().normalize().getParent().resolve("sync-evidence");
            var mutableLedger = new SyncRunLedger(ledgerPath);
            var runner = new SyncJobRunner<EtfPortfolio, EtfPortfolioKey>(mutableLedger,
                    new DatasetIntervalLock(ledgerPath));
            var before = snapshot(jdbc, table, date, observedAt);
            if (before.rows() != EXPECTED_ROWS || !before.observedAtMatches())
                throw new IllegalStateException("D018 pre-recovery full-column snapshot must contain 18,718 rows at runs[3] observedAt");

            String partialRunId = prefix + "-cancelled";
            var partialSends = new AtomicInteger(); var cancellationInjected = new AtomicBoolean();
            var partialAdapter = adapterFor(pages, jdbc, questdb, table, plan.targetId(),
                    evidenceBase.resolve(partialRunId), mutableLedger, partialRunId,
                    true, dateToken + "#0", cancellationInjected, partialSends);
            var partial = runner.run(partialRunId, null, plan.targetId(), request, partialAdapter, () -> false);
            if (partial.state() != SyncRunState.CANCELLED || partial.sourceRows() != EXPECTED_REUSED_ROWS
                    || partial.verifiedRows() != EXPECTED_REUSED_ROWS || partialSends.get() != EXPECTED_REUSED_ROWS
                    || !cancellationInjected.get())
                throw new IllegalStateException("Expected cancellation after exactly the first 10,000-row D018 chunk");

            String resumedRunId = prefix + "-resumed";
            var resumedSends = new AtomicInteger(); var noCancellation = new AtomicBoolean();
            var resumeAdapter = adapterFor(pages, jdbc, questdb, table, plan.targetId(),
                    evidenceBase.resolve(resumedRunId), mutableLedger, resumedRunId,
                    false, dateToken + "#0", noCancellation, resumedSends);
            var resumed = runner.resume(resumedRunId, partialRunId, plan.targetId(), request, resumeAdapter, () -> false);
            if (resumed.state() != SyncRunState.VERIFIED || resumed.sourceRows() != EXPECTED_ROWS
                    || resumed.verifiedRows() != EXPECTED_ROWS || resumed.reusedRows() != EXPECTED_REUSED_ROWS
                    || resumedSends.get() != EXPECTED_REMAINING_ROWS)
                throw new IllegalStateException("D018 resume must revalidate 10,000 rows and send only the remaining 8,718");

            Map<String, Object> sourceReadback = EtfPortfolioIndependentReadback.verify(jdbc, ledgerPath, table, resumedRunId);
            if (!"MATCHED".equals(sourceReadback.get("status")))
                throw new IllegalStateException("Independent D018 source-to-QuestDB comparison did not match");
            var after = snapshot(jdbc, table, date, observedAt);
            if (!after.equals(before)) throw new IllegalStateException("D018 recovery changed the full nine-column physical snapshot");

            var result = new LinkedHashMap<String, Object>();
            result.put("task", "D018"); result.put("operation", "cancel_first_runner_chunk_resume_remaining_chunk");
            result.put("table", table); result.put("targetId", plan.targetId()); result.put("date", date.toString());
            result.put("observedAt", observedAtText); result.put("originalIncrementalRunId", originalRunId);
        result.put("observedAtReference", reference == null ? "base_acceptance.runs[3]" : reference.reportPath().toString());
            result.put("frozenMode", request.mode().name()); result.put("frozenFrom", request.from().toString());
            result.put("frozenTo", request.to().toString()); result.put("cancelledRun", partial);
            result.put("cancelledSendRows", partialSends.get()); result.put("resumedRun", resumed);
            result.put("resumedSendRows", resumedSends.get()); result.put("expectedResumedSendRows", EXPECTED_REMAINING_ROWS);
            result.put("reusedRows", resumed.reusedRows()); result.put("comparedColumns", PHYSICAL_COLUMNS);
            result.put("beforeSnapshot", before); result.put("afterSnapshot", after);
            result.put("fullNineColumnSnapshotUnchanged", true); result.put("independentSourceReadback", sourceReadback);
            result.put("formalTableMutated", false); result.put("humanReview", "pending_review");
            Path output = reportPath.getParent().resolve("D018-multichunk-recovery-" + prefix.substring(prefix.lastIndexOf('-') + 1) + ".json");
            Files.writeString(output, json.writerWithDefaultPrettyPrinter().writeValueAsString(result));
            System.out.println("D018 multi-chunk cancellation/resume verified; evidence=" + output);
        }
    }

    private static SyncJobRunner.Adapter<EtfPortfolio, EtfPortfolioKey> adapterFor(
            com.zoutrankil.questdbwithdata.service.TusharePageService pages, JdbcTemplate jdbc, QuestDB questdb,
            String table, String targetId, Path evidenceRoot, SyncRunLedger ledger, String runId,
            boolean cancelAfterCursor, String cursorToCancel, AtomicBoolean cancellationInjected,
            AtomicInteger sentRows) {
        Path runEvidence = evidenceRoot.toAbsolutePath().normalize();
        var source = new EtfPortfolioSource(pages, new EtfPortfolioMapper(), runEvidence.resolve("source"));
        var port = new EtfPortfolioWritePort(table, targetId, jdbc, questdb);
        var delegate = new EtfPortfolioSyncAdapter(source, port, runEvidence);
        var trackedPort = new VerifiedBatchExecutor.Port<EtfPortfolio, EtfPortfolioKey>() {
            @Override public void preflight() throws Exception { port.preflight(); }
            @Override public void send(List<EtfPortfolio> rows) throws Exception {
                sentRows.addAndGet(rows.size()); port.send(rows);
            }
            @Override public List<EtfPortfolio> readback(List<EtfPortfolioKey> keys) { return port.readback(keys); }
            @Override public boolean walSettled() { return port.walSettled(); }
            @Override public boolean uncertainSenderStopped() { return port.uncertainSenderStopped(); }
        };
        return new SyncJobRunner.Adapter<>() {
            @Override public void preflight(SyncJobDefinition.FrozenRequest request) throws Exception {
                delegate.preflight(request);
            }
            @Override public SyncJobRunner.SourceCompletion fetch(SyncJobDefinition.FrozenRequest request,
                    SyncJobRunner.PageConsumer<EtfPortfolio> consumer, BooleanSupplier cancelled) throws Exception {
                return delegate.fetch(request, page -> {
                    consumer.accept(page);
                    if (cancelAfterCursor && cursorToCancel.equals(page.cursor())
                            && cancellationInjected.compareAndSet(false, true)) {
                        if (!ledger.requestCancellation(runId))
                            throw new IllegalStateException("Could not inject D018 cancellation after first runner chunk");
                    }
                }, cancelled);
            }
            @Override public VerifiedBatchExecutor.Codec<EtfPortfolio, EtfPortfolioKey> codec() { return delegate.codec(); }
            @Override public VerifiedBatchExecutor.Port<EtfPortfolio, EtfPortfolioKey> port() { return trackedPort; }
        };
    }

    private static Snapshot snapshot(JdbcTemplate jdbc, String table, LocalDate date, Instant observedAt) throws Exception {
        EtfPortfolioJobService.requireIsolatedTableName(table);
        String query = "SELECT ts_code,cast(ann_date AS long) AS ann_date_micros,"
                + "cast(end_date AS long) AS end_date_micros,symbol,mkv,amount,stk_mkv_ratio,stk_float_ratio,"
                + "cast(update_time AS long) AS update_time_micros FROM \"" + table
                + "\" WHERE ann_date=cast(? AS TIMESTAMP) ORDER BY ts_code,end_date,symbol";
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        int[] rows = {0}; boolean[] observed = {true}; long expectedObservedMicros = epochMicros(observedAt);
        jdbc.query(connection -> {
            var statement = connection.prepareStatement(query);
            statement.setQueryTimeout(90); statement.setFetchSize(1000);
            statement.setLong(1, calendarMicros(date)); return statement;
        }, resultSet -> {
            while (resultSet.next()) {
                token(digest, resultSet.getString("ts_code"));
                token(digest, resultSet.getObject("ann_date_micros"));
                token(digest, resultSet.getObject("end_date_micros"));
                token(digest, resultSet.getString("symbol"));
                token(digest, resultSet.getObject("mkv")); token(digest, resultSet.getObject("amount"));
                token(digest, resultSet.getObject("stk_mkv_ratio")); token(digest, resultSet.getObject("stk_float_ratio"));
                Object update = resultSet.getObject("update_time_micros");
                if (!(update instanceof Number number) || number.longValue() != expectedObservedMicros) observed[0] = false;
                token(digest, update); rows[0]++;
            }
            return null;
        });
        return new Snapshot(rows[0], java.util.HexFormat.of().formatHex(digest.digest()), observed[0]);
    }

    private static RevisionReference latestVerifiedRevision(Path reportDirectory, String table, Path ledger)
            throws Exception {
        if (reportDirectory == null || !Files.isDirectory(reportDirectory)) return null;
        Path normalizedLedger = ledger.toAbsolutePath().normalize();
        var candidates = new ArrayList<RevisionReference>();
        try (var paths = Files.list(reportDirectory)) {
            for (Path path : paths.filter(candidate -> candidate.getFileName().toString().matches(
                    "D018-revision-[a-f0-9-]+\\.json")).toList()) {
                JsonNode revision = JobDefinitionJson.mapper().readTree(Files.readAllBytes(path));
                Path revisionLedger = Path.of(requiredText(revision, "ledger"));
                JsonNode incremental = revision.path("incrementalAfterBackfill");
                if (!"D018".equals(revision.path("task").asText())
                        || !"VERIFIED".equals(revision.path("status").asText())
                        || !"MATCHED".equals(revision.path("incrementalReadback").path("status").asText())
                        || !table.equals(revision.path("table").asText())
                        || !normalizedLedger.equals(revisionLedger.toAbsolutePath().normalize())
                        || !"VERIFIED".equals(incremental.path("state").asText())
                        || incremental.path("runId").asText().isBlank()) continue;
                Instant finished = Instant.parse(requiredText(revision, "finishedAt"));
                candidates.add(new RevisionReference(incremental.path("runId").asText(), path, finished));
            }
        }
        return candidates.stream().max(Comparator.comparing(RevisionReference::finishedAt)).orElse(null);
    }

    private static void token(MessageDigest digest, Object value) {
        if (value == null) { digest.update(ByteBuffer.allocate(4).putInt(-1).array()); return; }
        String canonical;
        if (value instanceof Number number) canonical = "N:" + number.toString();
        else canonical = "S:" + value;
        byte[] bytes = canonical.getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(4).putInt(bytes.length).array()); digest.update(bytes);
    }

    private static long calendarMicros(LocalDate date) {
        return Math.multiplyExact(date.atStartOfDay().toEpochSecond(java.time.ZoneOffset.UTC), 1_000_000L);
    }
    private static long epochMicros(Instant instant) {
        if (instant.getNano() % 1_000 != 0) throw new IllegalArgumentException("Microsecond observedAt precision required");
        return Math.addExact(Math.multiplyExact(instant.getEpochSecond(), 1_000_000L), instant.getNano() / 1_000L);
    }
    private static String requiredText(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (!value.isTextual() || value.asText().isBlank()) throw new IllegalArgumentException("Required D018 report/frozen field: " + field);
        return value.asText();
    }

    private record RevisionReference(String runId, Path reportPath, Instant finishedAt) {}
    private record Snapshot(int rows, String sha256, boolean observedAtMatches) {}
}
