package com.zoutrankil.data.config;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.zaxxer.hikari.HikariDataSource;
import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.domain.temporal.TemporalValues;
import com.zoutrankil.data.mapper.EtfMarketOverviewDailyViewMapper;
import com.zoutrankil.data.repository.*;
import com.zoutrankil.data.service.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/** D102 SELECT acceptance. The separately admitted schema audit creates only the missing private view. */
class EtfMarketOverviewDailyViewLiveAcceptanceTest {
    private static final LocalDate FROM = LocalDate.of(2026, 9, 17);
    private static final LocalDate STOP = LocalDate.of(2026, 9, 22);
    private static final List<LocalDate> DAYS = List.of(FROM, FROM.plusDays(1), LocalDate.of(2026, 9, 21));
    private static final Path DIRECTORY = Path.of("artifacts/java-migration/D102/commands");
    private static final String GATE_SHA = "2201c05e5e05c4b056a5f3170be86ed86c230fb08e1d7dcf42f93c7228fccaef";
    private static final String SOURCE_SQL = """
            SELECT s.timestamp AS trade_date,count_distinct(s.ts_code) AS etf_count,
            sum(s.fd_share) AS total_share,sum(s.fd_share*d.close)/10000.0 AS total_size_yi
            FROM etf_share s JOIN etf_daily d ON s.ts_code=d.ts_code AND s.timestamp=d.timestamp
            WHERE s.timestamp>=? AND s.timestamp<? SAMPLE BY 1d ALIGN TO CALENDAR ORDER BY trade_date
            """;

    @Test void actualViewMatchesBothBasesInPrivateAndFormalBoundedReads() throws Exception {
        assumeTrue("true".equalsIgnoreCase(System.getenv("D102_LIVE_READ")));
        Path gate = Path.of("artifacts/java-migration/D101/coordinator-review-20261006.json");
        assertEquals(GATE_SHA, sha(gate));
        JsonNode admitted = JobDefinitionJson.mapper().readTree(gate.toFile());
        assertEquals("accepted_for_serial_progress", admitted.required("decision").asText());
        assertTrue(admitted.required("next_task_may_start").asBoolean());
        JsonNode nativeBefore = attestPrivate();
        var evidence = new LinkedHashMap<String, Object>();
        evidence.put("task_id", "D102"); evidence.put("checked_at", Instant.now());
        evidence.put("D101_gate_sha256", GATE_SHA); evidence.put("private_attestation_before", nativeBefore);
        try (var privatePool = pool("d102-private", "127.0.0.1", 18832, "admin", "quest");
             var formalPool = pool("d102-formal", required("APP_QUESTDB_HOST"),
                     Integer.parseInt(System.getenv().getOrDefault("APP_QUESTDB_PGPORT", "8812")),
                     required("APP_QUESTDB_USERNAME"), required("APP_QUESTDB_PASSWORD"))) {
            evidence.put("private", verify(jdbc(privatePool), true));
            evidence.put("formal", verify(jdbc(formalPool), false));
        }
        JsonNode nativeAfter = attestPrivate();
        assertEquals(nativeBefore, nativeAfter, "Exact private process, birth, command and listeners must remain bound");
        evidence.put("private_attestation_after", nativeAfter);
        evidence.put("status", "VERIFIED_PRIVATE_AND_FORMAL_BOUNDED_VIEW_READ");
        evidence.put("source_inserts", 0); evidence.put("ddl", 0); evidence.put("cache_publications", 0);
        evidence.put("formal_writes", 0); evidence.put("double_tolerance", 0);
        evidence.put("actual_source_revision_during_D102_test", false);
        evidence.put("source_increment_provenance", "D101 accepted actual third-day source append, reused unchanged");
        Files.createDirectories(DIRECTORY);
        Files.writeString(DIRECTORY.resolve("java-view-read-acceptance-20261006.json"),
                JobDefinitionJson.mapper().writerWithDefaultPrettyPrinter().writeValueAsString(evidence),
                StandardOpenOption.CREATE_NEW);
    }

