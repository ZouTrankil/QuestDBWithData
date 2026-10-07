package com.zoutrankil.data.derived.storage;

import com.zoutrankil.data.repository.PrivateQuestDbAttestations;
import com.zoutrankil.data.repository.QuestDbBoundedReader;
import com.zoutrankil.data.calendar.port.ExchangeCalendarReadPort;
import com.zoutrankil.data.derived.port.MarketBreadthDailyV1Session;
import com.zoutrankil.data.derived.domain.MarketBreadthDailyV1FullSourceScope;
import com.zoutrankil.data.derived.domain.MarketBreadthDailyV1Policy;

import com.zoutrankil.data.calendar.storage.ExchangeCalendarReadRepository;

import com.zoutrankil.data.domain.MarketBreadthDailyV1Snapshot;

import com.zoutrankil.data.config.QuestDbProperties;
import com.zoutrankil.data.domain.MarketBreadthDailyV1;
import java.nio.charset.StandardCharsets;
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
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import org.springframework.jdbc.core.JdbcTemplate;

/** One explicitly enabled native refresh, followed by bounded real QuestDB readback. */
public final class MarketBreadthDailyV1MaterializationPort
        implements MarketBreadthDailyV1Session {
    public static final String SOURCE = MarketBreadthDailyV1Policy.SOURCE;
    public static final String OUTPUT = MarketBreadthDailyV1Policy.OUTPUT;
    public static final int MAX_WINDOW_DAYS = MarketBreadthDailyV1Policy.MAX_WINDOW_DAYS;
    public static final long MAX_SOURCE_ROWS = MarketBreadthDailyV1Policy.MAX_SOURCE_ROWS;
    private static final String COLUMNS = "trade_date,stock_count,up_count,down_count,flat_count,avg_pct_change,total_amount_yi";
    private static final String AGGREGATES = "trade_date, count() AS stock_count, "
            + "sum(CASE WHEN pct_change > 0 THEN 1 ELSE 0 END) AS up_count, "
            + "sum(CASE WHEN pct_change < 0 THEN 1 ELSE 0 END) AS down_count, "
            + "sum(CASE WHEN pct_change = 0 THEN 1 ELSE 0 END) AS flat_count, "
            + "avg(pct_change) AS avg_pct_change, sum(amount) / 100000.0 AS total_amount_yi";
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
    private MarketBreadthDailyV1Snapshot frozen, submittedAt, readbackSnapshot, verifiedSnapshot;
    private boolean submitted, acknowledged;
    private boolean fullIsolated;
    private List<MarketBreadthDailyV1> submittedRows = List.of();
    private BooleanSupplier cancelled = () -> false;

    public MarketBreadthDailyV1MaterializationPort(JdbcTemplate jdbc, QuestDbProperties properties,
                                                  boolean mutationsEnabled) {
        this(jdbc, properties, mutationsEnabled, null);
    }

    /** A supplied physical target identity is explicit deployment admission; the default three-argument port is isolated only. */
    public MarketBreadthDailyV1MaterializationPort(JdbcTemplate jdbc, QuestDbProperties properties,
                                                  boolean mutationsEnabled, String expectedTargetId) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.properties = Objects.requireNonNull(properties);
        this.mutationsEnabled = mutationsEnabled;
        if (expectedTargetId != null && !expectedTargetId.matches("questdb-[0-9a-f]{64}"))
            throw new IllegalArgumentException("D095 exact expected physical target identity required");
        this.expectedTargetId = expectedTargetId;
        this.privateInstanceAttestor = PrivateQuestDbAttestations.marketBreadthV1();
    }

    public MarketBreadthDailyV1Snapshot snapshot() {
        requireSourceSchema();
        requireOutputSchema();
        Metadata first = metadata();
        Metadata second = metadata();
        if (!first.equals(second)) throw new IllegalStateException("D095 metadata changed during snapshot");
        return snapshot(first);
    }

    private MarketBreadthDailyV1Snapshot snapshot(Metadata state) {
        var source = state.source;
        var output = state.output;
        var sourceWal = state.sourceWal;
        var outputWal = state.outputWal;
        var refresh = state.refresh;
        if (!Set.of("MONTH", "YEAR").contains(source.partition.toUpperCase(Locale.ROOT)) || !source.wal || !source.dedup
                || !"MONTH".equalsIgnoreCase(output.partition) || !output.wal || output.dedup)
            throw new IllegalStateException("D095 source or MV physical contract differs");
        if (!SOURCE.equals(refresh.source) || !"timer".equalsIgnoreCase(refresh.type)
                || refresh.interval != 1 || !"MINUTE".equalsIgnoreCase(refresh.unit)
                || !normalized(DEFINITION_SQL).equals(normalized(refresh.sql)))
            throw new IllegalStateException("D095 MV definition or timer policy differs from authoritative SQL");
        boolean sourceSettled = !source.suspended && !sourceWal.suspended && source.pendingRows == 0
                && sourceWal.buffered == 0 && sourceWal.writer == sourceWal.sequencer;
        boolean outputSettled = !output.suspended && !outputWal.suspended && output.pendingRows == 0
                && outputWal.buffered == 0 && outputWal.writer == outputWal.sequencer;
        boolean valid = "valid".equalsIgnoreCase(refresh.status) && blank(refresh.reason);
        boolean caughtUp = valid && sourceSettled && outputSettled
                && refresh.refreshed == sourceWal.sequencer && refresh.base == sourceWal.sequencer;
        return new MarketBreadthDailyV1Snapshot(source.id, source.directory, source.txn, sourceWal.sequencer, sourceWal.writer,
                sourceSettled, output.id, output.directory, output.txn, outputWal.sequencer,
                outputWal.writer, outputSettled, valid, caughtUp, sha(normalized(refresh.sql)),
                refresh.started, refresh.finished, refresh.refreshed, refresh.base, source.partition, refresh.status);
    }

    public String targetId() {
        MarketBreadthDailyV1Snapshot state = snapshot();
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
            throw new IllegalStateException("D095 exchange calendar physical schema, timestamp or complete key differs");
        Physical after = physical("exchange_calendar");
        Wal walAfter = wal("exchange_calendar");
        if (!before.equals(after) || !walBefore.equals(walAfter) || after.suspended || walAfter.suspended
                || after.pendingRows != 0 || walAfter.buffered != 0 || walAfter.writer != walAfter.sequencer)
            throw new IllegalStateException("D095 exchange calendar version changed or has unsettled/suspended WAL");
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
                    + " WHERE trade_date >= ? AND trade_date < ?");
            bounds(statement, start, end);
            return statement;
        }, rows -> {
            if (!rows.next()) throw new IllegalStateException("D095 source count is absent");
            long value = rows.getLong(1);
            if (rows.wasNull() || value < 0 || rows.next())
                throw new IllegalStateException("Invalid D095 source row count");
            return value;
        });
        if (count == null || count > MAX_SOURCE_ROWS)
            throw new IllegalStateException("D095 source exceeds the 200000-row finite refresh budget");
        Boolean invalidKeys = jdbc.query(connection -> {
            var statement = connection.prepareStatement("SELECT trade_date,ts_code,key_rows FROM ("
                    + "SELECT trade_date,ts_code,count() AS key_rows FROM " + SOURCE
                    + " WHERE trade_date >= ? AND trade_date < ? GROUP BY trade_date,ts_code) "
                    + "WHERE key_rows <> 1 OR ts_code IS NULL OR ts_code = '' "
                    + "OR cast(trade_date AS long) % 86400000000 <> 0 LIMIT 1");
            bounds(statement, start, end);
            statement.setMaxRows(1);
            return statement;
        }, (org.springframework.jdbc.core.ResultSetExtractor<Boolean>) rows -> rows.next());
        if (Boolean.TRUE.equals(invalidKeys))
            throw new IllegalStateException("D095 source has null/duplicate complete keys or non-calendar timestamps");
        return count;
    }

    public List<MarketBreadthDailyV1> expected(LocalDate start, LocalDate end) {
        window(start, end);
        sourceRawRows(start, end);
        return rows("SELECT " + AGGREGATES + " FROM " + SOURCE
                + " WHERE trade_date >= ? AND trade_date < ? SAMPLE BY 1d ALIGN TO CALENDAR"
                + " ORDER BY trade_date LIMIT 32", start, end);
    }

    /** Reads the entire bounded output interval, including unexpected rows and duplicates. */
    public List<MarketBreadthDailyV1> actual(LocalDate start, LocalDate end) {
        window(start, end);
        return rows("SELECT " + COLUMNS + " FROM " + OUTPUT
                + " WHERE trade_date >= ? AND trade_date < ? ORDER BY trade_date LIMIT 32", start, end);
    }

    public long outputRowCount() {
        Long count = jdbc.query("SELECT count() FROM " + OUTPUT, rs -> {
            if (!rs.next()) throw new IllegalStateException("D095 full output row count is absent");
            long value = rs.getLong(1);
            if (rs.wasNull() || value < 0 || rs.next()) throw new IllegalStateException("D095 output row count differs");
            return value;
        });
        if (count == null) throw new IllegalStateException("D095 output row count is absent");
        return count;
    }

    public void bind(LocalDate start, LocalDate end, MarketBreadthDailyV1Snapshot expected) {
        window(start, end);
        Objects.requireNonNull(expected);
        if (submitted || frozen != null && (!from.equals(start) || !to.equals(end) || !frozen.equals(expected)))
            throw new IllegalStateException("D095 port cannot change or reuse its bound refresh request");
        from = start;
        to = end;
        frozen = expected;
    }

    public void cancellationProbe(BooleanSupplier probe) { cancelled = Objects.requireNonNull(probe); }

    /** DDL is an explicit isolated installation operation; reads never install or redefine objects. */
    public void createIsolatedTarget() {
        requireIsolatedMutation();
        requireSourceSchema();
        var found = jdbc.queryForList("SELECT view_name FROM materialized_views() WHERE view_name='" + OUTPUT + "'");
        if (found.size() > 1) throw new IllegalStateException("Duplicate D095 MV identity");
        if (found.isEmpty()) jdbc.execute("CREATE MATERIALIZED VIEW " + OUTPUT
                + " REFRESH EVERY 1m AS (" + DEFINITION_SQL + ") PARTITION BY MONTH");
        else snapshot();
    }

    /** Selects a repair operation before binding; submission still goes through the shared durable runner. */
    public void configureFullIsolated() {
        requireIsolatedMutation();
        if (submitted || frozen != null || fullIsolated)
            throw new IllegalStateException("D095 FULL repair must be selected on a fresh isolated port");
        fullIsolated = true;
    }



    /** FULL is bounded by the complete physical source, never by a convenient subset. Empty sources are rejected. */
    public MarketBreadthDailyV1FullSourceScope fullSourceScope() {
        requireIsolatedMutation();
        MarketBreadthDailyV1Snapshot before = snapshot();
        MarketBreadthDailyV1FullSourceScope scope = jdbc.query("SELECT count() AS n,min(trade_date) AS lo,max(trade_date) AS hi FROM " + SOURCE, rs -> {
            if (!rs.next()) throw new IllegalStateException("D095 full-source bounds are absent");
            long count = requiredLong(rs, "n");
            if (count < 1 || count > MAX_SOURCE_ROWS)
                throw new IllegalStateException("D095 FULL requires a nonempty complete source of at most 200000 rows");
            Timestamp first = rs.getTimestamp("lo", utc()), last = rs.getTimestamp("hi", utc());
            if (first == null || last == null || rs.next())
                throw new IllegalStateException("D095 FULL requires exact complete source date bounds");
            var start = first.toInstant().atOffset(ZoneOffset.UTC).toLocalDate();
            var end = last.toInstant().atOffset(ZoneOffset.UTC).toLocalDate();
            window(start, end);
            return new MarketBreadthDailyV1FullSourceScope(start, end, count);
        });
        if (scope.rawRows() != sourceRawRows(scope.from(), scope.to()) || !before.sourceUnchanged(snapshot()))
            throw new IllegalStateException("D095 complete source changed while bounding isolated FULL repair");
        return scope;
    }

    @Override public void preflight() {
        if (frozen == null) throw new IllegalStateException("D095 refresh interval is not frozen");
        if (fullIsolated) requireIsolatedMutation();
        else requireAdmittedMutation();
        MarketBreadthDailyV1Snapshot current = snapshot();
        requireFrozenSource(current);
        if ((!fullIsolated && !current.valid()) || "refreshing".equalsIgnoreCase(current.viewStatus())
                || !current.sourceSettled() || !current.mvSettled())
            throw new IllegalStateException("D095 MV is invalid, refreshing or WAL is unsettled");
        if (fullIsolated) {
            MarketBreadthDailyV1FullSourceScope scope = fullSourceScope();
            if (!scope.from().equals(from) || !scope.to().equals(to))
                throw new IllegalStateException("D095 FULL frozen window must cover the entire complete physical source");
            requireFrozenSource(snapshot());
        } else requireFinitePendingRefresh(current);
    }

    /** The native WAL refresh may visit the whole source; lagging sources therefore have a global work bound. */
    private void requireFinitePendingRefresh(MarketBreadthDailyV1Snapshot current) {
        if (current.caughtUp()) return;
        var scope = jdbc.queryForMap("SELECT count() AS n,min(trade_date) AS lo,max(trade_date) AS hi FROM " + SOURCE);
        long count = ((Number) scope.get("n")).longValue();
        if (count < 0 || count > MAX_SOURCE_ROWS) throw new IllegalStateException("D095 pending native refresh exceeds global source budget");
        if (count > 0) {
            var lo = ((java.sql.Timestamp) scope.get("lo")).toInstant().atZone(ZoneOffset.UTC).toLocalDate();
            var hi = ((java.sql.Timestamp) scope.get("hi")).toInstant().atZone(ZoneOffset.UTC).toLocalDate();
            if (ChronoUnit.DAYS.between(lo, hi) >= MAX_WINDOW_DAYS)
                throw new IllegalStateException("D095 pending native refresh exceeds the global 31-day span budget");
        }
    }

    @Override public void send(List<MarketBreadthDailyV1> rows) {
        if (submitted || rows == null || rows.isEmpty() || rows.size() > MAX_WINDOW_DAYS)
            throw new IllegalStateException("D095 requires exactly one nonempty finite native refresh submission");
        preflight();
        var expected = expected(from, to);
        if (expected.size() != rows.size()) throw new IllegalStateException("D095 source row count changed before submission");
        for (int index = 0; index < rows.size(); index++)
            if (!equivalent(expected.get(index), rows.get(index)))
                throw new IllegalStateException("D095 source values changed before submission");
        submittedAt = snapshot();
        requireFrozenSource(submittedAt);
        submittedRows = List.copyOf(rows);
        submitted = true;
        // QuestDB 10.0.1 RANGE persists a start but not its finish. INCREMENTAL preserves valid-state evidence.
        // Do not work around this by treating a refreshing view as valid or by issuing a formal FULL.
        jdbc.execute("REFRESH MATERIALIZED VIEW " + OUTPUT + (fullIsolated ? " FULL" : " INCREMENTAL"));
        acknowledged = true;
    }

    @Override public List<MarketBreadthDailyV1> readback(List<LocalDate> keys) {
        if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) {
            // The shared batch executor retains the uncertain slice when its polling sleep is interrupted.
            Thread.currentThread().interrupt();
            throw new CancellationException("D095 refresh cancelled after submission; reconcile before replay");
        }
        if (frozen == null || keys == null || keys.isEmpty() || keys.size() > MAX_WINDOW_DAYS
                || keys.stream().anyMatch(Objects::isNull) || new HashSet<>(keys).size() != keys.size())
            throw new IllegalArgumentException("D095 bounded complete date keys required");
        if (submitted && !submittedRows.stream().map(MarketBreadthDailyV1::tradeDate).toList().equals(keys))
            throw new IllegalArgumentException("D095 refresh readback must cover the complete submitted window");
        MarketBreadthDailyV1Snapshot before = snapshot();
        requireFrozenSource(before);
        requireReadable(before);
        var actual = actual(from, to);
        MarketBreadthDailyV1Snapshot after = snapshot();
        if (!before.equals(after)) throw new IllegalStateException("D095 output changed during real readback");
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
        MarketBreadthDailyV1Snapshot current = snapshot();
        requireFrozenSource(current);
        if (!current.equals(readbackSnapshot) || !current.valid() || !current.caughtUp()) return false;
        if (submitted && (!acknowledged || !refreshCompleted(current))) return false;
        verifiedSnapshot = current;
        return true;
    }

    private boolean refreshCompleted(MarketBreadthDailyV1Snapshot current) {
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
    public MarketBreadthDailyV1Snapshot verifiedSnapshot() { return verifiedSnapshot; }
    public Duration visibilityTimeout() { return Duration.ofMinutes(3); }

    private void requireFrozenSource(MarketBreadthDailyV1Snapshot state) {
        if (!frozen.sourceUnchanged(state) || frozen.mvId() != state.mvId()
                || !Objects.equals(frozen.mvDirectory(), state.mvDirectory())
                || !Objects.equals(frozen.definitionSha(), state.definitionSha()))
            throw new IllegalStateException("D095 frozen source version, physical identity or definition changed");
    }

    private static void requireReadable(MarketBreadthDailyV1Snapshot state) {
        if (!state.valid() || !state.caughtUp() || !state.sourceSettled() || !state.mvSettled())
            throw new IllegalStateException("D095 MV is invalid, lagging or has unsettled/suspended WAL");
    }

    public void verifyPrivateInstance() { requireIsolatedMutation(); }

    private void requireIsolatedMutation() {
        if (!mutationsEnabled || !Set.of("127.0.0.1", "localhost", "::1").contains(properties.getHost())
                || properties.getPgPort() != 18812 || properties.getQwpPort() != 19000)
            throw new IllegalStateException("D095 mutations require the explicitly enabled local private instance on 18812/19000");
        privateInstanceAttestor.attest();
    }

    private void requireAdmittedMutation() {
        if (!mutationsEnabled) throw new IllegalStateException("D095 materialization mutations are disabled");
        if (expectedTargetId == null) requireIsolatedMutation();
        else if (!expectedTargetId.equals(targetId()))
            throw new IllegalStateException("D095 admitted physical target identity differs from the actual instance");
    }

    private Metadata metadata() {
        return new Metadata(physical(SOURCE), wal(SOURCE), physical(OUTPUT), wal(OUTPUT), refresh());
    }

    private Physical physical(String name) {
        return jdbc.query("SELECT id,directoryName,table_txn,table_suspended,wal_pending_row_count,"
                + "partitionBy,walEnabled,dedup FROM tables() WHERE table_name='" + name + "'", rs -> {
            if (!rs.next()) throw new IllegalStateException("Missing D095 physical table: " + name);
            var result = new Physical(requiredLong(rs, "id"), requiredText(rs, "directoryName"),
                    requiredLong(rs, "table_txn"), rs.getBoolean("table_suspended"),
                    requiredLong(rs, "wal_pending_row_count"), requiredText(rs, "partitionBy"),
                    rs.getBoolean("walEnabled"), rs.getBoolean("dedup"));
            if (rs.next()) throw new IllegalStateException("Duplicate D095 physical table: " + name);
            return result;
        });
    }

    private Wal wal(String name) {
        return jdbc.query("SELECT sequencerTxn,writerTxn,bufferedTxnSize,suspended FROM wal_tables() WHERE name='"
                + name + "'", rs -> {
            if (!rs.next()) throw new IllegalStateException("Missing D095 WAL table: " + name);
            var result = new Wal(requiredLong(rs, "sequencerTxn"), requiredLong(rs, "writerTxn"),
                    requiredLong(rs, "bufferedTxnSize"), rs.getBoolean("suspended"));
            if (rs.next()) throw new IllegalStateException("Duplicate D095 WAL table: " + name);
            return result;
        });
    }

    private Refresh refresh() {
        return jdbc.query("SELECT view_status,invalidation_reason,base_table_name,view_sql,refresh_type,"
                + "timer_interval,timer_interval_unit,refresh_base_table_txn,base_table_txn,"
                + "last_refresh_start_timestamp,last_refresh_finish_timestamp FROM materialized_views() WHERE view_name='"
                + OUTPUT + "'", rs -> {
            if (!rs.next()) throw new IllegalStateException("Missing D095 native MV metadata");
            var result = new Refresh(requiredText(rs, "view_status"), rs.getString("invalidation_reason"),
                    requiredText(rs, "base_table_name"), requiredText(rs, "view_sql"), requiredText(rs, "refresh_type"),
                    requiredLong(rs, "timer_interval"), requiredText(rs, "timer_interval_unit"),
                    optionalTxn(rs, "refresh_base_table_txn"), optionalTxn(rs, "base_table_txn"),
                    rs.getString("last_refresh_start_timestamp"), rs.getString("last_refresh_finish_timestamp"));
            if (rs.next()) throw new IllegalStateException("Duplicate D095 native MV metadata");
            return result;
        });
    }

    private void requireSourceSchema() {
        var schema = schema(SOURCE);
        for (var expected : Map.of("trade_date", "TIMESTAMP", "ts_code", "SYMBOL",
                "pct_change", "DOUBLE", "amount", "DOUBLE").entrySet())
            if (!expected.getValue().equals(schema.get(expected.getKey())))
                throw new IllegalStateException("D095 base source column/type differs: " + expected.getKey());
        var keys = jdbc.queryForList("SELECT \"column\",designated,upsertKey FROM table_columns('" + SOURCE + "')");
        var upsertKeys = new HashSet<String>();
        var designated = new HashSet<String>();
        for (var column : keys) {
            if (Boolean.TRUE.equals(column.get("upsertKey"))) upsertKeys.add(column.get("column").toString());
            if (Boolean.TRUE.equals(column.get("designated"))) designated.add(column.get("column").toString());
        }
        if (!upsertKeys.equals(Set.of("trade_date", "ts_code")) || !designated.equals(Set.of("trade_date")))
            throw new IllegalStateException("D095 source complete UPSERT key or designated timestamp differs");
    }

    private void requireOutputSchema() {
        var expected = new LinkedHashMap<String, String>();
        expected.put("trade_date", "TIMESTAMP");
        for (String name : List.of("stock_count", "up_count", "down_count", "flat_count")) expected.put(name, "LONG");
        expected.put("avg_pct_change", "DOUBLE");
        expected.put("total_amount_yi", "DOUBLE");
        if (!expected.equals(schema(OUTPUT))) throw new IllegalStateException("D095 MV seven-column schema differs");
        var metadata = jdbc.queryForList("SELECT \"column\",designated,upsertKey FROM table_columns('" + OUTPUT + "')");
        var designated = new HashSet<String>();
        for (var column : metadata) {
            if (Boolean.TRUE.equals(column.get("upsertKey"))) throw new IllegalStateException("Native D095 MV must not declare UPSERT keys");
            if (Boolean.TRUE.equals(column.get("designated"))) designated.add(column.get("column").toString());
        }
        if (!designated.equals(Set.of("trade_date"))) throw new IllegalStateException("D095 MV timestamp differs");
    }

    private Map<String, String> schema(String name) {
        var result = new LinkedHashMap<String, String>();
        jdbc.query("SELECT \"column\",\"type\" FROM table_columns('" + name + "')", rs -> {
            String column = rs.getString(1);
            if (result.putIfAbsent(column, rs.getString(2).toUpperCase(Locale.ROOT)) != null)
                throw new IllegalStateException("Duplicate D095 schema column");
        });
        return result;
    }

    private List<MarketBreadthDailyV1> rows(String sql, LocalDate start, LocalDate end) {
        List<MarketBreadthDailyV1> values = jdbc.query(connection -> {
            var statement = connection.prepareStatement(sql);
            bounds(statement, start, end);
            statement.setMaxRows(32);
            return statement;
        }, (rs, row) -> {
            Timestamp stamp = rs.getTimestamp("trade_date", utc());
            if (stamp == null) throw new IllegalStateException("Null D095 day key");
            var calendar = stamp.toInstant().atOffset(ZoneOffset.UTC);
            if (!calendar.toLocalTime().equals(java.time.LocalTime.MIDNIGHT))
                throw new IllegalStateException("D095 day bucket is not an exact calendar midnight");
            return new MarketBreadthDailyV1(calendar.toLocalDate(), requiredLong(rs, "stock_count"),
                    requiredLong(rs, "up_count"), requiredLong(rs, "down_count"), requiredLong(rs, "flat_count"),
                    nullableDouble(rs, "avg_pct_change"), nullableDouble(rs, "total_amount_yi"));
        });
        if (values.size() > ChronoUnit.DAYS.between(start, end) + 1)
            throw new IllegalStateException("D095 daily aggregation exceeds its bounded date count");
        var keys = new HashSet<LocalDate>();
        for (var value : values)
            if (value.tradeDate().isBefore(start) || value.tradeDate().isAfter(end) || !keys.add(value.tradeDate()))
                throw new IllegalStateException("D095 output has duplicate or out-of-window dates");
        return List.copyOf(values);
    }

    private static void bounds(PreparedStatement statement, LocalDate start, LocalDate end) throws SQLException {
        statement.setQueryTimeout(20);
        statement.setTimestamp(1, Timestamp.from(start.atStartOfDay().toInstant(ZoneOffset.UTC)), utc());
        statement.setTimestamp(2, Timestamp.from(end.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC)), utc());
    }
    private static Calendar utc() { return Calendar.getInstance(TimeZone.getTimeZone("UTC")); }
    private static long requiredLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        if (rs.wasNull()) throw new IllegalStateException("Null D095 required number: " + column);
        return value;
    }
    private static String requiredText(ResultSet rs, String column) throws SQLException {
        String value = rs.getString(column);
        if (blank(value)) throw new IllegalStateException("Null D095 required metadata: " + column);
        return value;
    }
    private static Double nullableDouble(ResultSet rs, String column) throws SQLException {
        double value = rs.getDouble(column);
        if (rs.wasNull()) return null;
        if (!Double.isFinite(value)) throw new IllegalStateException("Non-finite D095 aggregate: " + column);
        return value;
    }
    private static long optionalTxn(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? -1 : value;
    }
    public static boolean equivalent(MarketBreadthDailyV1 left, MarketBreadthDailyV1 right) {
        return MarketBreadthDailyV1Policy.equivalent(left, right);
    }

    private static void window(LocalDate start, LocalDate end) { MarketBreadthDailyV1Policy.window(start, end); }
    private static boolean blank(String value) { return value == null || value.isBlank(); }
    private static String normalized(String value) { return value == null ? "" : value.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT); }
    private static String sha(String text) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception impossible) { throw new IllegalStateException(impossible); }
    }
}
