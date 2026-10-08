package com.zoutrankil.batch.l2;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zoutrankil.batch.DfcfCsvParser;
import com.zoutrankil.batch.l2.L2BatchState.Job;
import com.zoutrankil.batch.l2.L2BatchState.Outcome;
import com.zoutrankil.batch.l2.L2BatchState.SourceFingerprint;
import com.zoutrankil.data.domain.L2DailyFeatureField;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;

public final class L2DailyFeatureBatchService {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String CHECKPOINT_VERSION = "l2-native-batch-v1";
    private final L2DailyFeatureSource sourceReader;
    private final L2CheckpointStore store;
    public L2DailyFeatureBatchService() {
        this(new L2DailyFeatureFileSource(), new L2FileCheckpointStore());
    }

    L2DailyFeatureBatchService(L2DailyFeatureSource source, L2CheckpointStore store) {
        this.sourceReader = Objects.requireNonNull(source);
        this.store = Objects.requireNonNull(store);
    }

    public void execute(L2DailyFeatureBatchRequest request) throws Exception {
        Path sourceRoot = request.sourceRoot(), outputRoot = request.outputRoot();
        int workers = request.workers();
        long maxBytes = request.maxBytesPerFile();
        if (!Files.isDirectory(sourceRoot)) throw new IOException("Missing source-root: " + sourceRoot);
        if (outputRoot.startsWith(sourceRoot) || sourceRoot.startsWith(outputRoot))
            throw new IllegalArgumentException("Source and output roots must be separate, non-nested directories");
        Set<String> universe = request.symbolsFile() == null?null:universe(Path.of(requiredSymbolsFile(request.symbolsFile())));
        store.createDirectory(outputRoot);
        String computeFingerprint = L2ComputationFingerprint.computeFingerprint();
        boolean allComplete = true;
        try (var lock = store.lock(outputRoot)) {
            for (LocalDate date:request.dates()) {
                String day = day(date);
                Path source = day.equals(String.valueOf(sourceRoot.getFileName()))?sourceRoot:sourceRoot.resolve(day);
                boolean complete = processDate(source, outputRoot.resolve(day), date, workers, maxBytes, request.resume(), computeFingerprint, universe);
                allComplete &= complete;
            }
        }
        if (!allComplete) throw new IOException("At least one date was incomplete; inspect manifest.json and errors.jsonl");
    }

    private static String requiredSymbolsFile(String value) {
        if (value.isBlank()) throw new IllegalArgumentException("Required option: --symbols-file");
        return value;
    }

