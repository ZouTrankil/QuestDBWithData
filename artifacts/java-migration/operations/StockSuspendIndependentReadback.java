import com.fasterxml.jackson.databind.JsonNode;
import com.zoutrankil.questdbwithdata.domain.JobDefinitionJson;
import com.zoutrankil.questdbwithdata.domain.SyncRunState;
import com.zoutrankil.questdbwithdata.repository.SyncRunLedger;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.ConnectionCallback;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.net.URI;
import java.security.MessageDigest;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.Locale;

/** Independent receipt-to-QuestDB comparison. Does not call D011 source, mapper, or write port. */
public final class StockSuspendIndependentReadback {
    private static final int MAX_EVIDENCE_BYTES = 16 * 1024 * 1024;
    private static final int MAX_WINDOW_DAYS = 5;
    private static final List<String> RAW_FIELDS = List.of("ts_code", "trade_date", "suspend_timing", "suspend_type");
    private static final Set<String> RAW_FIELD_SET = Set.copyOf(RAW_FIELDS);
    private static final List<String> PHYSICAL_FIELDS = List.of("ts_code", "is_suspended", "timestamp");

    private record Key(String code, LocalDate date) {}
    private record Expected(String code, LocalDate date, long flag) {}
    private record Actual(String code, LocalDate date, long flag, long timestampMicros) {}
    private record DailyReceipt(LocalDate date, String path, String fingerprint, int rows) {}

    private StockSuspendIndependentReadback() {}

