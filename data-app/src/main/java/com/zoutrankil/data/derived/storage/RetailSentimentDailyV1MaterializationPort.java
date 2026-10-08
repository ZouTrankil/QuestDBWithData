package com.zoutrankil.data.derived.storage;

import com.zoutrankil.data.repository.PrivateQuestDbAttestations;
import com.zoutrankil.data.repository.QuestDbBoundedReader;
import com.zoutrankil.data.calendar.port.ExchangeCalendarReadPort;
import com.zoutrankil.data.derived.port.RetailSentimentDailyV1Session;
import com.zoutrankil.data.derived.domain.RetailSentimentDailyV1FullSourceScope;
import com.zoutrankil.data.derived.domain.RetailSentimentDailyV1Policy;

import com.zoutrankil.data.calendar.storage.ExchangeCalendarReadRepository;

import com.zoutrankil.data.domain.RetailSentimentDailyV1Snapshot;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zoutrankil.data.config.QuestDbProperties;
import com.zoutrankil.data.domain.RetailSentimentDailyV1;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.Duration;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Calendar;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TimeZone;
import java.util.TreeMap;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import org.springframework.jdbc.core.JdbcTemplate;

/** One explicitly enabled native refresh, followed by bounded real QuestDB readback. */
public final class RetailSentimentDailyV1MaterializationPort
        implements RetailSentimentDailyV1Session {
    private static final ObjectMapper FIXTURE_JSON = new ObjectMapper();
    public static final String SOURCE = RetailSentimentDailyV1Policy.SOURCE;
    public static final String OUTPUT = RetailSentimentDailyV1Policy.OUTPUT;
    public static final int MAX_WINDOW_DAYS = RetailSentimentDailyV1Policy.MAX_WINDOW_DAYS;
    public static final long MAX_SOURCE_ROWS = RetailSentimentDailyV1Policy.MAX_SOURCE_ROWS;
    private static final String COLUMNS = "trade_date,avg_retail_ratio,avg_retail_entropy,total_retail_amount_yi,"
            + "total_retail_net_inflow_yi,avg_rel_aggro,total_q1,total_q3,avg_wash_trade_ratio,"
            + "total_spoof_count,total_manipulation_count,avg_mfi_score,total_main_net_yi";
    private static final String AGGREGATES = "ts AS trade_date, avg(gmm_retail_ratio) AS avg_retail_ratio, "
            + "avg(mean_retail_entropy) AS avg_retail_entropy, "
            + "sum(retail_total_amount) / 100000000.0 AS total_retail_amount_yi, "
            + "sum(retail_funds_net_inflow) / 100000000.0 AS total_retail_net_inflow_yi, "
            + "avg(mean_rel_aggro) AS avg_rel_aggro, sum(q1_count) AS total_q1, sum(q3_count) AS total_q3, "
            + "avg(wash_trade_ratio) AS avg_wash_trade_ratio, sum(spoof_count) AS total_spoof_count, "
            + "sum(fake_support_count + fake_pressure_count) AS total_manipulation_count, "
            + "avg(mfi_score) AS avg_mfi_score, sum(main_net_inflow) / 100000000.0 AS total_main_net_yi";

    public static final String DEFINITION_SQL = "SELECT " + AGGREGATES + " FROM " + SOURCE
            + " SAMPLE BY 1d ALIGN TO CALENDAR";
    public static final String DEFINITION_SHA = sha(normalized(DEFINITION_SQL));


    private record Physical(long id, String directory, long txn, boolean suspended, long pendingRows,
                            String partition, boolean wal, boolean dedup) {}
    private record Wal(long sequencer, long writer, long buffered, boolean suspended) {}
    private record Refresh(String status, String reason, String source, String sql, String type,
                           long interval, String unit, long refreshed, long base,
                           String started, String finished) {}
    private record Metadata(Physical source, Wal sourceWal, Physical output, Wal outputWal, Refresh refresh) {}

    private final JdbcTemplate jdbc;
    private final QuestDbProperties properties;
    private final boolean mutationsEnabled;
    private final String expectedTargetId;
    private final PrivateQuestDbAttestations.Attestation privateInstanceAttestor;
    private LocalDate from, to;
    private RetailSentimentDailyV1Snapshot frozen, submittedAt, readbackSnapshot, verifiedSnapshot;
    private boolean submitted, acknowledged;
    private boolean fullIsolated;
    private List<RetailSentimentDailyV1> submittedRows = List.of();
    private BooleanSupplier cancelled = () -> false;

    public RetailSentimentDailyV1MaterializationPort(JdbcTemplate jdbc, QuestDbProperties properties,
                                                  boolean mutationsEnabled) {
        this(jdbc, properties, mutationsEnabled, null);
    }

    /** A supplied physical target identity is explicit deployment admission; the default three-argument port is isolated only. */
    public RetailSentimentDailyV1MaterializationPort(JdbcTemplate jdbc, QuestDbProperties properties,
                                                  boolean mutationsEnabled, String expectedTargetId) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.properties = Objects.requireNonNull(properties);
        this.mutationsEnabled = mutationsEnabled;
        if (expectedTargetId != null && !expectedTargetId.matches("questdb-[0-9a-f]{64}"))
            throw new IllegalArgumentException("D098 exact expected physical target identity required");
        this.expectedTargetId = expectedTargetId;
        this.privateInstanceAttestor = PrivateQuestDbAttestations.retailSentimentV1(this::verifyPrivateFixture);
    }

    public RetailSentimentDailyV1Snapshot snapshot() {
        requireSourceSchema();
        requireOutputSchema();
        Metadata first = metadata();
        Metadata second = metadata();
        if (!first.equals(second)) throw new IllegalStateException("D098 metadata changed during snapshot");
        return snapshot(first);
    }

    private RetailSentimentDailyV1Snapshot snapshot(Metadata state) {
        var source = state.source;
        var output = state.output;
        var sourceWal = state.sourceWal;
        var outputWal = state.outputWal;
        var refresh = state.refresh;
        if (!"DAY".equalsIgnoreCase(source.partition) || !source.wal || !source.dedup
                || !"MONTH".equalsIgnoreCase(output.partition) || !output.wal || output.dedup)
            throw new IllegalStateException("D098 source or MV physical contract differs");
        if (!SOURCE.equals(refresh.source) || !"timer".equalsIgnoreCase(refresh.type)
                || refresh.interval != 1 || !"MINUTE".equalsIgnoreCase(refresh.unit)
                || !normalized(DEFINITION_SQL).equals(normalized(refresh.sql)))
            throw new IllegalStateException("D098 MV definition or timer policy differs from authoritative SQL");
        boolean sourceSettled = !source.suspended && !sourceWal.suspended && source.pendingRows == 0
                && sourceWal.buffered == 0 && sourceWal.writer == sourceWal.sequencer;
        boolean outputSettled = !output.suspended && !outputWal.suspended && output.pendingRows == 0
                && outputWal.buffered == 0 && outputWal.writer == outputWal.sequencer;
        boolean valid = "valid".equalsIgnoreCase(refresh.status) && blank(refresh.reason);
        boolean caughtUp = valid && sourceSettled && outputSettled
                && refresh.refreshed == sourceWal.sequencer && refresh.base == sourceWal.sequencer;
        return new RetailSentimentDailyV1Snapshot(source.id, source.directory, source.txn, sourceWal.sequencer, sourceWal.writer,
                sourceSettled, output.id, output.directory, output.txn, outputWal.sequencer,
                outputWal.writer, outputSettled, valid, caughtUp, sha(normalized(refresh.sql)),
                refresh.started, refresh.finished, refresh.refreshed, refresh.base, source.partition, refresh.status);
    }

    public String targetId() {
        RetailSentimentDailyV1Snapshot state = snapshot();
        return "questdb-" + sha(properties.getHost() + ":" + properties.getPgPort() + ":"
                + properties.getQwpPort() + ":" + properties.getDatabase() + ":" + SOURCE + ":"
                + state.sourceId() + ":" + state.sourceDirectory() + ":" + OUTPUT + ":"
                + state.mvId() + ":" + state.mvDirectory() + ":" + state.definitionSha());
    }

    /** The real calendar has an independent WAL frontier; source settlement does not certify calendar coverage. */
    public String calendarVersion() {
        Physical before = physical("exchange_calendar");
        Wal walBefore = wal("exchange_calendar");
        var keys = jdbc.queryForList("SELECT \"column\",designated,upsertKey FROM table_columns('exchange_calendar')");
        var upsertKeys = new HashSet<String>();
        var designated = new HashSet<String>();
        for (var column : keys) {
            if (Boolean.TRUE.equals(column.get("upsertKey"))) upsertKeys.add(column.get("column").toString());
            if (Boolean.TRUE.equals(column.get("designated"))) designated.add(column.get("column").toString());
        }
        var types = schema("exchange_calendar");
        if (!"SYMBOL".equals(types.get("exchange")) || !"TIMESTAMP".equals(types.get("cal_date"))
                || !"INT".equals(types.get("is_open")) || !"STRING".equals(types.get("pretrade_date"))
                || !upsertKeys.equals(Set.of("exchange", "cal_date")) || !designated.equals(Set.of("cal_date"))
                || !"YEAR".equalsIgnoreCase(before.partition) || !before.wal || !before.dedup)
            throw new IllegalStateException("D098 exchange calendar physical schema, timestamp or complete key differs");
        Physical after = physical("exchange_calendar");
        Wal walAfter = wal("exchange_calendar");
        if (!before.equals(after) || !walBefore.equals(walAfter) || after.suspended || walAfter.suspended
                || after.pendingRows != 0 || walAfter.buffered != 0 || walAfter.writer != walAfter.sequencer)
            throw new IllegalStateException("D098 exchange calendar version changed or has unsettled/suspended WAL");
        return "calendar-" + after.id + ":" + walAfter.sequencer + ":" + after.txn + ":"
                + sha(after.directory + ":" + types);
    }

    /** Every calendar day must exist, and every recorded open session must have exactly one real source daily bucket. */
    public ExchangeCalendarReadPort newCalendarReader() {
        var calendars = new ExchangeCalendarReadRepository(new QuestDbBoundedReader(jdbc));
        return calendars::findPage;
    }

    public long sourceRawRows(LocalDate start, LocalDate end) {
        window(start, end);
        Long count = jdbc.query(connection -> {
            var statement = connection.prepareStatement("SELECT count() FROM " + SOURCE
                    + " WHERE ts >= ? AND ts < ?");
            bounds(statement, start, end);
            return statement;
        }, rows -> {
            if (!rows.next()) throw new IllegalStateException("D098 source count is absent");
            long value = rows.getLong(1);
            if (rows.wasNull() || value < 0 || rows.next())
                throw new IllegalStateException("Invalid D098 source row count");
            return value;
        });
        if (count == null || count > MAX_SOURCE_ROWS)
            throw new IllegalStateException("D098 source exceeds the 200000-row finite refresh budget");
        Boolean invalidKeys = jdbc.query(connection -> {
            var statement = connection.prepareStatement("SELECT ts,symbol,key_rows FROM ("
                    + "SELECT ts,symbol,count() AS key_rows FROM " + SOURCE
                    + " WHERE ts >= ? AND ts < ? GROUP BY ts,symbol) "
                    + "WHERE key_rows <> 1 OR symbol IS NULL OR symbol = '' "
                    + "OR cast(ts AS long) % 86400000000 <> 0 LIMIT 1");
            bounds(statement, start, end);
            statement.setMaxRows(1);
            return statement;
        }, (org.springframework.jdbc.core.ResultSetExtractor<Boolean>) rows -> rows.next());
        if (Boolean.TRUE.equals(invalidKeys))
            throw new IllegalStateException("D098 source has null/duplicate complete keys or non-calendar timestamps");
        return count;
    }

    /**
     * Independently visits every bounded physical source row. Native LONG aggregates can wrap, so native
     * aggregate parity alone cannot certify count values. This certifies the physical interval, not the
     * upstream provider universe, whose admission belongs to the D086 dependency.
     */
    public void requireSourceCoverage(LocalDate start, LocalDate end, long expectedRows,
                                      List<RetailSentimentDailyV1> expected) {
        window(start, end);
        Objects.requireNonNull(expected);
        if (expectedRows < 0 || expectedRows > MAX_SOURCE_ROWS)
            throw new IllegalStateException("D098 source census exceeds the finite source budget");
        var census = jdbc.query(connection -> {
            var statement = connection.prepareStatement("SELECT ts,q1_count,q3_count,spoof_count,"
                    + "fake_support_count,fake_pressure_count FROM " + SOURCE
                    + " WHERE ts >= ? AND ts < ? LIMIT 200001");
            bounds(statement, start, end);
            statement.setMaxRows(200001);
            statement.setFetchSize(1000);
            return statement;
        }, (org.springframework.jdbc.core.ResultSetExtractor<Map<LocalDate, DailyCounts>>) rows -> {
            var days = new TreeMap<LocalDate, DailyCounts>();
            long visited = 0;
            while (rows.next()) {
                if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted())
                    throw new CancellationException("D098 source census cancelled");
                if (++visited > MAX_SOURCE_ROWS)
                    throw new IllegalStateException("D098 source census exceeds the finite source budget");
                LocalDate date = day(rows, "ts");
                if (date.isBefore(start) || date.isAfter(end))
                    throw new IllegalStateException("D098 source census has an out-of-window day");
                var counts = days.computeIfAbsent(date, ignored -> new DailyCounts());
                counts.add(nullableLong(rows, "q1_count"), nullableLong(rows, "q3_count"),
                        nullableLong(rows, "spoof_count"), nullableLong(rows, "fake_support_count"),
                        nullableLong(rows, "fake_pressure_count"));
            }
            if (visited != expectedRows)
                throw new IllegalStateException("D098 source census count differs from the complete physical interval");
            return days;
        });
        if (census == null || !List.copyOf(census.keySet()).equals(
                expected.stream().map(RetailSentimentDailyV1::tradeDate).toList()))
            throw new IllegalStateException("D098 daily output does not cover all physical source buckets");
        for (var row : expected) {
            var counts = census.get(row.tradeDate());
            if (!Objects.equals(counts.exact(counts.q1), row.totalQ1())
                    || !Objects.equals(counts.exact(counts.q3), row.totalQ3())
                    || !Objects.equals(counts.exact(counts.spoof), row.totalSpoofCount())
                    || !Objects.equals(counts.exact(counts.manipulation), row.totalManipulationCount()))
                throw new IllegalStateException("D098 native count aggregate differs from the independent source census");
        }
    }

    private static final class DailyCounts {
        private BigInteger q1, q3, spoof, manipulation;

        private void add(Long nextQ1, Long nextQ3, Long nextSpoof, Long support, Long pressure) {
            q1 = sum(q1, nextQ1);
            q3 = sum(q3, nextQ3);
            spoof = sum(spoof, nextSpoof);
            // SQL adds within each row first. A NULL operand excludes the whole pair from SUM.
            if (support != null && pressure != null) {
                try { manipulation = sum(manipulation, Math.addExact(support, pressure)); }
                catch (ArithmeticException overflow) {
                    throw new IllegalStateException("D098 per-row manipulation LONG addition overflows", overflow);
                }
            }
        }

        private static BigInteger sum(BigInteger total, Long value) {
            if (value == null) return total;
            if (value < 0) throw new IllegalStateException("D098 source count must be nonnegative");
            return (total == null ? BigInteger.ZERO : total).add(BigInteger.valueOf(value));
        }

        private Long exact(BigInteger value) {
            if (value == null) return null;
            try { return value.longValueExact(); }
            catch (ArithmeticException overflow) {
                throw new IllegalStateException("D098 complete daily LONG sum overflows", overflow);
            }
        }
    }

    public List<RetailSentimentDailyV1> expected(LocalDate start, LocalDate end) {
        window(start, end);
        sourceRawRows(start, end);
        return rows("SELECT " + AGGREGATES + " FROM " + SOURCE
                + " WHERE ts >= ? AND ts < ? SAMPLE BY 1d ALIGN TO CALENDAR"
                + " ORDER BY trade_date LIMIT 32", start, end);
    }

    /** Reads the entire bounded output interval, including unexpected rows and duplicates. */
    public List<RetailSentimentDailyV1> actual(LocalDate start, LocalDate end) {
        window(start, end);
        return rows("SELECT " + COLUMNS + " FROM " + OUTPUT
                + " WHERE trade_date >= ? AND trade_date < ? ORDER BY trade_date LIMIT 32", start, end);
    }

    public long outputRowCount() {
        Long count = jdbc.query("SELECT count() FROM " + OUTPUT, rs -> {
            if (!rs.next()) throw new IllegalStateException("D098 full output row count is absent");
            long value = rs.getLong(1);
            if (rs.wasNull() || value < 0 || rs.next()) throw new IllegalStateException("D098 output row count differs");
            return value;
        });
        if (count == null) throw new IllegalStateException("D098 output row count is absent");
        return count;
    }

    public void bind(LocalDate start, LocalDate end, RetailSentimentDailyV1Snapshot expected) {
        window(start, end);
        Objects.requireNonNull(expected);
        if (submitted || frozen != null && (!from.equals(start) || !to.equals(end) || !frozen.equals(expected)))
            throw new IllegalStateException("D098 port cannot change or reuse its bound refresh request");
        from = start;
        to = end;
        frozen = expected;
    }

    public void cancellationProbe(BooleanSupplier probe) { cancelled = Objects.requireNonNull(probe); }

    /** DDL is an explicit isolated installation operation; reads never install or redefine objects. */
    public void createIsolatedTarget() {
        verifyPrivateInstance();
        requireInstallSourceReady();
        var found = jdbc.queryForList("SELECT view_name FROM materialized_views() WHERE view_name='" + OUTPUT + "'");
        if (found.size() > 1) throw new IllegalStateException("Duplicate D098 MV identity");
        if (found.isEmpty()) jdbc.execute("CREATE MATERIALIZED VIEW " + OUTPUT
                + " REFRESH EVERY 1m AS (" + DEFINITION_SQL + ") PARTITION BY MONTH");
        else snapshot();
    }

    /** Source preflight must work before the MV exists, so no DDL can precede the physical contract check. */
    private void requireInstallSourceReady() {
        requireSourceSchema();
        Physical before = physical(SOURCE);
        Wal walBefore = wal(SOURCE);
        Physical after = physical(SOURCE);
        Wal walAfter = wal(SOURCE);
        if (!before.equals(after) || !walBefore.equals(walAfter)
                || !"DAY".equalsIgnoreCase(after.partition) || !after.wal || !after.dedup
                || after.suspended || walAfter.suspended || after.pendingRows != 0
                || walAfter.buffered != 0 || walAfter.writer != walAfter.sequencer)
            throw new IllegalStateException("D098 source installation contract changed or is not DAY/WAL/DEDUP with settled WAL");
    }
    /** Selects a repair operation before binding; submission still goes through the shared durable runner. */
    public void configureFullIsolated() {
        requireIsolatedMutation();
        if (submitted || frozen != null || fullIsolated)
            throw new IllegalStateException("D098 FULL repair must be selected on a fresh isolated port");
        fullIsolated = true;
    }



    /** FULL is bounded by the complete physical source, never by a convenient subset. Empty sources are rejected. */
    public RetailSentimentDailyV1FullSourceScope fullSourceScope() {
        requireIsolatedMutation();
        RetailSentimentDailyV1Snapshot before = snapshot();
        RetailSentimentDailyV1FullSourceScope scope = jdbc.query("SELECT count() AS n,min(ts) AS lo,max(ts) AS hi FROM " + SOURCE, rs -> {
            if (!rs.next()) throw new IllegalStateException("D098 full-source bounds are absent");
            long count = requiredLong(rs, "n");
            if (count < 1 || count > MAX_SOURCE_ROWS)
                throw new IllegalStateException("D098 FULL requires a nonempty complete source of at most 200000 rows");
            Timestamp first = rs.getTimestamp("lo", utc()), last = rs.getTimestamp("hi", utc());
            if (first == null || last == null || rs.next())
                throw new IllegalStateException("D098 FULL requires exact complete source date bounds");
            var start = first.toInstant().atOffset(ZoneOffset.UTC).toLocalDate();
            var end = last.toInstant().atOffset(ZoneOffset.UTC).toLocalDate();
            window(start, end);
            return new RetailSentimentDailyV1FullSourceScope(start, end, count);
        });
        if (scope.rawRows() != sourceRawRows(scope.from(), scope.to()) || !before.sourceUnchanged(snapshot()))
            throw new IllegalStateException("D098 complete source changed while bounding isolated FULL repair");
        return scope;
    }

    @Override public void preflight() {
        if (frozen == null) throw new IllegalStateException("D098 refresh interval is not frozen");
        if (fullIsolated) requireIsolatedMutation();
        else requireAdmittedMutation();
        RetailSentimentDailyV1Snapshot current = snapshot();
        requireFrozenSource(current);
        if ((!fullIsolated && !current.valid()) || "refreshing".equalsIgnoreCase(current.viewStatus())
                || !current.sourceSettled() || !current.mvSettled())
            throw new IllegalStateException("D098 MV is invalid, refreshing or WAL is unsettled");
        if (fullIsolated) {
            RetailSentimentDailyV1FullSourceScope scope = fullSourceScope();
            if (!scope.from().equals(from) || !scope.to().equals(to))
                throw new IllegalStateException("D098 FULL frozen window must cover the entire complete physical source");
            requireFrozenSource(snapshot());
        } else requireFinitePendingRefresh(current);
    }

    /** The native WAL refresh may visit the whole source; lagging sources therefore have a global work bound. */
    private void requireFinitePendingRefresh(RetailSentimentDailyV1Snapshot current) {
        if (current.caughtUp()) return;
        var scope = jdbc.queryForMap("SELECT count() AS n,min(ts) AS lo,max(ts) AS hi FROM " + SOURCE);
        long count = ((Number) scope.get("n")).longValue();
        if (count < 0 || count > MAX_SOURCE_ROWS) throw new IllegalStateException("D098 pending native refresh exceeds global source budget");
        if (count > 0) {
            var lo = ((java.sql.Timestamp) scope.get("lo")).toInstant().atZone(ZoneOffset.UTC).toLocalDate();
            var hi = ((java.sql.Timestamp) scope.get("hi")).toInstant().atZone(ZoneOffset.UTC).toLocalDate();
            if (ChronoUnit.DAYS.between(lo, hi) >= MAX_WINDOW_DAYS)
                throw new IllegalStateException("D098 pending native refresh exceeds the global 31-day span budget");
            // INCREMENTAL can touch every pending source day, including days outside the requested readback window.
            requireSourceCoverage(lo, hi, count, expected(lo, hi));
        }
    }

    @Override public void send(List<RetailSentimentDailyV1> rows) {
        if (submitted || rows == null || rows.isEmpty() || rows.size() > MAX_WINDOW_DAYS)
            throw new IllegalStateException("D098 requires exactly one nonempty finite native refresh submission");
        preflight();
        var expected = expected(from, to);
        if (expected.size() != rows.size()) throw new IllegalStateException("D098 source row count changed before submission");
        for (int index = 0; index < rows.size(); index++)
            if (!equivalent(expected.get(index), rows.get(index)))
                throw new IllegalStateException("D098 source values changed before submission");
        requireSourceCoverage(from, to, sourceRawRows(from, to), expected);
        submittedAt = snapshot();
        requireFrozenSource(submittedAt);
        submittedRows = List.copyOf(rows);
        submitted = true;
        // QuestDB 10.0.1 RANGE persists a start but not its finish. INCREMENTAL preserves valid-state evidence.
        // Do not work around this by treating a refreshing view as valid or by issuing a formal FULL.
        jdbc.execute("REFRESH MATERIALIZED VIEW " + OUTPUT + (fullIsolated ? " FULL" : " INCREMENTAL"));
        acknowledged = true;
    }

    @Override public List<RetailSentimentDailyV1> readback(List<LocalDate> keys) {
        if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) {
            // The shared batch executor retains the uncertain slice when its polling sleep is interrupted.
            Thread.currentThread().interrupt();
            throw new CancellationException("D098 refresh cancelled after submission; reconcile before replay");
        }
        if (frozen == null || keys == null || keys.isEmpty() || keys.size() > MAX_WINDOW_DAYS
                || keys.stream().anyMatch(Objects::isNull) || new HashSet<>(keys).size() != keys.size())
            throw new IllegalArgumentException("D098 bounded complete date keys required");
        if (submitted && !submittedRows.stream().map(RetailSentimentDailyV1::tradeDate).toList().equals(keys))
            throw new IllegalArgumentException("D098 refresh readback must cover the complete submitted window");
        RetailSentimentDailyV1Snapshot before = snapshot();
        requireFrozenSource(before);
        requireReadable(before);
        var actual = actual(from, to);
        RetailSentimentDailyV1Snapshot after = snapshot();
        if (!before.equals(after)) throw new IllegalStateException("D098 output changed during real readback");
        requireFrozenSource(after);
        readbackSnapshot = after;
        return actual;
    }

    @Override public boolean walSettled() {
        if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) {
            Thread.currentThread().interrupt();
            return false;
        }
        if (readbackSnapshot == null) return false;
        RetailSentimentDailyV1Snapshot current = snapshot();
        requireFrozenSource(current);
        if (!current.equals(readbackSnapshot) || !current.valid() || !current.caughtUp()) return false;
        if (submitted && (!acknowledged || !refreshCompleted(current))) return false;
        verifiedSnapshot = current;
        return true;
    }

    private boolean refreshCompleted(RetailSentimentDailyV1Snapshot current) {
        boolean ready = submittedAt != null && acknowledged && current.valid() && current.caughtUp()
                && frozen.sourceUnchanged(current) && current.mvSettled();
        return ready && (!fullIsolated || current.mvTxn() != submittedAt.mvTxn()
                || !Objects.equals(current.refreshFinished(), submittedAt.refreshFinished()));
        // A caught-up INCREMENTAL can be a no-op. Its unchanged txn is accepted only with complete real value readback.
    }

    /** A lost asynchronous refresh response does not prove that its server-side writer has stopped. */
    @Override public boolean uncertainSenderStopped() {
        if (cancelled.getAsBoolean()) Thread.currentThread().interrupt();
        return false;
    }
    public boolean unresolved() { return submitted && verifiedSnapshot == null; }
    public RetailSentimentDailyV1Snapshot verifiedSnapshot() { return verifiedSnapshot; }
    public Duration visibilityTimeout() { return Duration.ofMinutes(3); }

    private void requireFrozenSource(RetailSentimentDailyV1Snapshot state) {
        if (!frozen.sourceUnchanged(state) || frozen.mvId() != state.mvId()
                || !Objects.equals(frozen.mvDirectory(), state.mvDirectory())
                || !Objects.equals(frozen.definitionSha(), state.definitionSha()))
            throw new IllegalStateException("D098 frozen source version, physical identity or definition changed");
    }

    private static void requireReadable(RetailSentimentDailyV1Snapshot state) {
        if (!state.valid() || !state.caughtUp() || !state.sourceSettled() || !state.mvSettled())
            throw new IllegalStateException("D098 MV is invalid, lagging or has unsettled/suspended WAL");
    }

    public void verifyPrivateInstance() { requireIsolatedMutation(); }

    private void requireIsolatedMutation() {
        if (!mutationsEnabled || !Set.of("127.0.0.1", "localhost", "::1").contains(properties.getHost())
                || properties.getPgPort() != 18822 || properties.getQwpPort() != 19010)
            throw new IllegalStateException("D098 mutations require the explicitly enabled local private instance on 18822/19010");
        privateInstanceAttestor.attest();
    }

    private void requireAdmittedMutation() {
        if (!mutationsEnabled) throw new IllegalStateException("D098 materialization mutations are disabled");
        if (expectedTargetId == null) requireIsolatedMutation();
        else if (!expectedTargetId.equals(targetId()))
            throw new IllegalStateException("D098 admitted physical target identity differs from the actual instance");
    }

    private void verifyPrivateFixture(Path root) throws IOException {
        var marker = FIXTURE_JSON.readTree(root.resolve("d098-fixture.json").toFile());
        if (!"D098".equals(marker.path("task_id").asText())
                || !root.equals(Path.of(marker.path("data_root").asText("")).toAbsolutePath().normalize().toRealPath())
                || !marker.path("fixture_tables").isArray() || marker.path("fixture_tables").size() != 2
                || !Set.of(SOURCE, OUTPUT).equals(Set.of(marker.path("fixture_tables").get(0).asText(),
                        marker.path("fixture_tables").get(1).asText())))
            throw new IllegalStateException("D098 private fixture marker differs from the admitted source and MV");
    }

    private Metadata metadata() {
        return new Metadata(physical(SOURCE), wal(SOURCE), physical(OUTPUT), wal(OUTPUT), refresh());
    }

    private Physical physical(String name) {
        return jdbc.query("SELECT id,directoryName,table_txn,table_suspended,wal_pending_row_count,"
                + "partitionBy,walEnabled,dedup FROM tables() WHERE table_name='" + name + "'", rs -> {
            if (!rs.next()) throw new IllegalStateException("Missing D098 physical table: " + name);
            var result = new Physical(requiredLong(rs, "id"), requiredText(rs, "directoryName"),
                    requiredLong(rs, "table_txn"), rs.getBoolean("table_suspended"),
                    requiredLong(rs, "wal_pending_row_count"), requiredText(rs, "partitionBy"),
                    rs.getBoolean("walEnabled"), rs.getBoolean("dedup"));
            if (rs.next()) throw new IllegalStateException("Duplicate D098 physical table: " + name);
            return result;
        });
    }

    private Wal wal(String name) {
        return jdbc.query("SELECT sequencerTxn,writerTxn,bufferedTxnSize,suspended FROM wal_tables() WHERE name='"
                + name + "'", rs -> {
            if (!rs.next()) throw new IllegalStateException("Missing D098 WAL table: " + name);
            var result = new Wal(requiredLong(rs, "sequencerTxn"), requiredLong(rs, "writerTxn"),
                    requiredLong(rs, "bufferedTxnSize"), rs.getBoolean("suspended"));
            if (rs.next()) throw new IllegalStateException("Duplicate D098 WAL table: " + name);
            return result;
        });
    }

    private Refresh refresh() {
        return jdbc.query("SELECT view_status,invalidation_reason,base_table_name,view_sql,refresh_type,"
                + "timer_interval,timer_interval_unit,refresh_base_table_txn,base_table_txn,"
                + "last_refresh_start_timestamp,last_refresh_finish_timestamp FROM materialized_views() WHERE view_name='"
                + OUTPUT + "'", rs -> {
            if (!rs.next()) throw new IllegalStateException("Missing D098 native MV metadata");
            var result = new Refresh(requiredText(rs, "view_status"), rs.getString("invalidation_reason"),
                    requiredText(rs, "base_table_name"), requiredText(rs, "view_sql"), requiredText(rs, "refresh_type"),
                    requiredLong(rs, "timer_interval"), requiredText(rs, "timer_interval_unit"),
                    optionalTxn(rs, "refresh_base_table_txn"), optionalTxn(rs, "base_table_txn"),
                    rs.getString("last_refresh_start_timestamp"), rs.getString("last_refresh_finish_timestamp"));
            if (rs.next()) throw new IllegalStateException("Duplicate D098 native MV metadata");
            return result;
        });
    }

    private void requireSourceSchema() {
        var schema = schema(SOURCE);
        var expected = new LinkedHashMap<String, String>();
        expected.put("ts", "TIMESTAMP");
        expected.put("symbol", "SYMBOL");
        for (String name : List.of("gmm_retail_ratio", "mean_retail_entropy", "retail_total_amount",
                "retail_funds_net_inflow", "mean_rel_aggro", "wash_trade_ratio", "mfi_score", "main_net_inflow"))
            expected.put(name, "DOUBLE");
        for (String name : List.of("q1_count", "q3_count", "spoof_count", "fake_support_count", "fake_pressure_count"))
            expected.put(name, "LONG");
        for (var column : expected.entrySet())
            if (!column.getValue().equals(schema.get(column.getKey())))
                throw new IllegalStateException("D098 base source column/type differs: " + column.getKey());
        var keys = jdbc.queryForList("SELECT \"column\",designated,upsertKey FROM table_columns('" + SOURCE + "')");
        var upsertKeys = new HashSet<String>();
        var designated = new HashSet<String>();
        for (var column : keys) {
            if (Boolean.TRUE.equals(column.get("upsertKey"))) upsertKeys.add(column.get("column").toString());
            if (Boolean.TRUE.equals(column.get("designated"))) designated.add(column.get("column").toString());
        }
        if (!upsertKeys.equals(Set.of("ts", "symbol")) || !designated.equals(Set.of("ts")))
            throw new IllegalStateException("D098 source complete UPSERT key or designated timestamp differs");
    }

    private void requireOutputSchema() {
        var expected = new LinkedHashMap<String, String>();
        expected.put("trade_date", "TIMESTAMP");
        for (String name : List.of("avg_retail_ratio", "avg_retail_entropy", "total_retail_amount_yi",
                "total_retail_net_inflow_yi", "avg_rel_aggro"))
            expected.put(name, "DOUBLE");
        expected.put("total_q1", "LONG");
        expected.put("total_q3", "LONG");
        expected.put("avg_wash_trade_ratio", "DOUBLE");
        expected.put("total_spoof_count", "LONG");
        expected.put("total_manipulation_count", "LONG");
        expected.put("avg_mfi_score", "DOUBLE");
        expected.put("total_main_net_yi", "DOUBLE");
        if (!expected.equals(schema(OUTPUT))) throw new IllegalStateException("D098 MV thirteen-column schema differs");
        var metadata = jdbc.queryForList("SELECT \"column\",designated,upsertKey FROM table_columns('" + OUTPUT + "')");
        var designated = new HashSet<String>();
        for (var column : metadata) {
            if (Boolean.TRUE.equals(column.get("upsertKey"))) throw new IllegalStateException("Native D098 MV must not declare UPSERT keys");
            if (Boolean.TRUE.equals(column.get("designated"))) designated.add(column.get("column").toString());
        }
        if (!designated.equals(Set.of("trade_date"))) throw new IllegalStateException("D098 MV timestamp differs");
    }

    private Map<String, String> schema(String name) {
        var result = new LinkedHashMap<String, String>();
        jdbc.query("SELECT \"column\",\"type\" FROM table_columns('" + name + "')", rs -> {
            String column = rs.getString(1);
            if (result.putIfAbsent(column, rs.getString(2).toUpperCase(Locale.ROOT)) != null)
                throw new IllegalStateException("Duplicate D098 schema column");
        });
        return result;
    }

    private List<RetailSentimentDailyV1> rows(String sql, LocalDate start, LocalDate end) {
        List<RetailSentimentDailyV1> values = jdbc.query(connection -> {
            var statement = connection.prepareStatement(sql);
            bounds(statement, start, end);
            statement.setMaxRows(32);
            return statement;
        }, (rs, row) -> new RetailSentimentDailyV1(day(rs, "trade_date"),
                nullableDouble(rs, "avg_retail_ratio"), nullableDouble(rs, "avg_retail_entropy"),
                nullableDouble(rs, "total_retail_amount_yi"), nullableDouble(rs, "total_retail_net_inflow_yi"),
                nullableDouble(rs, "avg_rel_aggro"), nullableLong(rs, "total_q1"), nullableLong(rs, "total_q3"),
                nullableDouble(rs, "avg_wash_trade_ratio"), nullableLong(rs, "total_spoof_count"),
                nullableLong(rs, "total_manipulation_count"), nullableDouble(rs, "avg_mfi_score"),
                nullableDouble(rs, "total_main_net_yi")));

        if (values.size() > ChronoUnit.DAYS.between(start, end) + 1)
            throw new IllegalStateException("D098 daily aggregation exceeds its bounded date count");
        var keys = new HashSet<LocalDate>();
        for (var value : values)
            if (value.tradeDate().isBefore(start) || value.tradeDate().isAfter(end) || !keys.add(value.tradeDate()))
                throw new IllegalStateException("D098 output has duplicate or out-of-window dates");
        return List.copyOf(values);
    }

    private static void bounds(PreparedStatement statement, LocalDate start, LocalDate end) throws SQLException {
        statement.setQueryTimeout(20);
        statement.setTimestamp(1, Timestamp.from(start.atStartOfDay().toInstant(ZoneOffset.UTC)), utc());
        statement.setTimestamp(2, Timestamp.from(end.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC)), utc());
    }
    private static LocalDate day(ResultSet rs, String column) throws SQLException {
        Timestamp stamp = rs.getTimestamp(column, utc());
        if (stamp == null) throw new IllegalStateException("Null D098 day key");
        var calendar = stamp.toInstant().atOffset(ZoneOffset.UTC);
        if (!calendar.toLocalTime().equals(java.time.LocalTime.MIDNIGHT))
            throw new IllegalStateException("D098 day bucket is not an exact calendar midnight");
        return calendar.toLocalDate();
    }
    private static Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        if (rs.wasNull()) return null;
        if (value < 0) throw new IllegalStateException("D098 count must be nonnegative: " + column);
        return value;
    }
    private static Calendar utc() { return Calendar.getInstance(TimeZone.getTimeZone("UTC")); }
    private static long requiredLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        if (rs.wasNull()) throw new IllegalStateException("Null D098 required number: " + column);
        return value;
    }
    private static String requiredText(ResultSet rs, String column) throws SQLException {
        String value = rs.getString(column);
        if (blank(value)) throw new IllegalStateException("Null D098 required metadata: " + column);
        return value;
    }
    private static Double nullableDouble(ResultSet rs, String column) throws SQLException {
        double value = rs.getDouble(column);
        if (rs.wasNull()) return null;
        if (!Double.isFinite(value)) throw new IllegalStateException("Non-finite D098 aggregate: " + column);
        return value;
    }
    private static long optionalTxn(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? -1 : value;
    }
    public static boolean equivalent(RetailSentimentDailyV1 left, RetailSentimentDailyV1 right) {
        return RetailSentimentDailyV1Policy.equivalent(left, right);
    }


    private static void window(LocalDate start, LocalDate end) { RetailSentimentDailyV1Policy.window(start, end); }
    private static boolean blank(String value) { return value == null || value.isBlank(); }
    private static String normalized(String value) { return value == null ? "" : value.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT); }
    private static String sha(String text) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception impossible) { throw new IllegalStateException(impossible); }
    }
}
