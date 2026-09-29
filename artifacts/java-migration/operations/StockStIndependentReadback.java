import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.zoutrankil.questdbwithdata.domain.JobDefinitionJson;
import com.zoutrankil.questdbwithdata.domain.SyncRunState;
import com.zoutrankil.questdbwithdata.repository.SyncRunLedger;
import com.zoutrankil.questdbwithdata.service.StaticTargetIdentity;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Operational D012 source-to-QuestDB verifier. It reads raw namechange receipts and frozen
 * SSE sessions directly; it deliberately does not use the production mapper/source/write port.
 */
public final class StockStIndependentReadback {
    private static final List<String> RAW_FIELDS = List.of("ts_code", "name", "start_date", "end_date");
    private static final List<String> PHYSICAL_FIELDS = List.of("ts_code", "timestamp", "is_st");
    private static final LocalDate HISTORY_ANCHOR = LocalDate.of(2010, 1, 1);
    private static final int MAX_SESSIONS = 366;
    private static final int MAX_ANNUAL_ROWS = 5_000;
    private static final int MAX_ROWS = 1_000_000;
    private static final long MAX_RECEIPT_BYTES = 16L * 1024 * 1024;
    private static final long MAX_EVIDENCE_BYTES = 512L * 1024 * 1024;
    private static final DateTimeFormatter BASIC = DateTimeFormatter.BASIC_ISO_DATE;

    private StockStIndependentReadback() {}