    public static Map<String,Object> verify(JdbcTemplate jdbc, Path ledger, String table, String runId) throws Exception {
        Objects.requireNonNull(jdbc); Objects.requireNonNull(ledger); Objects.requireNonNull(runId);
        if (table == null || !table.matches("(?:stk_suspend_d011_|java_d011_stk_suspend_)[A-Za-z0-9_]{1,80}"))
            throw new IllegalArgumentException("An explicitly isolated D011 target is required");
        var json = JobDefinitionJson.mapper();
        var runs = SyncRunLedger.openReadOnly(ledger);
        var run = runs.getRun(runId);
        var runEntry = runs.get(runId);
        if (!"data.stk_suspend".equals(run.jobId()) || run.jobVersion() < 1
                || !Set.of(SyncRunState.VERIFIED, SyncRunState.VERIFIED_EMPTY).contains(runEntry.state()))
            throw new IllegalStateException("A verified D011 run is required for independent readback");
        JsonNode frozen = json.readTree(run.frozenJson());
        if (!"data.stk_suspend".equals(frozen.path("definition").path("jobId").asText())
                || frozen.path("definition").path("version").asInt(-1) != run.jobVersion()
                || !run.targetId().equals(frozen.path("parameters").path("targetId").asText())
                || !frozen.path("parameters").path("physicalTargetId").asText().matches("static-v2-[0-9a-f]{64}"))
            throw new IllegalStateException("D011 run target identity is not frozen consistently");
        LocalDate from = LocalDate.parse(frozen.path("from").asText());
        LocalDate to = LocalDate.parse(frozen.path("to").asText());
        long days = java.time.temporal.ChronoUnit.DAYS.between(from, to) + 1;
        if (from.isAfter(to) || days < 1 || days > MAX_WINDOW_DAYS || to.isAfter(LocalDate.parse(frozen.path("logicalDate").asText())))
            throw new IllegalStateException("D011 frozen source range is invalid or exceeds its bound");

        Path evidenceRoot = ledger.toAbsolutePath().normalize().getParent().resolve("sync-evidence").resolve(runId).toRealPath();
        var slices = new ArrayList<SyncRunLedger.Entry>();
        for (var entry : runs.entries(runId, null, 1000))
            if (entry.kind() == SyncRunLedger.Kind.SLICE) slices.add(entry);
        if (slices.size() != days) throw new IllegalStateException("D011 run is missing a daily source slice");

        var expected = new TreeMap<Key,Expected>(Comparator.comparing(Key::date).thenComparing(Key::code));
        var receiptByDate = new TreeMap<LocalDate,DailyReceipt>();
        var pageEvidencePaths = new ArrayList<Path>();
        Path completionPath = null;
        for (var slice : slices) {
            var fetched = runs.events(slice.id(), -1, 100).stream()
                    .filter(event -> event.state() == SyncRunState.FETCHED).toList();
            if (fetched.size() != 1) throw new IllegalStateException("D011 slice must have exactly one FETCHED source receipt");
            JsonNode fetch = json.readTree(fetched.getFirst().payloadJson());
            Path pagePath = evidencePath(evidenceRoot, fetch.path("responseEvidence").asText());
            JsonNode page = json.readTree(Files.readAllBytes(pagePath));
            if (!"stk_suspend_daily_source".equals(page.path("evidenceType").asText())
                    || !page.path("sourceComplete").asBoolean(false)
                    || !page.path("sourceFingerprint").asText().equals(fetch.path("sourceFingerprint").asText())
                    || !page.path("sourceReceipt").isTextual()
                    || !page.path("completionEvidence").isTextual()
                    || !page.path("publication").isObject())
                throw new IllegalStateException("D011 page receipt lacks complete source/publication linkage");
            Path rawPath = evidencePath(evidenceRoot, page.path("sourceReceipt").asText());
            byte[] rawBytes = Files.readAllBytes(rawPath);
            String fingerprint = sha256(rawBytes);
            if (!fingerprint.equals(fetch.path("sourceFingerprint").asText()))
                throw new IllegalStateException("D011 raw receipt SHA-256 differs from the immutable FETCHED event");
            JsonNode receipt = json.readTree(rawBytes);
            LocalDate date = validateDailyReceipt(receipt, page, fetch, fingerprint);
            if (date.isBefore(from) || date.isAfter(to) || receiptByDate.containsKey(date))
                throw new IllegalStateException("D011 raw receipt date is duplicated or outside the frozen range");
            if (!Set.of(SyncRunState.VERIFIED, SyncRunState.VERIFIED_EMPTY).contains(slice.state())
                    || (receipt.path("returnedRows").asInt() == 0) != (slice.state() == SyncRunState.VERIFIED_EMPTY))
                throw new IllegalStateException("D011 slice state differs from its receipt row count");

            JsonNode rows = receipt.path("rows");
            JsonNode normalizedRows = receipt.path("normalizedRows");
            for (int i = 0; i < rows.size(); i++) {
                JsonNode raw = rows.get(i);
                var names = new TreeSet<String>(); raw.fieldNames().forEachRemaining(names::add);
                if (!RAW_FIELD_SET.equals(names)) throw new IllegalStateException("D011 raw row fields differ from suspend_d contract");
                String code = raw.path("ts_code").asText();
                if (!code.matches("[0-9]{6}\\.(?:SH|SZ|BJ)")) throw new IllegalStateException("D011 source code is invalid");
                String basicDate = date.format(DateTimeFormatter.BASIC_ISO_DATE);
                if (!basicDate.equals(raw.path("trade_date").asText()) || !"S".equals(raw.path("suspend_type").asText()))
                    throw new IllegalStateException("D011 source row escaped its daily S scope");
                JsonNode timing = raw.get("suspend_timing");
                if (timing == null || !(timing.isNull() || timing.isTextual()))
                    throw new IllegalStateException("D011 source suspend_timing must be nullable text");
                JsonNode normalized = normalizedRows.get(i);
                if (!code.equals(normalized.path("ts_code").asText())
                        || !date.toString().equals(normalized.path("trade_date").asText())
                        || normalized.path("is_suspended").asLong(-1) != 1L
                        || !Objects.equals(timing, normalized.get("suspend_timing")))
                    throw new IllegalStateException("D011 normalized source evidence differs from raw S fields");
                Key key = new Key(code, date);
                if (expected.putIfAbsent(key, new Expected(code, date, 1L)) != null)
                    throw new IllegalStateException("D011 source has a duplicate full business key");
            }
            Path candidateCompletion = evidencePath(evidenceRoot, page.path("completionEvidence").asText());
            if (completionPath != null && !completionPath.equals(candidateCompletion))
                throw new IllegalStateException("D011 daily pages point at different complete-window manifests");
            completionPath = candidateCompletion;
            if (!page.path("publication").equals(json.readTree(completionPath.toFile()).path("publication")))
                throw new IllegalStateException("D011 page and complete-window publication evidence differ");
            receiptByDate.put(date, new DailyReceipt(date, rawPath.toString(), fingerprint, rows.size()));
            pageEvidencePaths.add(pagePath);
        }
        for (LocalDate date = from; !date.isAfter(to); date = date.plusDays(1))
            if (!receiptByDate.containsKey(date)) throw new IllegalStateException("D011 complete run lacks date " + date);

        String combinedFingerprint = combineFingerprints(receiptByDate.values().stream().map(DailyReceipt::fingerprint).toList());
        JsonNode completion = json.readTree(Objects.requireNonNull(completionPath).toFile());
        JsonNode publication = completion.path("publication"), proof = publication.path("verification");
        String frozenLogical = frozen.path("parameters").path("targetId").asText();
        String frozenPhysical = frozen.path("parameters").path("physicalTargetId").asText();
        int expectedRows = expected.size();
        if (!"suspend_d".equals(completion.path("endpoint").asText()) || !completion.path("complete").asBoolean(false)
                || !completion.path("sourceComplete").asBoolean(false)
                || completion.path("completedDateSlices").asInt(-1) != days
                || completion.path("sourceRows").asInt(-1) != expectedRows
                || completion.path("returnedRows").asInt(-1) != expectedRows
                || completion.path("submittedRows").asInt(-1) != expectedRows
                || completion.path("responseEvidence").asText().isBlank()
                || !from.toString().equals(completion.path("fromInclusive").asText())
                || !to.toString().equals(completion.path("toInclusive").asText())
                || !frozenLogical.equals(publication.path("logicalTargetId").asText())
                || !frozenPhysical.equals(publication.path("physicalTargetBefore").asText())
                || !from.toString().equals(publication.path("fromInclusive").asText())
                || !to.toString().equals(publication.path("toInclusive").asText())
                || !publication.path("physicalTargetAfter").asText().matches("static-v2-[0-9a-f]{64}")
                || publication.path("fullTargetFingerprint").asText().isBlank()
                || !proof.path("passed").asBoolean(false) || !proof.path("writerStopped").asBoolean(false)
                || proof.path("expectedRows").asInt(-1) != expectedRows
                || proof.path("actualRows").asInt(-1) != expectedRows
                || proof.path("matchedRows").asInt(-1) != expectedRows
                || proof.path("mismatchedRows").asInt(-1) != 0 || proof.path("duplicateKeys").asInt(-1) != 0
                || proof.path("missingKeys").asInt(-1) != 0
                || proof.path("readbackEvidence").asText().isBlank()
                || !combinedFingerprint.equals(proof.path("sourceFingerprint").asText())
                || publication.path("replacementPublished").asBoolean(false)
                    == publication.path("physicalTargetBefore").asText().equals(publication.path("physicalTargetAfter").asText()))
            throw new IllegalStateException("D011 complete-window source/publication evidence is incomplete or inconsistent");

        var targetRows=jdbc.queryForList("SELECT id,directoryName FROM tables() WHERE table_name=?",table);
        if(targetRows.size()!=1||!(targetRows.getFirst().get("id") instanceof Number targetId)
                ||!(targetRows.getFirst().get("directoryName") instanceof String directory)
                ||!publication.path("physicalTargetAfter").asText().equals(physicalIdentity(jdbc,table,targetId.longValue(),directory))
                ||!frozenLogical.equals(logicalIdentity(jdbc,table)))
            throw new IllegalStateException("D011 current QuestDB target identity differs from the complete publication evidence");

        String target = quote(table);
        String start = timestampLiteral(from), end = timestampLiteral(to.plusDays(1));
        String query = "SELECT ts_code,is_suspended,cast(timestamp AS long) AS timestamp_micros FROM " + target
                + " WHERE timestamp>=cast('" + start + "' AS TIMESTAMP) AND timestamp<cast('" + end
                + "' AS TIMESTAMP) ORDER BY timestamp,ts_code";
        List<Actual> actualRows = jdbc.query(query, ACTUAL_ROW_MAPPER);
        var actualByKey = new HashMap<Key,Actual>();
        int duplicates = 0, extraRows = 0;
        for (Actual actual : actualRows) {
            Key key = new Key(actual.code(), actual.date());
            if (actualByKey.putIfAbsent(key, actual) != null) { duplicates++; continue; }
            if (!expected.containsKey(key)) extraRows++;
        }
        int missing = 0, mismatches = 0, matches = 0;
        for (var entry : expected.entrySet()) {
            Actual actual = actualByKey.get(entry.getKey());
            if (actual == null) { missing++; continue; }
            Expected wanted = entry.getValue();
            if (actual.flag() != wanted.flag() || actual.timestampMicros() != epochMicros(wanted.date())) mismatches++;
            else matches++;
        }
        int mismatchTotal = Math.addExact(mismatches, extraRows);
        String status = duplicates == 0 && missing == 0 && mismatchTotal == 0 && actualRows.size() == expectedRows
                ? "MATCHED" : "MISMATCH";
        var report = new LinkedHashMap<String,Object>();
        report.put("task", "D011"); report.put("runId", runId); report.put("status", status);
        report.put("table", table); report.put("requestWindow", Map.of("fromInclusive", from.toString(), "toInclusive", to.toString()));
        report.put("sourceReceiptCount", receiptByDate.size());
        report.put("sourceReceipts", receiptByDate.values().stream().map(receipt -> Map.of("tradeDate", receipt.date().toString(),
                "path", receipt.path(), "fingerprint", receipt.fingerprint(), "rows", receipt.rows())).toList());
        report.put("sourceFingerprint", combinedFingerprint); report.put("sourceRows", expectedRows);
        report.put("actualRows", actualRows.size()); report.put("matchedRows", matches);
        report.put("mismatchedRows", mismatchTotal); report.put("duplicateKeys", duplicates);
        report.put("missingKeys", missing); report.put("extraRows", extraRows);
        report.put("comparedColumns", PHYSICAL_FIELDS); report.put("query", query);
        report.put("completionEvidence", completionPath.toString());
        report.put("physicalTargetBefore", frozenPhysical);
        report.put("physicalTargetAfter", publication.path("physicalTargetAfter").asText());
        return report;
    }