    private static Map<String, Object> verify(JdbcTemplate jdbc, boolean isolated) throws Exception {
        var before = snapshot(jdbc);
        if (isolated) {
            assertEquals(2297L, count(jdbc, "etf_share")); assertEquals(6412L, count(jdbc, "etf_daily"));
            assertEquals(2958L, count(jdbc, "etf_basic"));
            assertEquals(5L, count(jdbc, "etf_market_overview_daily_cache"));
            assertEquals(5L, count(jdbc, "market_barometer_cache_coverage"));
        }
        var expected = aggregate(jdbc);
        assertEquals(DAYS, expected.stream().map(EtfMarketOverviewDailyView::tradeDate).toList());
        var reader = new QuestDbBoundedReader(jdbc);
        var repository = new EtfMarketOverviewDailyViewReadRepository(reader);
        var mapper = new EtfMarketOverviewDailyViewMapper();
        var first = repository.findRange(FROM, FROM.plusDays(2), 1, null);
        assertEquals(List.of(expected.getFirst()), first.rows());
        assertNotNull(first.nextCursor());
        var resumed = repository.findRange(FROM, FROM.plusDays(2), 1, first.nextCursor());
        assertEquals(List.of(expected.get(1)), resumed.rows()); assertFalse(resumed.hasMore());
        assertEquals(first.sourceVersion(), resumed.sourceVersion());
        var replay = repository.findRange(FROM, FROM.plusDays(2), 2, null);
        assertEquals(expected.subList(0, 2), replay.rows()); assertFalse(replay.hasMore());
        assertThrows(IllegalArgumentException.class,
                () -> repository.findRange(FROM, STOP, 1, first.nextCursor()),
                "An actual captured cursor cannot continue a different request range");

        var actual = new ArrayList<EtfMarketOverviewDailyView>();
        var pages = new ArrayList<Map<String, Object>>();
        DatasetReadCursor cursor = null;
        do {
            var page = repository.findRange(FROM, STOP, 1, cursor);
            assertEquals(1, page.rows().size()); assertEquals(first.sourceVersion(), page.sourceVersion());
            actual.addAll(page.rows());
            pages.add(Map.of("page", pages.size() + 1, "date", page.rows().getFirst().tradeDate(),
                    "source_version", page.sourceVersion(), "has_more", page.hasMore()));
            cursor = page.nextCursor();
            assertTrue(pages.size() <= 3, "Finite three-bucket output cannot produce an additional page");
        } while (cursor != null);
        assertEquals(3, pages.size()); assertEquals(DAYS, actual.stream().map(EtfMarketOverviewDailyView::tradeDate).toList());
        compare(expected, actual);
        for (int index = 0; index < DAYS.size(); index++) {
            var byKey = repository.findKey(new EtfMarketOverviewDailyViewKey(DAYS.get(index)));
            assertEquals(List.of(actual.get(index)), byKey.rows()); assertFalse(byKey.hasMore());
            assertEquals(first.sourceVersion(), byKey.sourceVersion());
        }
        assertTrue(repository.findForDate(LocalDate.of(2026, 9, 20)).rows().isEmpty());
        var independentView = directView(jdbc); compare(expected, independentView); compare(independentView, actual);

        var registry = new DatasetRegistry(List.of(repository,
                (DatasetImplementation) () -> EtfShareDataset.DEFINITION,
                (DatasetImplementation) () -> EtfDailyDataset.DEFINITION,
                new ExchangeCalendarReadRepository(reader)));
        var group = new ReadGroupConfiguration().readGroupReader(registry, reader);
        var query = new DatasetReadQuery(repository.definition().storageColumns(), Map.of(), "trade_date", FROM, STOP, 31, null);
        var requestDocument = Map.of("timeoutMillis", 30000, "members", List.of(Map.of(
                "memberId", "etf", "datasetId", repository.definition().datasetId(), "definitionVersion", 1, "query", query)));
        Path requestPath = DIRECTORY.resolve("strictread-group-request-D102-" + (isolated ? "private" : "formal") + ".json");
        Files.createDirectories(DIRECTORY);
        Files.writeString(requestPath, JobDefinitionJson.mapper().writerWithDefaultPrettyPrinter().writeValueAsString(requestDocument),
                StandardOpenOption.CREATE_NEW);
        var request = group.readRequest(requestPath);
        var grouped = group.read(request, () -> false); assertTrue(grouped.complete());
        var member = grouped.require("etf"); assertEquals(EtfMarketOverviewDailyView.class, member.rowType());
        var typed = member.typedPage(EtfMarketOverviewDailyView.class);
        assertEquals(actual, typed.rows()); assertFalse(typed.hasMore()); assertEquals(first.sourceVersion(), typed.sourceVersion());
        compare(expected, typed.rows());
        var cancelled = group.read(request, () -> true); assertFalse(cancelled.complete());
        assertEquals(ReadGroupReader.Status.CANCELLED, cancelled.require("etf").status());
        assertNull(cancelled.require("etf").page());
        var limits = new DatasetWritePreparation.Limits(31, 65536);
        assertThrows(IllegalArgumentException.class, () -> DatasetWritePreparation.prepare(repository.definition(), actual, mapper::values, limits));
        assertThrows(IllegalArgumentException.class, () -> DatasetWritePreparation.prepareStatic(repository.definition(), actual, mapper::values, limits));
        assertThrows(IllegalArgumentException.class, () -> DatasetWritePreparation.prepareWalReplace(repository.definition(), actual, mapper::values, limits));
        var after = snapshot(jdbc);
        assertEquals(before, after, "Readonly acceptance must retain exact source/cache/receipt/view metadata");
        return Map.ofEntries(Map.entry("metadata_before", before), Map.entry("metadata_after", after),
                Map.entry("source_sql", SOURCE_SQL), Map.entry("from_inclusive", FROM), Map.entry("to_exclusive", STOP),
                Map.entry("expected_source_rows", expected.stream().map(row -> mapper.values(row).asMap()).toList()),
                Map.entry("actual_view_rows", actual.stream().map(row -> mapper.values(row).asMap()).toList()),
                Map.entry("independent_jdbc_view_rows", independentView.stream().map(row -> mapper.values(row).asMap()).toList()),
                Map.entry("pages", pages), Map.entry("actual_first_cursor", first.nextCursor()),
                Map.entry("physical_source_version", first.sourceVersion()), Map.entry("changed_range_cursor_rejected", true),
                Map.entry("first_two_day_read_replayed_exact", true), Map.entry("third_day_window_extension_matched", true),
                Map.entry("source_field_comparisons", 12), Map.entry("source_double_rawbit_comparisons", 6),
                Map.entry("independent_jdbc_double_rawbit_comparisons", 6), Map.entry("actual_double_bits", bits(actual)),
                Map.entry("configured_read_group_matched", true), Map.entry("cancelled_member_has_no_page", true),
                Map.entry("write_and_replacement_preparation_rejected", true), Map.entry("source_completeness_certified", false));
    }

