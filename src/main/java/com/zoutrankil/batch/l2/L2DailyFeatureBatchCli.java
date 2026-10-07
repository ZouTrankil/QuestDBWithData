package com.zoutrankil.batch.l2;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zoutrankil.batch.DfcfCsvParser;
import com.zoutrankil.data.domain.DatasetDefinition;
import com.zoutrankil.data.domain.L2DailyFeatureField;
import com.zoutrankil.data.domain.L2DailyFeatures;
import com.zoutrankil.data.domain.L2DailyFeaturesKey;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.*;
import static java.nio.file.StandardOpenOption.*;

/** Offline native computation over extracted date directories, with bounded in-flight symbols. */
public final class L2DailyFeatureBatchCli {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String CHECKPOINT_VERSION = "l2-native-batch-v1";
    private static final List<String> SOURCE_FILES = List.of("逐笔成交.csv", "逐笔委托.csv", "行情.csv");
    private static final int MAX_CHECKPOINT_BYTES = 1048576;

    private L2DailyFeatureBatchCli() {}

    public static void main(String[] args) throws Exception {
        var options = options(args);
        Path sourceRoot = Path.of(required(options, "--source-root")).toAbsolutePath().normalize();
        Path outputRoot = Path.of(required(options, "--output-root")).toAbsolutePath().normalize();
        int workers = Integer.parseInt(options.getOrDefault("--workers", "1"));
        long maxBytes = Long.parseLong(options.getOrDefault("--max-bytes-per-file", "536870912"));
        String resumeText = options.getOrDefault("--resume", "false");
        if (workers < 1 || workers > 32) throw new IllegalArgumentException("workers must be between 1 and 32");
        if (maxBytes < 1) throw new IllegalArgumentException("max-bytes-per-file must be positive");
        if (!Set.of("true", "false").contains(resumeText)) throw new IllegalArgumentException("resume must be true or false");
        var dates = new TreeSet<LocalDate>();
        for (String text : required(options, "--dates").split(",", -1)) {
            LocalDate date = date(text.strip());
            if (!dates.add(date)) throw new IllegalArgumentException("Duplicate date: " + text);
        }
        if (!Files.isDirectory(sourceRoot)) throw new IOException("Missing source-root: " + sourceRoot);
        if (outputRoot.startsWith(sourceRoot) || sourceRoot.startsWith(outputRoot))
            throw new IllegalArgumentException("Source and output roots must be separate, non-nested directories");
        Set<String> universe = options.containsKey("--symbols-file") ? universe(Path.of(required(options, "--symbols-file"))) : null;
        Files.createDirectories(outputRoot);
        String computeFingerprint = computeFingerprint();
        boolean allComplete = true;
        try (var channel = FileChannel.open(outputRoot.resolve("batch.lock"), CREATE, WRITE)) {
            var lock = channel.tryLock();
            if (lock == null) throw new IOException("Another batch owns output-root: " + outputRoot);
            try (lock) {
                for (LocalDate date : dates) {
                    String day = day(date);
                    Path source = day.equals(String.valueOf(sourceRoot.getFileName())) ? sourceRoot : sourceRoot.resolve(day);
                    boolean complete = processDate(source, outputRoot.resolve(day), date, workers, maxBytes,
                            Boolean.parseBoolean(resumeText), computeFingerprint, universe);
                    allComplete &= complete;
                }
            }
        }
        if (!allComplete) throw new IOException("At least one date was incomplete; inspect manifest.json and errors.jsonl");
    }