    private static LocalDate validateDailyReceipt(JsonNode receipt, JsonNode page, JsonNode fetch,
                                                  String fingerprint) {
        if (!"suspend_d".equals(receipt.path("endpoint").asText())
                || !receipt.path("sourceComplete").asBoolean(false)
                || receipt.path("sourceRowCap").asInt(-1) != 5000
                || receipt.path("pages").asInt(-1) != 1
                || !receipt.path("fields").equals(JobDefinitionJson.mapper().valueToTree(RAW_FIELDS))
                || !receipt.path("rows").isArray() || !receipt.path("normalizedRows").isArray())
            throw new IllegalStateException("D011 raw receipt does not satisfy the complete suspend_d field contract");
        int rows = receipt.path("rows").size();
        if (rows >= 5000 || receipt.path("returnedRows").asInt(-1) != rows
                || receipt.path("normalizedRows").size() != rows || fetch.path("returnedRows").asInt(-1) != rows
                || !fingerprint.equals(page.path("sourceFingerprint").asText()))
            throw new IllegalStateException("D011 raw receipt count or cap evidence differs");
        LocalDate date = LocalDate.parse(receipt.path("tradeDate").asText());
        String basic = date.format(DateTimeFormatter.BASIC_ISO_DATE);
        if (!basic.equals(receipt.path("parameters").path("trade_date").asText())
                || !"S".equals(receipt.path("parameters").path("suspend_type").asText())
                || !date.toString().equals(page.path("tradeDate").asText()))
            throw new IllegalStateException("D011 raw receipt query is not its exact daily S request");
        return date;
    }