    boolean processDate(Path source, Path output, LocalDate date, int workers, long maxBytes,
        boolean resume, String computeFingerprint, Set<String> universe) throws Exception {
        store.createDirectory(output.resolve("features"));
        store.createDirectory(output.resolve("state"));
        Instant started = Instant.now();
        List<Job> jobs;
        List<String> ignored = new ArrayList<>();
        var selection = new LinkedHashMap<String, Object>();
        try {
            jobs = sourceReader.discover(source, ignored);
            selection.put("allDiscoveredCanonicalSymbols", jobs.size());
            if (universe != null) {
                var discovered = new HashSet<String>();
                jobs.forEach(job -> discovered.add(job.symbol()));
                selection.put("requestedNotPresent", universe.stream().filter(symbol -> !discovered.contains(symbol)).sorted().toList());
                List<Job> selected = jobs.stream().filter(job -> universe.contains(job.symbol())).toList();
                selection.put("excludedByUniverse", jobs.size() - selected.size());
                selection.put("requestedUniverseSize", universe.size());
                jobs = selected;
            }
            else {
                selection.put("requestedNotPresent", List.of());
                selection.put("excludedByUniverse", 0);
                selection.put("requestedUniverseSize", null);
            }
            selection.put("selectedCanonicalSymbols", jobs.size());
            if (jobs.isEmpty()) throw new IOException("No legal six-digit source directories in " + source);
        }
        catch (Exception failure) {
            var failed = Outcome.failure("", List.of(), failure);
            store.json(output.resolve("manifest.json"), manifest(source, date, workers, computeFingerprint,
                started, "FAILED", List.of(failed), ignored, 0, null, selection));
            store.text(output.resolve("errors.jsonl"), JSON.writeValueAsString(failed.record()) + "\n");
            return false;
        }
        var outcomes = new TreeMap<String, Outcome>();
        store.json(output.resolve("manifest.json"), manifest(source, date, workers, computeFingerprint,
            started, "RUNNING", List.of(), ignored, jobs.size(), null, selection));
        var session = new L2BatchExecutionSession(workers);
        try {
            ExecutorService pool = session.pool;
            var completed = new ExecutorCompletionService<Outcome>(pool);
            int submitted = 0;
            int received = 0;
            try (var progress = store.log(output.resolve("progress.jsonl"));
                var errors = store.log(output.resolve("errors.jsonl"))) {
                // Never retain more than workers jobs, parsed tables, or completed futures in flight.
                while (submitted < Math.min(workers, jobs.size())) {
                    submit(completed, jobs.get(submitted++), output, date, maxBytes, resume, computeFingerprint, session.publication);
                }
                while (received < jobs.size()) {
                    Outcome result = completed.take().get();
                    outcomes.put(result.symbol(), result);
                    received++;
                    String text = JSON.writeValueAsString(result.record());
                    progress.write(text);
                    progress.newLine();
                    progress.flush();
                    if (result.status().equals("FAILED")) {
                        errors.write(text);
                        errors.newLine();
                        errors.flush();
                    }
                    System.out.printf(Locale.ROOT, "date=%s completed=%d/%d symbol=%s status=%s resumed=%s elapsedSeconds=%.3f%n",
                        day(date), received, jobs.size(), result.symbol(), result.status(), result.resumed(), result.elapsedSeconds());
                    if (received % 100 == 0)
                        store.json(output.resolve("manifest.json"), manifest(source, date, workers, computeFingerprint,
                        started, "RUNNING", outcomes.values(), ignored, jobs.size(), null, selection));
                    if (submitted < jobs.size())
                        submit(completed, jobs.get(submitted++), output, date, maxBytes, resume, computeFingerprint, session.publication);
                }
            }
            catch (Exception failure) {
                if (failure instanceof InterruptedException interrupted) session.interrupted(interrupted);
                session.publication.close();
                var failed = Outcome.failure("", List.of(), failure);
                outcomes.put("", failed);
                store.append(output.resolve("errors.jsonl"), JSON.writeValueAsString(failed.record()) + "\n");
            }
            finally {
                session.stop();
            }
            if (session.interruption() != null && !outcomes.containsKey("")) {
                var failure = Outcome.failure("", List.of(), session.interruption());
                outcomes.put("", failure);
                store.append(output.resolve("errors.jsonl"), JSON.writeValueAsString(failure.record()) + "\n");
            }
            long failed = outcomes.values().stream().filter(o -> o.status().equals("FAILED")).count();
            long successful = outcomes.values().stream().filter(o -> o.status().equals("SUCCESS")).count();
            if (failed == 0 && successful == 0) {
                var failedOutcome = Outcome.failure("", List.of(), new IOException("All selected source directories contain no raw rows"));
                outcomes.put("", failedOutcome);
                store.append(output.resolve("errors.jsonl"), JSON.writeValueAsString(failedOutcome.record()) + "\n");
                failed++;
            }
            boolean complete = failed == 0 && successful > 0;
            String aggregateHash = null;
            if (complete) {
                try {
                    aggregateHash = aggregate(output, outcomes.values());
                }
                catch (Exception failure) {
                    complete = false;
                    var failedOutcome = Outcome.failure("", List.of(), failure);
                    outcomes.put("", failedOutcome);
                    store.append(output.resolve("errors.jsonl"), JSON.writeValueAsString(failedOutcome.record()) + "\n");
                    failed++;
                }
            }
            store.json(output.resolve("manifest.json"), manifest(source, date, workers, computeFingerprint, started,
                complete ? "COMPLETE" : "FAILED", outcomes.values(), ignored, jobs.size(), aggregateHash, selection));
            System.out.printf(Locale.ROOT, "date=%s status=%s success=%d empty=%d failed=%d published=%s%n", day(date),
                complete ? "COMPLETE" : "FAILED", successful, outcomes.size() - successful - failed, failed, complete);
            System.out.println("manifest=" + output.resolve("manifest.json") + " aggregate=" + output.resolve("l2_daily_features.jsonl"));
            session.propagateInterrupted();
            return complete;
        }
        catch (Exception failure) {
            throw session.combine(failure);
        }
        catch (Error failure) {
            session.attachInterrupted(failure);
            throw failure;
        }
        finally {
            session.restoreInterrupted();
        }
    }