    private static List<EtfMarketOverviewDailyView> aggregate(JdbcTemplate jdbc) {
        return jdbc.query(connection -> {
            var statement = connection.prepareStatement(SOURCE_SQL); statement.setQueryTimeout(20); statement.setMaxRows(32);
            var utc = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
            statement.setTimestamp(1, Timestamp.from(FROM.atStartOfDay().toInstant(ZoneOffset.UTC)), utc);
            statement.setTimestamp(2, Timestamp.from(STOP.atStartOfDay().toInstant(ZoneOffset.UTC)), utc); return statement;
        }, (rs, index) -> row(rs));
    }

    private static List<EtfMarketOverviewDailyView> directView(JdbcTemplate jdbc) {
        return jdbc.query("SELECT trade_date,etf_count,total_share,total_size_yi FROM v_etf_market_overview_daily "
                + "WHERE trade_date>='2026-09-17' AND trade_date<'2026-09-22' ORDER BY trade_date LIMIT 4", (rs, index) -> row(rs));
    }

    private static EtfMarketOverviewDailyView row(ResultSet rs) throws java.sql.SQLException {
        Timestamp stamp = rs.getTimestamp("trade_date", Calendar.getInstance(TimeZone.getTimeZone("UTC")));
        assertNotNull(stamp); long count = rs.getLong("etf_count"); assertFalse(rs.wasNull());
        double share = rs.getDouble("total_share"); Double nullableShare = rs.wasNull() ? null : share;
        double size = rs.getDouble("total_size_yi"); Double nullableSize = rs.wasNull() ? null : size;
        return new EtfMarketOverviewDailyView(TemporalValues.CalendarTimestamp.fromStorage(stamp.toInstant()).date(),
                count, nullableShare, nullableSize);
    }

    private static void compare(List<EtfMarketOverviewDailyView> expected, List<EtfMarketOverviewDailyView> actual) {
        assertEquals(expected.size(), actual.size());
        for (int index = 0; index < expected.size(); index++) {
            var left = expected.get(index); var right = actual.get(index);
            assertEquals(left.tradeDate(), right.tradeDate()); assertEquals(left.etfCount(), right.etfCount());
            compareDouble(left.totalShare(), right.totalShare()); compareDouble(left.totalSizeYi(), right.totalSizeYi());
        }
    }

    private static void compareDouble(Double left, Double right) {
        if (left == null || right == null) assertEquals(left, right);
        else assertEquals(Double.doubleToRawLongBits(left), Double.doubleToRawLongBits(right));
    }