    private static boolean processDate(Path source, Path output, LocalDate date, int workers, long maxBytes,
                                       boolean resume, String computeFingerprint, Set<String> universe) throws Exception {
        Files.createDirectories(output.resolve("features"));
        Files.createDirectories(output.resolve("state"));
        Instant started = Instant.now();
        List<Job> jobs;
        List<String> ignored = new ArrayList<>();
        var selection = new LinkedHashMap<String, Object>();
        try {
            jobs = discover(source, ignored);
            selection.put("allDiscoveredCanonicalSymbols", jobs.size());
            if (universe != null) {
                var discovered = new HashSet<String>();
                jobs.forEach(job -> discovered.add(job.symbol()));
                selection.put("requestedNotPresent", universe.stream().filter(symbol -> !discovered.contains(symbol)).sorted().toList());
                List<Job> selected = jobs.stream().filter(job -> universe.contains(job.symbol())).toList();
                selection.put("excludedByUniverse", jobs.size() - selected.size());
                selection.put("requestedUniverseSize", universe.size());
                jobs = selected;
            } else {
                selection.put("requestedNotPresent", List.of());
                selection.put("excludedByUniverse", 0);
                selection.put("requestedUniverseSize", null);
            }
            selection.put("selectedCanonicalSymbols", jobs.size());
            if (jobs.isEmpty()) throw new IOException("No legal six-digit source directories in " + source);
        } catch (Exception failure) {
            var failed = Outcome.failure("", List.of(), failure);
            atomicJson(output.resolve("manifest.json"), manifest(source, date, workers, computeFingerprint,
                    started, "FAILED", List.of(failed), ignored, 0, null, selection));
            atomicText(output.resolve("errors.jsonl"), JSON.writeValueAsString(failed.record()) + "\n");
            return false;
        }
        var outcomes = new TreeMap<String, Outcome>();
        atomicJson(output.resolve("manifest.json"), manifest(source, date, workers, computeFingerprint,
                started, "RUNNING", List.of(), ignored, jobs.size(), null, selection));
        ExecutorService pool = Executors.newFixedThreadPool(workers);
        var completed = new ExecutorCompletionService<Outcome>(pool);
        int submitted = 0;
        int received = 0;
        try (BufferedWriter progress = Files.newBufferedWriter(output.resolve("progress.jsonl"), StandardCharsets.UTF_8,
                    CREATE, TRUNCATE_EXISTING, WRITE);
             BufferedWriter errors = Files.newBufferedWriter(output.resolve("errors.jsonl"), StandardCharsets.UTF_8,
                    CREATE, TRUNCATE_EXISTING, WRITE)) {
            // Never retain more than workers jobs, parsed tables, or completed futures in flight.
            while (submitted < Math.min(workers, jobs.size())) {
                submit(completed, jobs.get(submitted++), output, date, maxBytes, resume, computeFingerprint);
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
                    atomicJson(output.resolve("manifest.json"), manifest(source, date, workers, computeFingerprint,
                            started, "RUNNING", outcomes.values(), ignored, jobs.size(), null, selection));
                if (submitted < jobs.size())
                    submit(completed, jobs.get(submitted++), output, date, maxBytes, resume, computeFingerprint);
            }
        } catch (Exception failure) {
            var failed = Outcome.failure("", List.of(), failure);
            outcomes.put("", failed);
            Files.writeString(output.resolve("errors.jsonl"), JSON.writeValueAsString(failed.record()) + "\n",
                    StandardCharsets.UTF_8, CREATE, APPEND);
        } finally {
            pool.shutdownNow();
            pool.awaitTermination(30, TimeUnit.SECONDS);
        }
        long failed = outcomes.values().stream().filter(o -> o.status().equals("FAILED")).count();
        long successful = outcomes.values().stream().filter(o -> o.status().equals("SUCCESS")).count();
        if (failed == 0 && successful == 0) {
            var failedOutcome = Outcome.failure("", List.of(), new IOException("All selected source directories contain no raw rows"));
            outcomes.put("", failedOutcome);
            Files.writeString(output.resolve("errors.jsonl"), JSON.writeValueAsString(failedOutcome.record()) + "\n",
                    StandardCharsets.UTF_8, CREATE, APPEND);
            failed++;
        }
        boolean complete = failed == 0 && successful > 0;
        String aggregateHash = null;
        if (complete) {
            try { aggregateHash = aggregate(output, outcomes.values()); }
            catch (Exception failure) {
                complete = false;
                var failedOutcome = Outcome.failure("", List.of(), failure);
                outcomes.put("", failedOutcome);
                Files.writeString(output.resolve("errors.jsonl"), JSON.writeValueAsString(failedOutcome.record()) + "\n",
                        StandardCharsets.UTF_8, CREATE, APPEND);
                failed++;
            }
        }
        atomicJson(output.resolve("manifest.json"), manifest(source, date, workers, computeFingerprint, started,
                complete ? "COMPLETE" : "FAILED", outcomes.values(), ignored, jobs.size(), aggregateHash, selection));
        System.out.printf(Locale.ROOT, "date=%s status=%s success=%d empty=%d failed=%d published=%s%n", day(date),
                complete ? "COMPLETE" : "FAILED", successful, outcomes.size() - successful - failed, failed, complete);
        System.out.println("manifest=" + output.resolve("manifest.json") + " aggregate=" + output.resolve("l2_daily_features.jsonl"));
        return complete;
    }

