package com.zoutrankil.questdbwithdata.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.zoutrankil.questdbwithdata.domain.JobDefinitionJson;
import com.zoutrankil.questdbwithdata.domain.PageContract;
import com.zoutrankil.questdbwithdata.domain.StockStDaily;
import com.zoutrankil.questdbwithdata.domain.StockStDailyKey;
import com.zoutrankil.questdbwithdata.domain.StockStPeriod;
import com.zoutrankil.questdbwithdata.mapper.StockStDailyMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.function.BooleanSupplier;

/**
 * D012 source route: read bounded natural-year namechange responses from the fixed 2010 anchor,
 * then materialize intervals onto each covered SSE open date. Raw interval rows are never sent to
 * the daily writer as if they were daily records.
 */
public final class StockStDailySource {
    public static final LocalDate HISTORY_ANCHOR = LocalDate.of(2010, 1, 1);
    public static final int API_ROW_CAP = 5000;
    public static final int MAX_RESPONSE_BYTES = 16 * 1024 * 1024;
    public static final int MAX_HISTORY_PERIODS = 100_000;
    public static final int MAX_ROWS_PER_DATE = 10_000;
    public static final int MAX_OUTPUT_ROWS = 1_000_000;
    public static final int MAX_YEAR_WINDOWS = 100;
    public static final long MAX_TOTAL_EVIDENCE_BYTES = 512L * 1024 * 1024;
    public static final List<String> FIELDS = StockStDailyMapper.SOURCE_FIELDS;
    public static final PageContract CONTRACT = new PageContract("namechange", FIELDS,
            List.of("ts_code", "start_date"), Set.of("start_date", "end_date"), PageContract.Paging.NONE,
            PageContract.Completion.SHORT_PAGE, null, null, API_ROW_CAP, API_ROW_CAP, 1, API_ROW_CAP,
            "Python D012 issues one non-paged Tushare namechange request per natural-year start_date/end_date window; "
                    + "a response at 5000 rows is retained as incomplete and rejected because no paging cursor is declared.");

    public record Evidence(String path, String fingerprint, int year, int rows) {
        public Evidence { Objects.requireNonNull(path); Objects.requireNonNull(fingerprint); }
    }
    public record Materialized(LocalDate tradeDate, List<StockStDaily> rows, Path path, String fingerprint) {
        public Materialized { rows = List.copyOf(rows); Objects.requireNonNull(tradeDate); Objects.requireNonNull(path); }
    }
    private final TusharePageService pages;
    private final StockStDailyMapper mapper;
    private final Path evidenceRoot;

    public StockStDailySource(TusharePageService pages, StockStDailyMapper mapper, Path evidenceRoot) {
        this.pages = Objects.requireNonNull(pages); this.mapper = Objects.requireNonNull(mapper);
        this.evidenceRoot = Objects.requireNonNull(evidenceRoot).toAbsolutePath().normalize();
    }