    public static Map<String, Object> verify(JdbcTemplate jdbc, Path ledger, String table, String runId)
            throws Exception {
        Objects.requireNonNull(jdbc); Objects.requireNonNull(ledger); Objects.requireNonNull(table); Objects.requireNonNull(runId);
        if (!table.matches("java_d012_stk_st_daily_[A-Za-z0-9_]+"))
            throw new IllegalArgumentException("Dedicated D012 isolated target required");
        Path ledgerPath = ledger.toAbsolutePath().normalize();
        var json = JobDefinitionJson.mapper();
        var history = SyncRunLedger.openReadOnly(ledgerPath);
        var runEntry = history.get(runId);
        var run = history.getRun(runId);
        if (!Set.of(SyncRunState.VERIFIED, SyncRunState.VERIFIED_EMPTY).contains(runEntry.state())
                || !"data.stk_st_daily".equals(run.jobId()) || run.jobVersion() != 1)
            throw new IllegalStateException("A verified D012 run is required");
        JsonNode frozen = json.readTree(run.frozenJson());
        if (!"data.stk_st_daily".equals(frozen.path("definition").path("jobId").asText())
                || frozen.path("definition").path("version").asInt(-1) != 1)
            throw new IllegalStateException("Frozen request definition differs from D012");
        LocalDate from = iso(frozen.path("from").asText());
        LocalDate to = iso(frozen.path("to").asText());
        if (from.isAfter(to) || from.isBefore(HISTORY_ANCHOR) || to.isAfter(iso(frozen.path("logicalDate").asText())))
            throw new IllegalStateException("Frozen D012 date bounds are invalid");
        JsonNode parameters = frozen.path("parameters");
        String logicalTarget = requiredText(parameters, "targetId");
        String frozenPhysicalTarget = requiredText(parameters, "physicalTargetId");
        if (!logicalTarget.equals(run.targetId()) || !logicalTarget.matches("d012-logical-v1-[0-9a-f]{64}")
                || !frozenPhysicalTarget.matches("static-v2-[0-9a-f]{64}"))
            throw new IllegalStateException("Frozen logical/physical target binding differs");
        List<LocalDate> sessions = sessions(parameters.path("trade_dates").asText(), from, to);

        Path evidenceRoot = ledgerPath.getParent().resolve("sync-evidence").resolve(runId).resolve("source").toRealPath();
        var fetchedByDate = fetchedPages(history, runId, sessions);
        var annualByYear = new TreeMap<Integer, Annual>();
        var expected = new TreeMap<Key, Integer>();
        var pageFingerprints = new ArrayList<String>();
        var pageReceipts = new ArrayList<Map<String, Object>>();
        long evidenceBytes = 0;
        Set<Path> hashedAnnualFiles = new HashSet<>();
        long rawAnnualRows = 0;
        int sourcePageRows = 0;

        for (LocalDate day : sessions) {
            JsonNode fetched = fetchedByDate.get(day);
            ReceiptFile dailyFile = readEvidence(evidenceRoot, requiredText(fetched, "responseEvidence"),
                    requiredText(fetched, "sourceFingerprint"), "stk-st-daily-" + BASIC.format(day) + "-");
            evidenceBytes = Math.addExact(evidenceBytes, dailyFile.bytes().length);
            JsonNode daily = dailyFile.body();
            if (!"tushare.namechange".equals(daily.path("sourceKind").asText())
                    || !"namechange".equals(daily.path("endpoint").asText())
                    || !daily.path("sourceComplete").asBoolean(false)
                    || !from.toString().equals(daily.path("from").asText())
                    || !to.toString().equals(daily.path("to").asText())
                    || !day.toString().equals(daily.path("tradeDate").asText())
                    || !HISTORY_ANCHOR.toString().equals(daily.path("historyAnchor").asText())
                    || !json.valueToTree(RAW_FIELDS).equals(daily.path("fields"))
                    || !daily.path("historyReceipts").isArray() || !daily.path("dailyRows").isArray()
                    || daily.path("dailyRows").size() != fetched.path("returnedRows").asInt(-1))
                throw new IllegalStateException("D012 daily receipt scope or fetched-row count differs for " + day);
            pageFingerprints.add(dailyFile.sha256());
            pageReceipts.add(Map.of("tradeDate", day.toString(), "path", dailyFile.path().toString(),
                    "fingerprint", dailyFile.sha256(), "rows", daily.path("dailyRows").size()));

            int expectedYears = to.getYear() - HISTORY_ANCHOR.getYear() + 1;
            JsonNode refs = daily.path("historyReceipts");
            if (expectedYears < 1 || expectedYears > 100 || refs.size() != expectedYears)
                throw new IllegalStateException("D012 daily receipt has incomplete annual history references");
            for (int i = 0; i < refs.size(); i++) {
                JsonNode ref = refs.get(i);
                int year = HISTORY_ANCHOR.getYear() + i;
                if (ref.path("year").asInt(-1) != year)
                    throw new IllegalStateException("D012 annual history references have a year gap");
                String file = requiredText(ref, "file");
                if (file.contains("/") || file.contains("\\") || !Path.of(file).getFileName().toString().equals(file))
                    throw new IllegalStateException("D012 annual receipt reference must be one relative filename");
                Path annualPath = evidenceRoot.resolve(file).normalize();
                if (!annualPath.startsWith(evidenceRoot)) throw new IllegalStateException("Annual receipt escaped source evidence root");
                Annual annual = annualByYear.get(year);
                if (annual == null) {
                    ReceiptFile annualFile = readEvidence(evidenceRoot, annualPath.toString(),
                            requiredText(ref, "fingerprint"), "namechange-" + year + "-");
                    JsonNode raw = annualFile.body();
                    validateAnnual(raw, year, to);
                    List<Map<String, JsonNode>> rows = canonicalRawRows(raw.path("rawRows"));
                    if (rows.size() != ref.path("returnedRows").asInt(-1))
                        throw new IllegalStateException("D012 annual raw row count differs from daily reference");
                    String contentHash = sha256(json.configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true)
                            .writeValueAsBytes(rows));
                    if (!contentHash.equals(raw.path("rawRowsFingerprint").asText())
                            || !file.equals("namechange-" + year + "-" + contentHash + ".json"))
                        throw new IllegalStateException("D012 annual canonical raw-row fingerprint differs");
                    annual = new Annual(annualFile, rows);
                    annualByYear.put(year, annual);
                    if (hashedAnnualFiles.add(annualFile.path())) {
                        evidenceBytes = Math.addExact(evidenceBytes, annualFile.bytes().length);
                        rawAnnualRows = Math.addExact(rawAnnualRows, rows.size());
                    }
                } else if (!annual.file().sha256().equals(ref.path("fingerprint").asText())
                        || !annual.file().path().getFileName().toString().equals(file)) {
                    throw new IllegalStateException("D012 annual reference changed across session receipts");
                }
            }
            if (evidenceBytes > MAX_EVIDENCE_BYTES)
                throw new IllegalStateException("D012 source evidence exceeds its 512 MiB run bound");
        }