    private void submit(CompletionService<Outcome> completed, Job job, Path output, LocalDate date,
        long maxBytes, boolean resume, String computeFingerprint, L2PublicationGate publication) {
        completed.submit(() -> processSymbol(job, output, date, maxBytes, resume, computeFingerprint, publication));
    }

    private Outcome processSymbol(Job job, Path output, LocalDate date, long maxBytes,
        boolean resume, String computeFingerprint, L2PublicationGate publication) {
        long started = System.nanoTime();
        List<String> sourceNames = job.sources().stream().map(p -> p.getFileName().toString()).toList();
        try {
            SourceFingerprint fingerprint = sourceReader.fingerprint(job.sources().getFirst(), maxBytes);
            // Identical aliases select a deterministic source; conflicting aliases fail this canonical symbol.
            for (int i = 1; i < job.sources().size(); i++) {
                SourceFingerprint alias = sourceReader.fingerprint(job.sources().get(i), maxBytes);
                if (!alias.sha256().equals(fingerprint.sha256()))
                    throw new IOException("Conflicting canonical aliases: " + sourceNames);
            }
            Path result = output.resolve("features").resolve(job.symbol() + ".json");
            Path checkpoint = output.resolve("state").resolve(job.symbol() + ".json");
            if (resume) {
                Outcome reused = resume(checkpoint, result, job, date, fingerprint, computeFingerprint);
                if (reused != null) {
                    for (Path alias : job.sources())
                        if (!sourceReader.fingerprint(alias, maxBytes).sha256().equals(fingerprint.sha256()))
                    throw new IOException("Source files changed while validating resume: " + alias);
                    return reused.withElapsed((System.nanoTime() - started) / 1e9);
                }
            }
            var parsed = sourceReader.parse(job.sources().getFirst(), job.symbol(), date, maxBytes);
            boolean empty = parsed.rawDealRows() == 0 && parsed.rawOrderRows() == 0 && parsed.rawQuoteRows() == 0;
            var rows = Map.of("deals", (long) parsed.deals().size(), "orders", (long) parsed.orders().size(),
                "quotes", (long) parsed.quotes().size(), "rawDeals", parsed.rawDealRows(),
                "rawOrders", parsed.rawOrderRows(), "rawQuotes", parsed.rawQuoteRows());
            String resultHash = null;
            String resultText = null;
            if (!empty) resultText = JSON.writeValueAsString(L2DailyFeaturePipeline.output(L2DailyFeaturePipeline.compute(parsed))) + "\n";
            for (Path alias : job.sources())
                if (!sourceReader.fingerprint(alias, maxBytes).sha256().equals(fingerprint.sha256()))
            throw new IOException("Source files changed while computing: " + alias);
            String publishedText = resultText;
            Outcome[] published = new Outcome[1];
            publication.publish(() -> {
                    String publishedHash = null;
                    if (empty) store.delete(result);
                    else {
                        store.text(result, publishedText);
                        publishedHash = store.hash(result);
                }
                    var outcome = new Outcome(job.symbol(), empty ? "EMPTY" : "SUCCESS", false, sourceNames,
                    fingerprint.sha256(), fingerprint.files(), publishedHash, rows, (System.nanoTime() - started) / 1e9, null);
                    var state = new LinkedHashMap<String, Object>(outcome.record());
                    state.put("checkpointVersion", CHECKPOINT_VERSION);
                    state.put("date", day(date));
                    state.put("computeFingerprint", computeFingerprint);
                    store.json(checkpoint, state);
                    published[0] = outcome;
            });
            Outcome outcome = published[0];
            return outcome;
        }
        catch (Exception failure) {
            return Outcome.failure(job.symbol(), sourceNames, failure).withElapsed((System.nanoTime() - started) / 1e9);
        }
    }