    /** Calls the annual history chain once, then emits one receipt-backed page per SSE session. */
    public int fetchWindow(LocalDate from, LocalDate to, List<LocalDate> sessions,
            SyncJobRunner.PageConsumer<StockStDaily> consumer, BooleanSupplier cancelled) throws Exception {
        Objects.requireNonNull(from); Objects.requireNonNull(to); Objects.requireNonNull(sessions);
        Objects.requireNonNull(consumer); Objects.requireNonNull(cancelled);
        if (from.isAfter(to) || from.isBefore(HISTORY_ANCHOR)
                || sessions.size() > StockStDailySyncJobOwner.MAX_WINDOW_DAYS
                || !sessions.equals(sessions.stream().distinct().sorted().toList())
                || sessions.stream().anyMatch(day -> day.isBefore(from) || day.isAfter(to)))
            throw new IllegalArgumentException("Invalid bounded stk_st_daily materialization window");
        Files.createDirectories(evidenceRoot);
        if (sessions.isEmpty()) return 0;

        var yearEvidence = new ArrayList<Evidence>();
        var stPeriods = new LinkedHashSet<StockStPeriod>();
        int firstYear = HISTORY_ANCHOR.getYear(), lastYear = to.getYear();
        if (lastYear - firstYear + 1 > MAX_YEAR_WINDOWS)
            throw new IllegalArgumentException("stk_st_daily annual namechange scan exceeds its bound");
        long totalEvidenceBytes = 0;
        for (int year = firstYear; year <= lastYear; year++) {
            checkCancelled(cancelled);
            var saved = fetchYear(year, to, cancelled);
            totalEvidenceBytes = Math.addExact(totalEvidenceBytes, Files.size(Path.of(saved.evidence().path())));
            if (totalEvidenceBytes > MAX_TOTAL_EVIDENCE_BYTES)
                throw new IllegalStateException("D012 raw annual evidence exceeds 512 MiB per-run bound");
            yearEvidence.add(saved.evidence());
            for (var raw : saved.rows()) {
                StockStPeriod period = mapper.period(raw);
                if (period.isStName() && period.overlaps(from, to) && stPeriods.add(period)
                        && stPeriods.size() > MAX_HISTORY_PERIODS)
                    throw new IllegalStateException("ST interval inventory exceeds its bounded run budget");
            }
        }
        var yearRefs = yearEvidence.stream().map(e -> Map.<String,Object>of("year", e.year(),
                "file", Path.of(e.path()).getFileName().toString(), "fingerprint", e.fingerprint(),
                "returnedRows", e.rows())).toList();
        long totalRows = 0;
        int emitted = 0;
        for (LocalDate day : sessions) {
            checkCancelled(cancelled);
            var keys = new TreeSet<StockStDailyKey>(Comparator.comparing(StockStDailyKey::tsCode)
                    .thenComparing(StockStDailyKey::timestamp));
            for (StockStPeriod period : stPeriods) {
                if (period.activeOn(day, to)) keys.add(new StockStDailyKey(period.tsCode(), day));
            }
            if (keys.size() > MAX_ROWS_PER_DATE)
                throw new IllegalStateException("One stk_st_daily session exceeds its 10000-row bound");
            var dailyRows = keys.stream().map(key -> new StockStDaily(key, 1)).toList();
            totalRows = Math.addExact(totalRows, dailyRows.size());
            if (totalRows > MAX_OUTPUT_ROWS)
                throw new IllegalStateException("stk_st_daily materialized output exceeds its bounded row budget");
            List<Map<String,Object>> boundedYearRefs = new ArrayList<>(yearRefs.size());
            for (var ref : yearRefs) boundedYearRefs.add(new LinkedHashMap<>(ref));
            Materialized page = persistDaily(from, to, day, boundedYearRefs, dailyRows);
            totalEvidenceBytes = Math.addExact(totalEvidenceBytes, Files.size(page.path()));
            if (totalEvidenceBytes > MAX_TOTAL_EVIDENCE_BYTES)
                throw new IllegalStateException("D012 total source evidence exceeds 512 MiB per-run bound");
            consumer.accept(new SyncJobRunner.Page<>(page.rows(), page.fingerprint(), page.path().toString(),
                    day.format(DateTimeFormatter.BASIC_ISO_DATE)));
            emitted++;
        }
        return emitted;
    }