    private static void submit(CompletionService<Outcome> completed, Job job, Path output, LocalDate date,
                               long maxBytes, boolean resume, String computeFingerprint) {
        completed.submit(() -> processSymbol(job, output, date, maxBytes, resume, computeFingerprint));
    }

    private static Outcome processSymbol(Job job, Path output, LocalDate date, long maxBytes,
                                          boolean resume, String computeFingerprint) {
        long started = System.nanoTime();
        List<String> sourceNames = job.sources().stream().map(p -> p.getFileName().toString()).toList();
        try {
            SourceFingerprint fingerprint = sourceFingerprint(job.sources().getFirst(), maxBytes);
            // Identical aliases select a deterministic source; conflicting aliases fail this canonical symbol.
            for (int i = 1; i < job.sources().size(); i++) {
                SourceFingerprint alias = sourceFingerprint(job.sources().get(i), maxBytes);
                if (!alias.sha256().equals(fingerprint.sha256()))
                    throw new IOException("Conflicting canonical aliases: " + sourceNames);
            }
            Path result = output.resolve("features").resolve(job.symbol() + ".json");
            Path checkpoint = output.resolve("state").resolve(job.symbol() + ".json");
            if (resume) {
                Outcome reused = resume(checkpoint, result, job, date, fingerprint, computeFingerprint);
                if (reused != null) {
                    for (Path alias : job.sources())
                        if (!sourceFingerprint(alias, maxBytes).sha256().equals(fingerprint.sha256()))
                            throw new IOException("Source files changed while validating resume: " + alias);
                    return reused.withElapsed((System.nanoTime() - started) / 1e9);
                }
            }
            var parsed = DfcfCsvParser.readProductionDay(job.sources().getFirst(), job.symbol(), date, maxBytes);
            boolean empty = parsed.rawDealRows() == 0 && parsed.rawOrderRows() == 0 && parsed.rawQuoteRows() == 0;
            var rows = Map.of("deals", (long) parsed.deals().size(), "orders", (long) parsed.orders().size(),
                    "quotes", (long) parsed.quotes().size(), "rawDeals", parsed.rawDealRows(),
                    "rawOrders", parsed.rawOrderRows(), "rawQuotes", parsed.rawQuoteRows());
            String resultHash = null;
            String resultText = null;
            if (!empty) resultText = JSON.writeValueAsString(L2DailyFeaturePipeline.output(L2DailyFeaturePipeline.compute(parsed))) + "\n";
            for (Path alias : job.sources())
                if (!sourceFingerprint(alias, maxBytes).sha256().equals(fingerprint.sha256()))
                    throw new IOException("Source files changed while computing: " + alias);
            if (empty) Files.deleteIfExists(result);
            else {
                atomicText(result, resultText);
                resultHash = sha256(result);
            }
            var outcome = new Outcome(job.symbol(), empty ? "EMPTY" : "SUCCESS", false, sourceNames,
                    fingerprint.sha256(), fingerprint.files(), resultHash, rows, (System.nanoTime() - started) / 1e9, null);
            var state = new LinkedHashMap<String, Object>(outcome.record());
            state.put("checkpointVersion", CHECKPOINT_VERSION);
            state.put("date", day(date));
            state.put("computeFingerprint", computeFingerprint);
            atomicJson(checkpoint, state);
            return outcome;
        } catch (Exception failure) {
            return Outcome.failure(job.symbol(), sourceNames, failure).withElapsed((System.nanoTime() - started) / 1e9);
        }
    }