    private static List<List<String>> bits(List<EtfMarketOverviewDailyView> rows) {
        return rows.stream().map(row -> List.of(row.tradeDate().toString(), bit(row.totalShare()), bit(row.totalSizeYi()))).toList();
    }
    private static String bit(Double value) { return value == null ? "null" : Long.toUnsignedString(Double.doubleToRawLongBits(value), 16); }
    private static long count(JdbcTemplate jdbc, String table) { return Objects.requireNonNull(jdbc.queryForObject("SELECT count() FROM " + table, Long.class)); }
    private static Map<String, Object> snapshot(JdbcTemplate jdbc) {
        var result = new LinkedHashMap<String, Object>();
        for (String table : List.of("etf_share", "etf_daily", "etf_basic", "etf_market_overview_daily_cache", "market_barometer_cache_coverage")) {
            result.put(table, jdbc.queryForList("SELECT t.id,t.directoryName,t.table_txn,t.table_row_count,t.partitionBy,t.walEnabled,t.dedup,"
                    + "t.designatedTimestamp,t.table_suspended,t.wal_pending_row_count,w.sequencerTxn,w.writerTxn,w.bufferedTxnSize,w.suspended "
                    + "FROM tables() t JOIN wal_tables() w ON w.name=t.table_name WHERE t.table_name='" + table + "'"));
        }
        result.put("view", jdbc.queryForList("SELECT * FROM views() WHERE view_name='v_etf_market_overview_daily' LIMIT 2"));
        result.put("columns", jdbc.queryForList("SELECT \"column\",type FROM table_columns('v_etf_market_overview_daily') LIMIT 5"));
        return result;
    }

    private static JsonNode attestPrivate() throws Exception {
        String script = """
                $ErrorActionPreference='Stop'
                $listeners=@(Get-NetTCPConnection -LocalPort 19020,18832 -State Listen -ErrorAction Stop)
                $records=@(foreach($port in @(19020,18832)) {
                  $matches=@($listeners | Where-Object LocalPort -eq $port)
                  if($matches.Count -ne 1) { throw 'Ambiguous private listener' }
                  $listener=$matches[0]
                  $procTarget=Get-CimInstance Win32_Process -Filter "ProcessId=$($listener.OwningProcess)"
                  [pscustomobject]@{port=$port;address=$listener.LocalAddress;pid=$procTarget.ProcessId;name=$procTarget.Name;command=$procTarget.CommandLine;birth=$procTarget.CreationDate.ToUniversalTime().ToString('o')}
                })
                ConvertTo-Json -InputObject $records -Compress
                """;
        Path powershell = Path.of(required("SystemRoot"), "System32/WindowsPowerShell/v1.0/powershell.exe");
        var process = new ProcessBuilder(powershell.toString(), "-NoProfile", "-NonInteractive", "-Command", script).start();
        assertTrue(process.waitFor(45, TimeUnit.SECONDS), "Readonly process attestation timed out");
        assertEquals(0, process.exitValue(), "Readonly native attestation failed");
        var value = JobDefinitionJson.mapper().readTree(process.getInputStream());
        assertEquals(2, value.size());
        var root = Path.of("var/d101-isolated-questdb").toAbsolutePath().normalize();
        for (JsonNode record : value) {
            assertEquals(23388, record.required("pid").asInt()); assertEquals("127.0.0.1", record.required("address").asText());
            assertEquals("java.exe", record.required("name").asText().toLowerCase(Locale.ROOT));
            String command = record.required("command").asText();
            assertTrue(command.contains("io.questdb/io.questdb.ServerMain"));
            assertTrue(command.contains("-d " + root) || command.contains("-d \"" + root + "\""));
            assertFalse(record.required("birth").asText().isBlank());
        }
        assertEquals(Set.of(19020, 18832), Set.of(value.get(0).required("port").asInt(), value.get(1).required("port").asInt()));
        return value;
    }

    private static HikariDataSource pool(String name, String host, int port, String username, String password) {
        var pool = new HikariDataSource(); pool.setPoolName(name); pool.setJdbcUrl("jdbc:postgresql://" + host + ":" + port + "/qdb");
        pool.setUsername(username); pool.setPassword(password); pool.setMaximumPoolSize(1); pool.setConnectionTimeout(10000); return pool;
    }
    private static JdbcTemplate jdbc(HikariDataSource pool) {
        var jdbc = new JdbcTemplate(pool); jdbc.setQueryTimeout(20); return jdbc;
    }
    private static String required(String key) { return Objects.requireNonNull(System.getenv(key), "Required local environment: " + key); }
    private static String sha(Path path) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path))); }
}