    /** Reopens and re-derives one persisted date page only from its raw annual Tushare receipts. */
    public static Materialized reopen(Path receipt, String expectedFingerprint, LocalDate expectedDate) throws Exception {
        Objects.requireNonNull(receipt); Objects.requireNonNull(expectedDate);
        if (expectedFingerprint == null || !expectedFingerprint.matches("[0-9a-f]{64}")
                || !Files.isRegularFile(receipt) || Files.size(receipt) > MAX_RESPONSE_BYTES)
            throw new IllegalArgumentException("Bounded stk_st_daily daily receipt and SHA-256 required");
        Path root = receipt.toAbsolutePath().normalize().getParent().toRealPath();
        Path realReceipt = receipt.toRealPath();
        if (!realReceipt.getParent().equals(root)) throw new IllegalStateException("D012 daily receipt escaped its evidence directory");
        byte[] bytes = Files.readAllBytes(realReceipt);
        String actual = sha256(bytes);
        if (!actual.equals(expectedFingerprint)
                || !realReceipt.getFileName().toString().equals("stk-st-daily-"
                + expectedDate.format(DateTimeFormatter.BASIC_ISO_DATE) + "-" + actual + ".json"))
            throw new IllegalStateException("D012 daily receipt fingerprint/content identity changed");
        var json = JobDefinitionJson.mapper(); JsonNode body = json.readTree(bytes);
        LocalDate from = parseDate(body.path("from").asText());
        LocalDate to = parseDate(body.path("to").asText());
        if (!"tushare.namechange".equals(body.path("sourceKind").asText())
                || !"namechange".equals(body.path("endpoint").asText())
                || !body.path("sourceComplete").asBoolean(false)
                || !expectedDate.toString().equals(body.path("tradeDate").asText())
                || !HISTORY_ANCHOR.toString().equals(body.path("historyAnchor").asText())
                || from.isAfter(expectedDate) || to.isBefore(expectedDate)
                || !json.valueToTree(FIELDS).equals(body.path("fields"))
                || !body.path("historyReceipts").isArray()
                || !body.path("dailyRows").isArray() || body.path("dailyRows").size() > MAX_ROWS_PER_DATE)
            throw new IllegalStateException("D012 daily receipt scope/completion differs");

        var refs = body.path("historyReceipts");
        int years = to.getYear() - HISTORY_ANCHOR.getYear() + 1;
        if (refs.size() != years || years > MAX_YEAR_WINDOWS)
            throw new IllegalStateException("D012 daily receipt does not retain every annual namechange response");
        var periods = new LinkedHashSet<StockStPeriod>();
        for (int index = 0; index < refs.size(); index++) {
            JsonNode ref = refs.get(index);
            int year = HISTORY_ANCHOR.getYear() + index;
            if (ref.path("year").asInt(-1) != year)
                throw new IllegalStateException("D012 annual namechange receipt sequence has a gap");
            String file = requiredText(ref, "file");
            if (file.contains("/") || file.contains("\\") || !Path.of(file).getFileName().toString().equals(file))
                throw new IllegalStateException("D012 raw annual receipt reference must be one relative filename");
            Path candidate = root.resolve(file).normalize();
            if (!candidate.startsWith(root) || !Files.isRegularFile(candidate))
                throw new IllegalStateException("D012 raw annual receipt is absent or outside its page evidence root");
            Path rawPath = candidate.toRealPath();
            if (!rawPath.startsWith(root)) throw new IllegalStateException("D012 raw annual receipt escaped its evidence directory");
            var annual = reopenYear(rawPath, requiredText(ref, "fingerprint"), year, to);
            if (annual.size() != ref.path("returnedRows").asInt(-1))
                throw new IllegalStateException("D012 raw annual receipt row count changed");
            for (var raw : annual) {
                StockStPeriod period = new StockStDailyMapper().period(raw);
                if (period.isStName() && period.overlaps(from, to)) periods.add(period);
            }
        }
        var expected = new TreeSet<StockStDailyKey>(Comparator.comparing(StockStDailyKey::tsCode)
                .thenComparing(StockStDailyKey::timestamp));
        for (StockStPeriod period : periods) if (period.activeOn(expectedDate, to))
            expected.add(new StockStDailyKey(period.tsCode(), expectedDate));
        var actualRows = new ArrayList<StockStDaily>(); var actualKeys = new HashSet<StockStDailyKey>();
        for (JsonNode row : body.path("dailyRows")) {
            if (!row.path("ts_code").isTextual() || !row.path("timestamp").isTextual()
                    || !row.path("is_st").canConvertToInt()) throw new IllegalArgumentException("Malformed D012 daily row receipt");
            var typed = new StockStDaily(row.path("ts_code").asText(), parseDate(row.path("timestamp").asText()),
                    row.path("is_st").asInt());
            if (!typed.timestamp().equals(expectedDate) || !actualKeys.add(typed.key()))
                throw new IllegalStateException("Duplicate/out-of-scope D012 daily receipt key");
            actualRows.add(typed);
        }
        if (!actualKeys.equals(expected)) throw new IllegalStateException("D012 daily rows differ from raw interval expansion");
        return new Materialized(expectedDate, actualRows, realReceipt, actual);
    }

    private record Annual(List<Map<String,JsonNode>> rows, Evidence evidence) {}
    private Annual fetchYear(int year, LocalDate through, BooleanSupplier cancelled) throws Exception {
        LocalDate start = LocalDate.of(year, 1, 1);
        LocalDate end = LocalDate.of(year, 12, 31);
        if (year == through.getYear()) end = through;
        var parameters = Map.<String,Object>of("start_date", start.format(DateTimeFormatter.BASIC_ISO_DATE),
                "end_date", end.format(DateTimeFormatter.BASIC_ISO_DATE));
        var rawRows = new ArrayList<Map<String,JsonNode>>(); var captured = new PageExecutor.Page[1];
        var fetcher = pages.fetcher(CONTRACT, cancelled);
        PageExecutor.Completed complete;
        try {
            complete = new PageExecutor().execute(CONTRACT, parameters, request -> {
                PageExecutor.Page response = fetcher.fetch(request); captured[0] = response; return response;
            }, (page, pageReceipt) -> rawRows.addAll(page.rows()), row -> validate(row), cancelled);
        } catch (Exception failure) {
            try {
                persistAnnual(year, parameters, captured[0], false, failure);
            } catch (Exception evidenceFailure) {
                failure.addSuppressed(evidenceFailure);
            }
            throw failure;
        }
        if (captured[0] == null || complete.pages() != 1 || complete.rows() != rawRows.size())
            throw new IllegalStateException("namechange year did not produce one complete response: " + year);
        Path path = persistAnnual(year, parameters, captured[0], true, null);
        return new Annual(List.copyOf(rawRows), new Evidence(path.toString(), sha256(Files.readAllBytes(path)), year, rawRows.size()));
    }