    private static Outcome resume(Path checkpoint, Path result, Job job, LocalDate date,
                                   SourceFingerprint input, String computeFingerprint) {
        try {
            if (!Files.isRegularFile(checkpoint) || Files.size(checkpoint) > MAX_CHECKPOINT_BYTES) return null;
            JsonNode state = JSON.readTree(checkpoint.toFile());
            if (!CHECKPOINT_VERSION.equals(state.path("checkpointVersion").asText())
                    || !computeFingerprint.equals(state.path("computeFingerprint").asText())
                    || !input.sha256().equals(state.path("inputFingerprint").asText())
                    || !day(date).equals(state.path("date").asText())
                    || !job.symbol().equals(state.path("symbol").asText())) return null;
            String status = state.path("status").asText();
            String resultHash = state.path("resultSha256").isTextual() ? state.path("resultSha256").asText() : null;
            if (status.equals("SUCCESS")) {
                if (!Files.isRegularFile(result) || Files.size(result) > MAX_CHECKPOINT_BYTES
                        || !sha256(result).equals(resultHash)) return null;
                JsonNode row = JSON.readTree(result.toFile());
                if (!job.symbol().equals(row.path("symbol").asText()) || !day(date).equals(row.path("ts").asText())
                        || row.size() != L2DailyFeatureField.values().length) return null;
            } else if (!status.equals("EMPTY") || Files.exists(result)) return null;
            var rows = new TreeMap<String, Long>();
            state.path("rows").fields().forEachRemaining(entry -> rows.put(entry.getKey(), entry.getValue().asLong()));
            return new Outcome(job.symbol(), status, true,
                    job.sources().stream().map(p -> p.getFileName().toString()).toList(), input.sha256(), input.files(),
                    resultHash, rows, 0, null);
        } catch (Exception invalidCheckpoint) {
            // An unreadable or stale checkpoint is recomputed from CSV, never silently accepted.
            return null;
        }
    }