    private static final RowMapper<Actual> ACTUAL_ROW_MAPPER = (ResultSet row, int index) -> {
        Object rawMicros = row.getObject("timestamp_micros"), rawFlag = row.getObject("is_suspended");
        if (!(rawMicros instanceof Number micros) || !(rawFlag instanceof Number flag))
            throw new SQLException("D011 physical timestamp and LONG state required");
        String code = row.getString("ts_code");
        Instant instant = Instant.ofEpochSecond(Math.floorDiv(micros.longValue(), 1_000_000L),
                Math.multiplyExact(Math.floorMod(micros.longValue(), 1_000_000L), 1_000L));
        return new Actual(code, instant.atZone(ZoneOffset.UTC).toLocalDate(), flag.longValue(), micros.longValue());
    };

    private static Path evidencePath(Path root, String value) throws Exception {
        if (value == null || value.isBlank()) throw new IllegalStateException("D011 evidence path is missing");
        Path path = Path.of(value).toAbsolutePath().normalize();
        if (!path.startsWith(root) || !Files.isRegularFile(path)) throw new IllegalStateException("D011 evidence path is outside its run directory");
        Path realRoot = root.toRealPath(), real = path.toRealPath();
        if (!real.startsWith(realRoot) || Files.size(real) > MAX_EVIDENCE_BYTES)
            throw new IllegalStateException("D011 evidence is oversized or escapes its run directory");
        return real;
    }