        for (Annual annual : annualByYear.values()) {
            for (Map<String, JsonNode> row : annual.rows()) {
                String name = pythonText(row.get("name"));
                if (!name.toUpperCase(Locale.ROOT).contains("ST")) continue;
                String code = pythonText(row.get("ts_code"));
                if (!code.matches("[0-9]{6}\\.(?:SH|SZ|BJ)"))
                    throw new IllegalStateException("ST namechange row has a non-numeric listed-stock code: " + code);
                LocalDate start = sourceDate(row.get("start_date"), false);
                LocalDate end = sourceDate(row.get("end_date"), true);
                if (start == null) throw new IllegalStateException("ST interval start_date is blank");
                if (end != null && end.isBefore(start)) throw new IllegalStateException("ST interval end_date precedes start_date");
                if (end == null) end = to;
                if (start.isAfter(to) || end.isBefore(from)) continue;
                for (LocalDate day : sessions) {
                    if (!day.isBefore(start) && !day.isAfter(end)) {
                        expected.put(new Key(code, day), 1);
                        if (expected.size() > MAX_ROWS) throw new IllegalStateException("D012 source expansion exceeds one-million row bound");
                    }
                }
            }
        }
        sourcePageRows = expected.size();
        verifyDailyMaterializations(json, sessions, fetchedByDate, expected, pageReceipts);
        String combinedFingerprint = combinedFingerprint(pageFingerprints);

        Publication publication = publication(jdbc, ledgerPath, runId, table, logicalTarget, frozenPhysicalTarget,
                from, to, sessions, sourcePageRows, combinedFingerprint, evidenceRoot, pageReceipts, json);
        var actual = readPhysical(jdbc, table, from, to);
        Comparison comparison = compare(expected, actual.rows());