    private static List<Job> discover(Path source, List<String> ignored) throws IOException {
        if (!Files.isDirectory(source)) throw new IOException("Missing extracted date directory: " + source);
        var symbols = new TreeMap<String, List<Path>>();
        try (var entries = Files.list(source)) {
            for (Path entry : entries.sorted().toList()) {
                String name = entry.getFileName().toString();
                if (!Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS) || !name.matches("(?i)\\d{6}[._](SH|SZ|BJ)")) {
                    ignored.add(name);
                    continue;
                }
                String canonical = DfcfCsvParser.normalizeSymbol(name.replace('_', '.'));
                symbols.computeIfAbsent(canonical, unused -> new ArrayList<>()).add(entry);
            }
        }
        return symbols.entrySet().stream().map(entry -> new Job(entry.getKey(), List.copyOf(entry.getValue()))).toList();
    }

    private static SourceFingerprint sourceFingerprint(Path source, long maxBytes) throws IOException {
        var files = new LinkedHashMap<String, Object>();
        MessageDigest digest = digest();
        for (String name : SOURCE_FILES) {
            Path file = source.resolve(name);
            if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
                files.put(name, Map.of("present", false));
                update(digest, name + "\nMISSING\n");
                continue;
            }
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Source CSV is not a regular file: " + file);
            long size = Files.size(file);
            if (size > maxBytes) throw new IOException("Source CSV exceeds byte bound: " + file + " bytes=" + size);
            String hash = sha256(file);
            if (Files.size(file) != size) throw new IOException("Source CSV changed while hashing: " + file);
            files.put(name, Map.of("present", true, "bytes", size, "sha256", hash));
            update(digest, name + "\n" + size + "\n" + hash + "\n");
        }
        return new SourceFingerprint(hex(digest), Collections.unmodifiableMap(files));
    }

    private static String aggregate(Path output, Collection<Outcome> outcomes) throws IOException {
        Path destination = output.resolve("l2_daily_features.jsonl");
        Path temp = temporary(destination);
        try {
            try (BufferedWriter writer = Files.newBufferedWriter(temp, StandardCharsets.UTF_8, CREATE_NEW, WRITE)) {
                for (Outcome outcome : outcomes) {
                    if (!outcome.status().equals("SUCCESS")) continue;
                    Path result = output.resolve("features").resolve(outcome.symbol() + ".json");
                    if (!sha256(result).equals(outcome.resultSha256())) throw new IOException("Feature result changed before aggregation: " + result);
                    writer.write(Files.readString(result, StandardCharsets.UTF_8).strip());
                    writer.newLine();
                }
            }
            force(temp);
            Files.move(temp, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            return sha256(destination);
        } finally {
            Files.deleteIfExists(temp);
        }
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

    /** Digest actual loaded computation bytecode (including nested classes), schema and runtime. */
    private static String computeFingerprint() throws IOException {
        var classes = new TreeMap<String, Class<?>>();
        for (Class<?> type : List.of(L2DailyFeatureBatchCli.class, L2DailyFeaturePipeline.class,
                DfcfCsvParser.class, L2DailyFeatureField.class, L2DailyFeatures.class,
                L2DailyFeaturesKey.class, DatasetDefinition.StorageType.class, L2FeatureData.class,
                L2FeatureMath.class, L2NumpySort.class, L2WideFeatures.class, L2SpoofingFeatures.class,
                L2QualityFeatures.class, L2LifecycleFeatures.class, L2IntradayFeatures.class,
                L2TradeSignFeatures.class, L2LobTransitionFeatures.class, L2MicrostructureFeatures.class,
                L2GmmFeatures.class, L2IntensityFeatures.class)) collectClasses(type, classes);
        try { collectClasses(Class.forName("com.zoutrankil.batch.DfcfCsvInspector"), classes); }
        catch (ClassNotFoundException absent) { throw new IOException("Missing production CSV reader", absent); }
        MessageDigest digest = digest();
        update(digest, CHECKPOINT_VERSION + "\n" + System.getProperty("java.version") + "\n" + JSON.version() + "\n");
        for (var entry : classes.entrySet()) {
            update(digest, entry.getKey() + "\n");
            String resource = "/" + entry.getKey().replace('.', '/') + ".class";
            try (InputStream input = entry.getValue().getResourceAsStream(resource)) {
                if (input == null) throw new IOException("Cannot fingerprint loaded computation class: " + resource);
                transferDigest(input, digest);
            }
        }
        return hex(digest);
    }

    private static void collectClasses(Class<?> type, Map<String, Class<?>> classes) throws IOException {
        if (classes.putIfAbsent(type.getName(), type) != null) return;
        for (Class<?> nested : type.getDeclaredClasses()) collectClasses(nested, classes);
        // Reflection excludes javac's enum-switch helpers and anonymous/local classes.
        // Hash their numbered class files as well: changing only a switch mapping must invalidate resume.
        for (int index = 1; ; index++) {
            String name = type.getName() + "$" + index;
            String resource = "/" + name.replace('.', '/') + ".class";
            if (type.getResource(resource) == null) break;
            try { collectClasses(Class.forName(name, false, type.getClassLoader()), classes); }
            catch (ClassNotFoundException absent) { throw new IOException("Cannot fingerprint computation helper: " + name, absent); }
        }
    }

    private static void atomicJson(Path target, Object value) throws IOException {
        atomicText(target, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(value) + "\n");
    }

    private static void atomicText(Path target, String text) throws IOException {
        Path temp = temporary(target);
        try {
            try (FileChannel channel = FileChannel.open(temp, CREATE_NEW, WRITE)) {
                ByteBuffer bytes = ByteBuffer.wrap(text.getBytes(StandardCharsets.UTF_8));
                while (bytes.hasRemaining()) channel.write(bytes);
                channel.force(true);
            }
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private static Path temporary(Path target) { return target.resolveSibling(target.getFileName() + ".tmp-" + UUID.randomUUID()); }
    private static void force(Path path) throws IOException { try (var channel = FileChannel.open(path, WRITE)) { channel.force(true); } }
    private static String sha256(Path file) throws IOException {
        var digest = digest();
        try (InputStream input = Files.newInputStream(file)) { transferDigest(input, digest); }
        return hex(digest);
    }
    private static void transferDigest(InputStream input, MessageDigest digest) throws IOException {
        byte[] bytes = new byte[65536];
        for (int count; (count = input.read(bytes)) != -1;) digest.update(bytes, 0, count);
    }
    private static MessageDigest digest() { try { return MessageDigest.getInstance("SHA-256"); } catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); } }
    private static void update(MessageDigest digest, String text) { digest.update(text.getBytes(StandardCharsets.UTF_8)); }
    private static String hex(MessageDigest digest) { return HexFormat.of().formatHex(digest.digest()); }
    private static String day(LocalDate date) { return date.toString().replace("-", ""); }
    private static LocalDate date(String text) { return LocalDate.parse(text.length() == 8 ? text.substring(0, 4) + "-" + text.substring(4, 6) + "-" + text.substring(6, 8) : text); }
    private static String required(Map<String, String> options, String name) { String value = options.get(name); if (value == null || value.isBlank()) throw new IllegalArgumentException("Required option: " + name); return value; }
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
    private static Map<String, String> options(String[] args) {
        var options = new HashMap<String, String>();
        for (int i = 0; i < args.length; i += 2) {
            if (i + 1 >= args.length || !args[i].startsWith("--")) throw new IllegalArgumentException("Expected --option value pairs");
            if (options.put(args[i], args[i + 1]) != null) throw new IllegalArgumentException("Duplicate option: " + args[i]);
        }
        if (!Set.of("--source-root", "--dates", "--output-root", "--workers", "--max-bytes-per-file", "--resume", "--symbols-file").containsAll(options.keySet()))
            throw new IllegalArgumentException("Unknown batch option");
        return options;
    }

    private record Job(String symbol, List<Path> sources) {}
    private record SourceFingerprint(String sha256, Map<String, Object> files) {}
    private record Outcome(String symbol, String status, boolean resumed, List<String> sourceDirectories,
                           String inputFingerprint, Map<String, Object> inputFiles, String resultSha256,
                           Map<String, Long> rows, double elapsedSeconds, String error) {
        static Outcome failure(String symbol, List<String> sources, Exception failure) {
            return new Outcome(symbol, "FAILED", false, sources, null, Map.of(), null, Map.of(), 0,
                    failure.getClass().getSimpleName() + ": " + failure.getMessage());
        }
        Outcome withElapsed(double seconds) {
            return new Outcome(symbol, status, resumed, sourceDirectories, inputFingerprint, inputFiles, resultSha256, rows, seconds, error);
        }
        Map<String, Object> record() {
            var map = new LinkedHashMap<String, Object>();
            map.put("symbol", symbol);
            map.put("status", status);
            map.put("resumed", resumed);
            map.put("sourceDirectories", sourceDirectories);
            map.put("inputFingerprint", inputFingerprint);
            map.put("inputFiles", inputFiles);
            map.put("resultSha256", resultSha256);
            map.put("rows", rows);
            map.put("elapsedSeconds", elapsedSeconds);
            map.put("error", error);
            return map;
        }
    }
}