    private Path persistAnnual(int year, Map<String,Object> parameters, PageExecutor.Page response,
            boolean complete, Exception failure) throws Exception {
        var body = new LinkedHashMap<String,Object>();
        body.put("sourceKind", "tushare"); body.put("endpoint", "namechange");
        body.put("year", year); body.put("parameters", parameters); body.put("fields", FIELDS);
        // Incomplete pages are diagnostic evidence: preserve malformed source values exactly as received.
        // Validation/canonicalization here would mask the original source failure and lose the raw payload.
        List<Map<String,JsonNode>> orderedRows = response == null ? List.of()
                : complete ? canonicalRawRows(response.rows()) : new ArrayList<>(response.rows());
        body.put("returnedRows", orderedRows.size());
        body.put("rawRows", orderedRows);
        if (complete) body.put("rawRowsFingerprint", sha256(JobDefinitionJson.mapper()
                .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true).writeValueAsBytes(orderedRows)));
        body.put("sourceComplete", complete); body.put("apiMaximumRows", API_ROW_CAP);
        body.put("explicitEnd", response != null && response.explicitEnd());
        if (failure != null) {
            body.put("evidenceStatus", "unverified_raw_response");
            body.put("failureCategory", failure.getClass().getSimpleName());
            body.put("failure", failure.getMessage() == null ? "source failed" : failure.getMessage());
        }
        byte[] bytes = JobDefinitionJson.mapper().configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true)
                .writeValueAsBytes(body);
        if (bytes.length > MAX_RESPONSE_BYTES) throw new IllegalArgumentException("D012 source evidence exceeds 16 MiB");
        Files.createDirectories(evidenceRoot);
        Path output;
        if (complete) {
            String contentFingerprint = (String) body.get("rawRowsFingerprint");
            output = evidenceRoot.resolve("namechange-" + year + "-" + contentFingerprint + ".json");
            writeDeterministic(output, bytes);
        } else {
            output = evidenceRoot.resolve("incomplete-namechange-" + year + "-" + UUID.randomUUID() + ".json");
            Files.write(output, bytes, StandardOpenOption.CREATE_NEW);
        }
        return output;
    }

    private Materialized persistDaily(LocalDate from, LocalDate to, LocalDate day,
            List<Map<String,Object>> yearRefs, List<StockStDaily> rows) throws Exception {
        var body = new LinkedHashMap<String,Object>();
        body.put("sourceKind", "tushare.namechange"); body.put("endpoint", "namechange");
        body.put("from", from); body.put("to", to); body.put("tradeDate", day); body.put("fields", FIELDS);
        body.put("historyAnchor", HISTORY_ANCHOR); body.put("historyReceipts", yearRefs);
        body.put("sourceComplete", true); body.put("dailyRows", rows.stream().map(row -> Map.of(
                "ts_code", row.tsCode(), "timestamp", row.timestamp(), "is_st", row.isSt())).toList());
        byte[] bytes = JobDefinitionJson.mapper().configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true)
                .writeValueAsBytes(body);
        if (bytes.length > MAX_RESPONSE_BYTES) throw new IllegalArgumentException("D012 daily page receipt exceeds 16 MiB");
        String fingerprint = sha256(bytes);
        Path output = evidenceRoot.resolve("stk-st-daily-" + day.format(DateTimeFormatter.BASIC_ISO_DATE)
                + "-" + fingerprint + ".json");
        writeDeterministic(output, bytes);
        return new Materialized(day, rows, output, fingerprint);
    }

    private static List<Map<String,JsonNode>> reopenYear(Path path, String fingerprint, int year, LocalDate through)
            throws Exception {
        if (!fingerprint.matches("[0-9a-f]{64}") || Files.size(path) > MAX_RESPONSE_BYTES)
            throw new IllegalArgumentException("Invalid bounded D012 annual receipt fingerprint/size");
        byte[] bytes = Files.readAllBytes(path);
        if (!sha256(bytes).equals(fingerprint)) throw new IllegalStateException("D012 annual source receipt fingerprint changed");
        var json = JobDefinitionJson.mapper(); JsonNode body = json.readTree(bytes);
        LocalDate start = LocalDate.of(year,1,1), end = year == through.getYear() ? through : LocalDate.of(year,12,31);
        if (!"tushare".equals(body.path("sourceKind").asText()) || !"namechange".equals(body.path("endpoint").asText())
                || body.path("year").asInt(-1) != year || !body.path("sourceComplete").asBoolean(false)
                || !json.valueToTree(FIELDS).equals(body.path("fields")) || !body.path("rawRows").isArray()
                || body.path("rawRows").size() >= API_ROW_CAP
                || body.path("returnedRows").asInt(-1) != body.path("rawRows").size()
                || !start.format(DateTimeFormatter.BASIC_ISO_DATE).equals(body.path("parameters").path("start_date").asText())
                || !end.format(DateTimeFormatter.BASIC_ISO_DATE).equals(body.path("parameters").path("end_date").asText()))
            throw new IllegalStateException("D012 annual receipt range/cap/completion differs");
        var rows = json.convertValue(body.path("rawRows"), new TypeReference<List<Map<String,JsonNode>>>() {});
        rows = canonicalRawRows(rows);
        String rawFingerprint = sha256(json.configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true)
                .writeValueAsBytes(rows));
        if (!rawFingerprint.equals(body.path("rawRowsFingerprint").asText())
                || !path.getFileName().toString().equals("namechange-" + year + "-" + rawFingerprint + ".json"))
            throw new IllegalStateException("D012 deterministic annual receipt content identity differs");
        var mapper = new StockStDailyMapper(); var unique = new HashSet<String>();
        for (var row : rows) {
            validate(row); var period = mapper.period(row);
            String key = period.tsCode() + "\u0000" + period.startDate() + "\u0000" + period.name()
                    + "\u0000" + period.endDate();
            if (!unique.add(key)) throw new IllegalStateException("Duplicate namechange interval in D012 raw receipt");
        }
        return List.copyOf(rows);
    }

    private static void validate(Map<String,JsonNode> row) {
        new StockStDailyMapper().period(row);
    }
    private static List<Map<String,JsonNode>> canonicalRawRows(List<Map<String,JsonNode>> rows) {
        var ordered = new ArrayList<Map<String,JsonNode>>(rows.size());
        for (var row : rows) {
            validate(row);
            var canonical = new LinkedHashMap<String,JsonNode>();
            for (String field : FIELDS) canonical.put(field, row.get(field));
            ordered.add(Collections.unmodifiableMap(canonical));
        }
        ordered.sort(Comparator.comparing((Map<String,JsonNode> row) -> rawText(row.get("ts_code")))
                .thenComparing(row -> rawText(row.get("start_date")))
                .thenComparing(row -> rawText(row.get("name")))
                .thenComparing(row -> rawText(row.get("end_date"))));
        return List.copyOf(ordered);
    }
    private static String rawText(JsonNode node) {
        if (node == null) return "0";
        if (node.isNull()) return "1";
        return "2" + node.asText();
    }
    private static void writeDeterministic(Path output, byte[] bytes) throws Exception {
        try {
            Files.write(output, bytes, StandardOpenOption.CREATE_NEW);
        } catch (java.nio.file.FileAlreadyExistsException exists) {
            if (!Files.isRegularFile(output) || !MessageDigest.isEqual(bytes, Files.readAllBytes(output)))
                throw new IllegalStateException("D012 deterministic evidence filename already contains different bytes", exists);
        }
    }
    private static void checkCancelled(BooleanSupplier cancelled) {
        if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted())
            throw new java.util.concurrent.CancellationException("stk_st_daily source cancelled");
    }
    private static LocalDate parseDate(String text) {
        try { return LocalDate.parse(text); }
        catch (RuntimeException invalid) { throw new IllegalArgumentException("ISO calendar date required", invalid); }
    }
    private static String requiredText(JsonNode node, String field) {
        if (!node.path(field).isTextual() || node.path(field).asText().isBlank())
            throw new IllegalArgumentException("D012 receipt text required: " + field);
        return node.path(field).asText();
    }
    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