    private Outcome resume(Path checkpoint, Path result, Job job, LocalDate date,
        SourceFingerprint input, String computeFingerprint) {
        try {
            JsonNode state = store.checkpoint(checkpoint);
            if (state == null) return null;
            if (!CHECKPOINT_VERSION.equals(state.path("checkpointVersion").asText()) || !computeFingerprint.equals(state.path("computeFingerprint").asText()) || !input.sha256().equals(state.path("inputFingerprint").asText()) || !day(date).equals(state.path("date").asText()) || !job.symbol().equals(state.path("symbol").asText())) return null;
            String status = state.path("status").asText();
            String resultHash = state.path("resultSha256").isTextual() ? state.path("resultSha256").asText() : null;
            if (status.equals("SUCCESS")) {
                if (!store.readableResult(result) || !store.hash(result).equals(resultHash)) return null;
                JsonNode row = store.readJson(result);
                if (!job.symbol().equals(row.path("symbol").asText()) || !day(date).equals(row.path("ts").asText()) || row.size() != L2DailyFeatureField.values().length) return null;
            }
            else if (!status.equals("EMPTY") || store.exists(result)) return null;
            var rows = new TreeMap<String, Long>();
            state.path("rows").fields().forEachRemaining(entry -> rows.put(entry.getKey(), entry.getValue().asLong()));
            return new Outcome(job.symbol(), status, true,
                job.sources().stream().map(p -> p.getFileName().toString()).toList(), input.sha256(), input.files(),
                resultHash, rows, 0, null);
        }
        catch (Exception invalidCheckpoint) {
            // An unreadable or stale checkpoint is recomputed from CSV, never silently accepted.
            return null;
        }
    }

    private String aggregate(Path output, Collection<Outcome> outcomes) throws IOException {
        return store.aggregate(output.resolve("l2_daily_features.jsonl"), writer -> {
                for (Outcome outcome:outcomes) {
                    if (!outcome.status().equals("SUCCESS")) continue;
                    Path result = output.resolve("features").resolve(outcome.symbol() + ".json");
                    if (!store.hash(result).equals(outcome.resultSha256())) throw new IOException("Feature result changed before aggregation: " + result);
                    writer.write(store.readText(result).strip());
                    writer.newLine();
            }
        });
    }

    private static Map<String, Object> manifest(Path source, LocalDate date, int workers, String fingerprint,
        Instant started, String status, Collection<Outcome> outcomes,
        List<String> ignored, int discovered, String aggregateHash,
        Map<String, Object> selection) {
        var counts = new LinkedHashMap<String, Long>();
        counts.put("success", outcomes.stream().filter(o -> o.status().equals("SUCCESS")).count());
        counts.put("empty", outcomes.stream().filter(o -> o.status().equals("EMPTY")).count());
        counts.put("failed", outcomes.stream().filter(o -> o.status().equals("FAILED")).count());
        counts.put("resumed", outcomes.stream().filter(Outcome::resumed).count());
        var manifest = new LinkedHashMap<String, Object>();
        manifest.put("checkpointVersion", CHECKPOINT_VERSION);
        manifest.put("date", day(date));
        manifest.put("sourceDirectory", source.toString());
        manifest.put("computeFingerprint", fingerprint);
        manifest.put("featureVersion", L2DailyFeaturePipeline.FEATURE_VERSION);
        manifest.put("parserVersion", L2DailyFeaturePipeline.PARSER_VERSION);
        manifest.put("workers", workers);
        manifest.put("startedAt", started.toString());
        manifest.put("updatedAt", Instant.now().toString());
        manifest.put("status", status);
        manifest.put("published", status.equals("COMPLETE"));
        manifest.put("aggregateSha256", aggregateHash);
        manifest.put("discoveredCanonicalSymbols", discovered);
        manifest.put("selection", selection);
        manifest.put("counts", counts);
        manifest.put("ignoredEntries", ignored);
        manifest.put("symbols", outcomes.stream().map(Outcome::record).toList());
        return manifest;
    }

    private static Set<String> universe(Path path) throws IOException {
        var symbols = new TreeSet<String>();
        for (String row : Files.readAllLines(path, StandardCharsets.UTF_8)) {
            String symbol = row.replace("\uFEFF", "").strip();
            if (symbol.isEmpty() || symbol.startsWith("#")) continue;
            if (!symbol.matches("(?i)\\d{6}[._](SH|SZ|BJ)")) throw new IOException("Invalid symbols-file row: " + row);
            symbols.add(DfcfCsvParser.normalizeSymbol(symbol.replace('_', '.')));
        }
        if (symbols.isEmpty()) throw new IOException("Empty symbols-file: " + path);
        return Collections.unmodifiableSet(symbols);
    }

    private static String day(LocalDate date) {
        return date.toString().replace("-", "");
    }
}