        var report = new LinkedHashMap<String, Object>();
        report.put("task", "D012"); report.put("runId", runId); report.put("status", comparison.mismatches() == 0
                && comparison.duplicateKeys() == 0 && comparison.missingKeys() == 0 && comparison.unexpectedKeys() == 0
                ? "MATCHED" : "MISMATCH");
        report.put("target", table); report.put("logicalTargetId", logicalTarget);
        report.put("frozenPhysicalTargetId", frozenPhysicalTarget);
        report.put("journalStagePhysicalTargetId", publication.journalStagePhysicalTarget());
        report.put("currentPhysicalTargetId", publication.currentPhysicalTarget());
        report.put("currentGenerationMatchesRun", publication.journalStagePhysicalTarget()
                .equals(publication.currentPhysicalTarget()));
        report.put("publicationId", publication.id()); report.put("publicationState", publication.state());
        report.put("requestFrom", from.toString()); report.put("requestTo", to.toString());
        report.put("tradeDates", sessions.stream().map(LocalDate::toString).toList());
        report.put("sourcePages", sessions.size()); report.put("sourceRows", expected.size());
        report.put("rawAnnualRows", rawAnnualRows); report.put("annualReceiptCount", annualByYear.size());
        report.put("sourceFingerprint", combinedFingerprint); report.put("sourceEvidenceBytes", evidenceBytes);
        report.put("actualRows", actual.rows().size()); report.put("matches", comparison.matches());
        report.put("mismatches", comparison.mismatches()); report.put("duplicateKeys", comparison.duplicateKeys());
        report.put("missingKeys", comparison.missingKeys()); report.put("unexpectedKeys", comparison.unexpectedKeys());
        report.put("mismatchSamples", comparison.samples()); report.put("comparedColumns", PHYSICAL_FIELDS);
        report.put("query", actual.query()); report.put("sourceReceipts", pageReceipts);
        report.put("stageReceipt", publication.stageReceipt());
        return Map.copyOf(report);
    }

    private static Map<LocalDate, JsonNode> fetchedPages(SyncRunLedger ledger, String runId,
            List<LocalDate> sessions) throws Exception {
        var all = new ArrayList<SyncRunLedger.Entry>();
        String cursor = null;
        while (true) {
            List<SyncRunLedger.Entry> page = ledger.entries(runId, cursor, 1000);
            all.addAll(page);
            if (page.size() < 1000) break;
            cursor = page.getLast().id();
        }
        var found = new TreeMap<LocalDate, JsonNode>();
        var json = JobDefinitionJson.mapper();
        for (var entry : all) {
            if (entry.kind() != SyncRunLedger.Kind.SLICE) continue;
            List<SyncRunLedger.Event> events = ledger.events(entry.id(), -1, 1000);
            JsonNode fetched = null;
            for (var event : events) if (event.state() == SyncRunState.FETCHED) {
                if (fetched != null) throw new IllegalStateException("D012 slice has multiple FETCHED events: " + entry.id());
                fetched = json.readTree(event.payloadJson());
            }
            if (fetched == null) throw new IllegalStateException("D012 slice has no FETCHED receipt: " + entry.id());
            if (!Set.of(SyncRunState.VERIFIED, SyncRunState.VERIFIED_EMPTY).contains(entry.state()))
                throw new IllegalStateException("D012 slice is not verified: " + entry.id());
            String cursorText = requiredText(fetched, "cursor");
            if (!cursorText.matches("[0-9]{8}")) throw new IllegalStateException("D012 FETCHED cursor is not a trade date");
            LocalDate date = LocalDate.parse(cursorText, BASIC);
            if (fetched.path("returnedRows").asInt(-1) < 0 || found.putIfAbsent(date, fetched) != null)
                throw new IllegalStateException("D012 FETCHED page count/key is invalid");
        }
        if (!found.keySet().equals(new TreeSet<>(sessions)))
            throw new IllegalStateException("D012 ledger FETCHED dates differ from the frozen SSE date list");
        return Map.copyOf(found);
    }

    private static List<LocalDate> sessions(String encoded, LocalDate from, LocalDate to) {
        if ("NONE".equals(encoded)) return List.of();
        if (encoded == null || encoded.isBlank()) throw new IllegalStateException("Frozen D012 sessions are absent");
        List<LocalDate> dates = Arrays.stream(encoded.split(",", -1)).map(value -> {
            if (!value.matches("[0-9]{8}")) throw new IllegalStateException("Frozen D012 session date is malformed");
            return LocalDate.parse(value, BASIC);
        }).toList();
        if (dates.size() > MAX_SESSIONS || dates.stream().distinct().count() != dates.size()
                || !dates.equals(dates.stream().sorted().toList())
                || dates.stream().anyMatch(date -> date.isBefore(from) || date.isAfter(to)))
            throw new IllegalStateException("Frozen D012 sessions are duplicate, unordered or out of bounds");
        return dates;
    }

    private static ReceiptFile readEvidence(Path root, String rawPath, String fingerprint, String namePrefix)
            throws Exception {
        if (fingerprint == null || !fingerprint.matches("[0-9a-f]{64}"))
            throw new IllegalStateException("D012 evidence SHA-256 is malformed");
        Path candidate = Path.of(rawPath).toAbsolutePath().normalize();
        Path real = candidate.toRealPath();
        if (!real.startsWith(root) || !real.getParent().equals(root) || !Files.isRegularFile(real))
            throw new IllegalStateException("D012 source evidence is outside its per-run source directory");
        long size = Files.size(real);
        if (size == 0 || size > MAX_RECEIPT_BYTES) throw new IllegalStateException("D012 source receipt size is outside bound");
        byte[] bytes = Files.readAllBytes(real);
        String actual = sha256(bytes);
        String name = real.getFileName().toString();
        boolean dailyName = namePrefix.startsWith("stk-st-daily-") && name.equals(namePrefix + actual + ".json");
        boolean annualName = namePrefix.startsWith("namechange-")
                && name.matches(java.util.regex.Pattern.quote(namePrefix) + "[0-9a-f]{64}\\.json");
        if (!actual.equals(fingerprint) || !(dailyName || annualName))
            throw new IllegalStateException("D012 source receipt filename/hash differs from FETCHED evidence");
        return new ReceiptFile(real, actual, bytes, JobDefinitionJson.mapper().readTree(bytes));
    }

    private static void validateAnnual(JsonNode receipt, int year, LocalDate through) {
        LocalDate start = LocalDate.of(year, 1, 1);
        LocalDate end = year == through.getYear() ? through : LocalDate.of(year, 12, 31);
        if (!"tushare".equals(receipt.path("sourceKind").asText())
                || !"namechange".equals(receipt.path("endpoint").asText())
                || receipt.path("year").asInt(-1) != year || !receipt.path("sourceComplete").asBoolean(false)
                || !JobDefinitionJson.mapper().valueToTree(RAW_FIELDS).equals(receipt.path("fields"))
                || !start.format(BASIC).equals(receipt.path("parameters").path("start_date").asText())
                || !end.format(BASIC).equals(receipt.path("parameters").path("end_date").asText())
                || !receipt.path("rawRows").isArray() || receipt.path("rawRows").size() >= MAX_ANNUAL_ROWS
                || receipt.path("returnedRows").asInt(-1) != receipt.path("rawRows").size()
                || receipt.path("apiMaximumRows").asInt(-1) != MAX_ANNUAL_ROWS)
            throw new IllegalStateException("D012 annual receipt query scope/completion/cap differs for " + year);
    }

    private static List<Map<String, JsonNode>> canonicalRawRows(JsonNode rawRows) {
        var rows = new ArrayList<Map<String, JsonNode>>(rawRows.size());
        for (JsonNode node : rawRows) {
            if (!node.isObject()) throw new IllegalStateException("D012 annual raw row is not an object");
            var names = new TreeSet<String>(); node.fieldNames().forEachRemaining(names::add);
            if (!names.equals(new TreeSet<>(RAW_FIELDS))) throw new IllegalStateException("D012 annual raw fields differ");
            var row = new LinkedHashMap<String, JsonNode>();
            for (String field : RAW_FIELDS) row.put(field, node.get(field));
            rows.add(row);
        }
        rows.sort(Comparator.comparing((Map<String, JsonNode> row) -> rawSortText(row.get("ts_code")))
                .thenComparing(row -> rawSortText(row.get("start_date")))
                .thenComparing(row -> rawSortText(row.get("name")))
                .thenComparing(row -> rawSortText(row.get("end_date"))));
        return List.copyOf(rows);
    }

    private static String rawSortText(JsonNode value) {
        if (value == null) return "0";
        if (value.isNull()) return "1";
        return "2" + value.asText();
    }

    private static void verifyDailyMaterializations(com.fasterxml.jackson.databind.ObjectMapper json,
            List<LocalDate> sessions, Map<LocalDate, JsonNode> fetched,
            Map<Key, Integer> expected, List<Map<String, Object>> pageReceipts) throws Exception {
        int pageIndex = 0;
        for (LocalDate day : sessions) {
            byte[] bytes = Files.readAllBytes(Path.of(pageReceipts.get(pageIndex++).get("path").toString()));
            if (!sha256(bytes).equals(fetched.get(day).path("sourceFingerprint").asText()))
                throw new IllegalStateException("D012 daily receipt changed during independent verification");
            JsonNode page = json.readTree(bytes);
            var got = new TreeSet<Key>();
            for (JsonNode row : page.path("dailyRows")) {
                if (!row.isObject() || !row.path("ts_code").isTextual() || !row.path("timestamp").isTextual()
                        || !row.path("is_st").isIntegralNumber() || row.path("is_st").asInt() != 1)
                    throw new IllegalStateException("D012 daily materialized source row is malformed");
                LocalDate date = iso(row.path("timestamp").asText());
                if (!date.equals(day) || !got.add(new Key(row.path("ts_code").asText(), date)))
                    throw new IllegalStateException("D012 daily source receipt has duplicate/out-of-date keys");
            }
            var should = new TreeSet<Key>();
            expected.keySet().stream().filter(key -> key.date().equals(day)).forEach(should::add);
            if (!got.equals(should) || got.size() != fetched.get(day).path("returnedRows").asInt(-1))
                throw new IllegalStateException("D012 derived daily receipt differs from independent raw interval expansion on " + day);
        }
    }

    private static Publication publication(JdbcTemplate jdbc, Path ledger, String runId, String table, String logicalTarget,
            String frozenPhysical, LocalDate from, LocalDate to, List<LocalDate> sessions,
            int sourceRows, String sourceFingerprint, Path evidenceRoot, List<Map<String, Object>> pageReceipts,
            com.fasterxml.jackson.databind.ObjectMapper json) throws Exception {
        JsonNode intent;
        String state;
        String url = "jdbc:sqlite:" + ledger.toUri().toASCIIString() + "?mode=ro";
        try (var connection = DriverManager.getConnection(url);
             var statement = connection.prepareStatement(
                     "SELECT intent_json,state FROM stk_st_daily_publications WHERE run_id=?")) {
            statement.setString(1, runId);
            try (var rows = statement.executeQuery()) {
                if (!rows.next()) throw new IllegalStateException("No D012 publication journal for a nonempty session window");
                intent = json.readTree(rows.getString("intent_json")); state = rows.getString("state");
                if (rows.next()) throw new IllegalStateException("Multiple D012 publication journals exist for one run");
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("D012 publication journal is unavailable for independent readback", failure);
        }
        if (!"VERIFIED".equals(state) || !runId.equals(intent.path("runId").asText())
                || !table.equals(intent.path("target").asText())
                || !logicalTarget.equals(intent.path("logicalTarget").asText())
                || !frozenPhysical.equals(intent.path("frozenPhysicalTarget").asText())
                || !from.toString().equals(intent.path("windowFrom").asText())
                || !to.toString().equals(intent.path("windowTo").asText())
                || intent.path("sourceRows").asInt(-1) != sourceRows
                || !sourceFingerprint.equals(intent.path("sourceFingerprint").asText()))
            throw new IllegalStateException("D012 publication intent differs from the frozen source window");
        String receiptPath = requiredText(intent, "stageReceipt");
        String receiptFingerprint = requiredText(intent, "stageReceiptFingerprint");
        Path runEvidence = evidenceRoot.getParent().toRealPath();
        Path receipt = Path.of(receiptPath).toAbsolutePath().normalize().toRealPath();
        if (!receipt.startsWith(runEvidence) || receipt.equals(runEvidence)
                || Files.size(receipt) == 0 || Files.size(receipt) > MAX_RECEIPT_BYTES
                || !receiptFingerprint.equals(sha256(Files.readAllBytes(receipt))))
            throw new IllegalStateException("D012 stage receipt is outside run evidence or its hash differs");
        JsonNode stage = json.readTree(Files.readAllBytes(receipt));
        if (!table.equals(stage.path("target").asText()) || !sourceFingerprint.equals(stage.path("sourceFingerprint").asText())
                || stage.path("sourceRows").asInt(-1) != sourceRows
                || stage.path("authoritativeWindow").path("rows").asLong(-1) != sourceRows
                || !json.valueToTree(pageReceipts).equals(stage.path("sourceReceipts"))
                || !stage.path("sourceComplete").asBoolean(false))
            throw new IllegalStateException("D012 verified stage receipt differs from raw source window");

        JsonNode identity = intent.path("stageIdentity");
        if (!identity.path("id").canConvertToLong() || !identity.path("directory").isTextual())
            throw new IllegalStateException("D012 stage physical identity is malformed");
        var tables = jdbc.queryForList("SELECT id,directoryName FROM tables() WHERE table_name=?", table);
        if (tables.size() != 1 || !(tables.getFirst().get("id") instanceof Number tableId)
                || !(tables.getFirst().get("directoryName") instanceof String directory)
                || tableId.longValue() < 0 || directory.isBlank())
            throw new IllegalStateException("D012 current logical target table identity is unavailable");
        String livePhysicalTarget = StaticTargetIdentity.identify(jdbc, table, tableId.longValue(), directory);
        String journalStagePhysicalTarget = StaticTargetIdentity.identify(jdbc, table,
                identity.path("id").asLong(), identity.path("directory").asText());
        return new Publication(requiredText(intent, "id"), state, receipt.toString(),
                journalStagePhysicalTarget, livePhysicalTarget);
    }

    private static PhysicalRead readPhysical(JdbcTemplate jdbc, String table, LocalDate from, LocalDate to) throws Exception {
        String query = "SELECT ts_code,is_st,cast(timestamp AS long) AS timestamp_micros FROM \"" + table
                + "\" WHERE timestamp>=cast(? AS TIMESTAMP) AND timestamp<cast(? AS TIMESTAMP) "
                + "ORDER BY timestamp,ts_code LIMIT " + (MAX_ROWS + 1);
        var rows = new ArrayList<Actual>();
        long fromMicros = Math.multiplyExact(from.atStartOfDay().toEpochSecond(ZoneOffset.UTC), 1_000_000L);
        long toMicros = Math.multiplyExact(to.plusDays(1).atStartOfDay().toEpochSecond(ZoneOffset.UTC), 1_000_000L);
        jdbc.query(connection -> {
            var statement = connection.prepareStatement(query);
            statement.setQueryTimeout(120); statement.setFetchSize(512); statement.setMaxRows(MAX_ROWS + 1);
            statement.setLong(1, fromMicros); statement.setLong(2, toMicros); return statement;
        }, result -> {
            while (result.next()) {
                if (rows.size() >= MAX_ROWS) throw new SQLException("D012 bounded readback exceeds one-million rows");
                String code = result.getString("ts_code");
                int isSt = result.getInt("is_st"); boolean valueNull = result.wasNull();
                long micros = result.getLong("timestamp_micros"); boolean timeNull = result.wasNull();
                LocalDate date = timeNull ? null : dateAtMidnight(micros);
                rows.add(new Actual(code, date, valueNull ? null : isSt));
            }
            return null;
        });
        return new PhysicalRead(query, List.copyOf(rows));
    }

    private static LocalDate dateAtMidnight(long micros) {
        long seconds = Math.floorDiv(micros, 1_000_000L);
        long remainder = Math.floorMod(micros, 1_000_000L);
        Instant instant = Instant.ofEpochSecond(seconds, remainder * 1_000L);
        LocalDateTime dateTime = LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
        if (!dateTime.toLocalTime().equals(LocalTime.MIDNIGHT))
            throw new IllegalStateException("D012 physical timestamp is not a UTC-midnight calendar carrier");
        return dateTime.toLocalDate();
    }

    private static Comparison compare(Map<Key, Integer> expected, List<Actual> actual) {
        var found = new HashMap<Key, Actual>();
        int duplicates = 0, mismatches = 0, matches = 0, unexpected = 0;
        var samples = new ArrayList<String>();
        for (Actual row : actual) {
            if (row.code() == null || row.date() == null) {
                mismatches++;
                addSample(samples, "null-key:" + row);
                continue;
            }
            Key key = new Key(row.code(), row.date());
            if (found.putIfAbsent(key, row) != null) {
                duplicates++;
                addSample(samples, "duplicate:" + key);
                continue;
            }
            Integer source = expected.get(key);
            if (source == null) {
                unexpected++;
                addSample(samples, "unexpected:" + key);
            } else if (!Objects.equals(source, row.isSt())) {
                mismatches++;
                addSample(samples, "value:" + key + ":expected=" + source + ":actual=" + row.isSt());
            } else matches++;
        }
        int missing = 0;
        for (Key key : expected.keySet()) if (!found.containsKey(key)) {
            missing++;
            addSample(samples, "missing:" + key);
        }
        return new Comparison(matches, mismatches, duplicates, missing, unexpected, List.copyOf(samples));
    }

    private static void addSample(List<String> samples, String sample) {
        if (samples.size() < 30) samples.add(sample);
    }

    private static LocalDate sourceDate(JsonNode node, boolean blankAllowed) {
        if (node == null || node.isNull()) {
            if (blankAllowed) return null;
            throw new IllegalStateException("ST namechange start date is null");
        }
        if (!node.isTextual()) throw new IllegalStateException("ST namechange date is not textual");
        String value = node.asText().trim();
        if (blankAllowed && Set.of("", "none", "nan", "nat").contains(value.toLowerCase(Locale.ROOT))) return null;
        if (!value.matches("[0-9]{8}")) throw new IllegalStateException("ST namechange date is not YYYYMMDD");
        return LocalDate.parse(value, BASIC);
    }

    private static String pythonText(JsonNode node) {
        if (node == null || node.isNull()) return "None";
        if (node.isTextual()) return node.textValue();
        return node.asText();
    }

    private static List<Map<String, JsonNode>> sortedRawRows(JsonNode rows) {
        return canonicalRawRows(rows);
    }

    private static String combinedFingerprint(List<String> parts) throws Exception {
        var digest = MessageDigest.getInstance("SHA-256");
        for (String part : parts) {
            digest.update(part.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static String requiredText(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (!value.isTextual() || value.asText().isBlank()) throw new IllegalStateException("Required D012 evidence field: " + field);
        return value.asText();
    }

    private static LocalDate iso(String value) {
        try { return LocalDate.parse(value); }
        catch (RuntimeException invalid) { throw new IllegalStateException("ISO D012 calendar date required", invalid); }
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private record Key(String code, LocalDate date) implements Comparable<Key> {
        Key { Objects.requireNonNull(code); Objects.requireNonNull(date); }
        @Override public int compareTo(Key other) {
            int codeOrder = code.compareTo(other.code);
            return codeOrder != 0 ? codeOrder : date.compareTo(other.date);
        }
    }
    private record Actual(String code, LocalDate date, Integer isSt) {}
    private record PhysicalRead(String query, List<Actual> rows) {}
    private record ReceiptFile(Path path, String sha256, byte[] bytes, JsonNode body) {}
    private record Annual(ReceiptFile file, List<Map<String, JsonNode>> rows) {}
    private record Publication(String id, String state, String stageReceipt,
                               String journalStagePhysicalTarget, String currentPhysicalTarget) {}
    private record Comparison(int matches, int mismatches, int duplicateKeys, int missingKeys,
                              int unexpectedKeys, List<String> samples) {}
}