    private static long epochMicros(LocalDate date) {
        Instant start = date.atStartOfDay(ZoneOffset.UTC).toInstant();
        return Math.addExact(Math.multiplyExact(start.getEpochSecond(), 1_000_000L), start.getNano() / 1_000L);
    }

    private static String timestampLiteral(LocalDate day) { return day + "T00:00:00.000000Z"; }
    private static String quote(String name) { return "\"" + name + "\""; }
    private static String logicalIdentity(JdbcTemplate jdbc,String table) throws Exception {
        String url=jdbc.execute((ConnectionCallback<String>)connection->connection.getMetaData().getURL());
        String endpoint=endpoint(url);
        return "static-v2-"+sha256(("stk_suspend-logical-v1\n"+endpoint+"\n"+table).getBytes(StandardCharsets.UTF_8));
    }
    private static String physicalIdentity(JdbcTemplate jdbc,String table,long id,String directory)throws Exception {
        String url=jdbc.execute((ConnectionCallback<String>)connection->connection.getMetaData().getURL());
        String frozen=endpoint(url)+"\n"+table+"\n"+id+"\n"+directory;
        return "static-v2-"+sha256(frozen.getBytes(StandardCharsets.UTF_8));
    }
    private static String endpoint(String url) {
        if(url==null||!url.startsWith("jdbc:postgresql://"))throw new IllegalStateException("Explicit QuestDB PGWire endpoint required");
        URI address=URI.create(url.substring(5).split("\\?",2)[0]);
        if(address.getHost()==null||address.getPath()==null||address.getPath().length()<2)
            throw new IllegalStateException("QuestDB PGWire database identity is unavailable");
        return address.getHost().toLowerCase(Locale.ROOT)+":"+(address.getPort()<0?5432:address.getPort())+address.getRawPath();
    }
    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
    private static String combineFingerprints(List<String> values) throws Exception {
        var digest = MessageDigest.getInstance("SHA-256");
        for (String value : values) { digest.update(value.getBytes(StandardCharsets.UTF_8)); digest.update((byte) 0); }
        return HexFormat.of().formatHex(digest.digest());
    }
}
