package com.zoutrankil.data.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.derived.storage.DerivedMonthlyReadGuards;
import com.zoutrankil.data.derived.storage.MarketBreadthDailyV1MaterializationPort;
import com.zoutrankil.data.derived.storage.RetailSentimentDailyV1MaterializationPort;
import com.zoutrankil.data.domain.temporal.TemporalValues;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.stereotype.Repository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.function.Function;
import static com.zoutrankil.data.domain.DatasetDefinition.*;

/** One bounded SELECT per page plus schema preflight; never relies on JDBC full-table buffering. */
@Repository
public class QuestDbBoundedReader {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<StorageType> UNORDERED_STORAGE_TYPES = Set.of(
            StorageType.BINARY, StorageType.LONG256, StorageType.UUID, StorageType.BOOLEAN, StorageType.IPV4);

    public record Bound(Column column, Object storageValue) {}
    public record PreparedRead(String sql, List<Bound> parameters, String fingerprint) {
        public PreparedRead { parameters = List.copyOf(parameters); }
    }
    // Add aliases here only after their individual migration has established the exact native MV contract.
    private static final Map<String, String> GUARDED_MATERIALIZED_ALIASES = Map.of(
            "v_market_breadth_daily", "mv_market_breadth_daily_v1",
            "v_retail_sentiment_daily", "mv_retail_sentiment_daily_v1");
    // D102 is an ordinary aggregate of two physical source tables, not an MV alias or cache generation.
    private static final String GUARDED_ETF_AGGREGATE_VIEW = "v_etf_market_overview_daily";
    private static final List<String> GUARDED_ETF_AGGREGATE_SOURCES = List.of("etf_share", "etf_daily");
    private static final Set<String> ETF_AGGREGATE_COLUMNS = Set.of("trade_date", "etf_count", "total_share", "total_size_yi");
    private static final String ETF_AGGREGATE_SQL = """
            SELECT
                s.timestamp AS trade_date,
                count_distinct(s.ts_code) AS etf_count,
                sum(s.fd_share) AS total_share,
                sum(s.fd_share * d.close) / 10000.0 AS total_size_yi
            FROM etf_share s
            JOIN etf_daily d ON s.ts_code = d.ts_code AND s.timestamp = d.timestamp
            SAMPLE BY 1d ALIGN TO CALENDAR
            """;
    private static final Set<String> ETF_SQL_IDENTIFIERS = Set.of("s", "d", "timestamp", "ts_code", "fd_share",
            "close", "trade_date", "etf_count", "total_share", "total_size_yi", "etf_share", "etf_daily");
    private static final java.util.regex.Pattern ETF_SQL_TOKEN = java.util.regex.Pattern.compile(
            "\"([A-Za-z_][A-Za-z0-9_]*)\"|([A-Za-z_][A-Za-z0-9_]*)|([0-9]+(?:\\.[0-9]+)?[A-Za-z]*)|([.,()*/=])");
    // Python's DEDUP publisher can revise existing cache keys without changing their business source_version.
    private static final Set<String> GUARDED_CACHE_TABLES = Set.of(
            "market_breadth_daily_cache", "retail_sentiment_daily_cache", "etf_market_overview_daily_cache");
    // Admitted Python cache products identify a generation by sha256(...).hexdigest().
    private static final java.util.regex.Pattern CACHE_GENERATION = java.util.regex.Pattern.compile("[0-9a-f]{64}");
    private final JdbcTemplate jdbc;
    private final Path ledgerPath;
    public QuestDbBoundedReader(JdbcTemplate jdbc) { this(jdbc,System.getProperty("app.sync.ledger-path","var/sync-ledger.sqlite3")); }
    @Autowired
    public QuestDbBoundedReader(JdbcTemplate jdbc,@Value("${app.sync.ledger-path:var/sync-ledger.sqlite3}") String ledger) { this.jdbc=jdbc;this.ledgerPath=Path.of(ledger).toAbsolutePath().normalize(); }

    public <T> DatasetReadPage<T> read(DatasetDefinition definition, DatasetReadQuery query, String sourceVersion,
                                      Function<DatasetValues, T> mapper) {
        validateEtfAggregateQuery(definition, query);
        DerivedMonthlyReadGuards.validateEquityStyle(definition, query);
        DerivedMonthlyReadGuards.validateMacroCore(definition, query);
        DerivedMonthlyReadGuards.validateMacroCoreView(definition, query);
        validateCacheGenerationFilters(definition, query);
        String guardedBefore = guardedSourceVersion(definition);
        String effectiveSourceVersion = guardedBefore == null ? sourceVersion : guardedBefore;
        var prepared = prepare(definition, query, effectiveSourceVersion);
        var columns = columns(definition);
        verifySchema(definition, query.columns(), columns);
        List<DatasetValues> values = jdbc.query(connection -> {
            var statement = connection.prepareStatement(prepared.sql());
            statement.setQueryTimeout(20);
            statement.setMaxRows(query.pageSize() + 1);
            statement.setFetchSize(query.pageSize() + 1);
            for (int i = 0; i < prepared.parameters().size(); i++) bind(statement, i + 1, prepared.parameters().get(i));
            return statement;
        }, (rs, index) -> {
            var row = new LinkedHashMap<String, Object>();
            for (var name : query.columns()) row.put(name, readValue(rs, columns.get(name)));
            return new DatasetValues(row);
        });
        var seen = new HashSet<List<Object>>();
        for (var row : values) {
            var key = definition.businessKey().stream().map(name -> row.get(name, Object.class)).toList();
            if (key.stream().anyMatch(Objects::isNull) || !seen.add(key)) {
                throw new IllegalStateException("Null/duplicate complete business key; stable pagination cannot be proven");
            }
        }
        boolean more = values.size() > query.pageSize();
        var selected = more ? values.subList(0, query.pageSize()) : values;
        DatasetReadCursor cursor = null;
        if (more) {
            var last = selected.getLast();
            cursor = new DatasetReadCursor(prepared.fingerprint(), definition.businessKey().stream()
                    .map(name -> last.get(name, Object.class)).toList(), effectiveSourceVersion);
        }
        if (guardedBefore != null && !guardedBefore.equals(guardedSourceVersion(definition))) {
            throw new IllegalStateException("Guarded source changed during bounded read");
        }
        return new DatasetReadPage<>(definition.datasetId(), definition.schemaVersion(), effectiveSourceVersion, Instant.now(),
                selected.stream().map(mapper).toList(), cursor);
    }

    /** Date bounds limit output buckets; LIMIT is not evidence that the native JOIN scans only that many source rows. */
    private static void validateEtfAggregateQuery(DatasetDefinition definition, DatasetReadQuery query) {
        if (!GUARDED_ETF_AGGREGATE_VIEW.equals(definition.objectName())) return;
        if (definition.objectKind() != ObjectKind.VIEW || !definition.dependencies().equals(GUARDED_ETF_AGGREGATE_SOURCES))
            throw new IllegalStateException("ETF aggregate requires its registered ordinary VIEW and exact two-source contract");
        if (query.columns().size() != ETF_AGGREGATE_COLUMNS.size()
                || !new HashSet<>(query.columns()).equals(ETF_AGGREGATE_COLUMNS) || query.pageSize() > 31)
            throw new IllegalArgumentException("ETF aggregate requires all four fields and a page of at most 31 daily buckets");
        boolean exactDate = query.equalities().containsKey("trade_date");
        if (exactDate && !(query.equalities().get("trade_date") instanceof LocalDate))
            throw new IllegalArgumentException("ETF aggregate exact date requires a non-null LocalDate");
        if (query.rangeColumn() != null) {
            if (!"trade_date".equals(query.rangeColumn()) || !(query.fromInclusive() instanceof LocalDate from)
                    || !(query.toExclusive() instanceof LocalDate to) || !from.isBefore(to)
                    || java.time.temporal.ChronoUnit.DAYS.between(from, to) > 31)
                throw new IllegalArgumentException("ETF aggregate requires an increasing LocalDate range of at most 31 days");
        } else if (!exactDate) {
            throw new IllegalArgumentException("ETF aggregate requires an exact date or a bounded daily range");
        }
    }

    private static void validateCacheGenerationFilters(DatasetDefinition definition, DatasetReadQuery query) {
        if (definition.objectKind() != ObjectKind.TABLE || !GUARDED_CACHE_TABLES.contains(definition.objectName())) return;
        if (query.equalities().containsKey("source_version"))
            requireCacheGeneration(query.equalities().get("source_version"));
        if (query.cursor() != null) {
            if (query.cursor().keyValues().size() != 2)
                throw new IllegalArgumentException("Complete date/source-generation cursor key required");
            requireCacheGeneration(query.cursor().keyValues().get(1));
        }
    }

    private static void requireCacheGeneration(Object value) {
        if (!(value instanceof String version) || !CACHE_GENERATION.matcher(version).matches())
            throw new IllegalArgumentException("Lowercase SHA-256 source_version required");
    }

    private String guardedSourceVersion(DatasetDefinition definition) {
        if (BacktestDailyMaterializationReadGuard.applies(definition))
            return BacktestDailyMaterializationReadGuard.version(jdbc, definition, ledgerPath);
        if (DerivedMonthlyReadGuards.appliesEquityStyle(definition))
            return DerivedMonthlyReadGuards.versionEquityStyle(jdbc, definition);
        if (DerivedMonthlyReadGuards.appliesMacroCore(definition))
            return DerivedMonthlyReadGuards.versionMacroCore(jdbc, definition);
        if (DerivedMonthlyReadGuards.appliesMacroCoreView(definition))
            return DerivedMonthlyReadGuards.versionMacroCoreView(jdbc, definition);
        if (definition.objectKind() == ObjectKind.MATERIALIZED_VIEW)
            return requireCurrentMaterializedView(definition.objectName()).sourceVersion();
        if (definition.objectKind() == ObjectKind.VIEW && GUARDED_MATERIALIZED_ALIASES.containsKey(definition.objectName()))
            return requireCurrentAlias(definition);
        if (definition.objectKind() == ObjectKind.VIEW && GUARDED_ETF_AGGREGATE_VIEW.equals(definition.objectName()))
            return requireCurrentEtfAggregateView(definition);
        if (definition.objectKind() == ObjectKind.TABLE && GUARDED_CACHE_TABLES.contains(definition.objectName()))
            return requireCurrentCacheTable(definition.objectName());
        return null;
    }

    /** The registered direct aggregate binds its exact view definition and both original source versions. */
    private String requireCurrentEtfAggregateView(DatasetDefinition definition) {
        if (!definition.dependencies().equals(GUARDED_ETF_AGGREGATE_SOURCES))
            throw new IllegalStateException("ETF aggregate differs from its registered two-source dependency contract");
        AliasState before = etfAggregateViewState();
        String share = requireEtfAggregateSource("etf_share", EtfShareDataset.DEFINITION);
        String daily = requireEtfAggregateSource("etf_daily", EtfDailyDataset.DEFINITION);
        if (!before.equals(etfAggregateViewState()))
            throw new IllegalStateException("ETF aggregate definition changed while reading its source versions");
        return "view-dir:" + before.directory + ":view-sql:" + before.sqlHash
                + ":view-status-updated:" + before.statusUpdated + ":source:" + share + ":source:" + daily;
    }

    private AliasState etfAggregateViewState() {
        return queryEtfMetadata("SELECT view_sql,view_table_dir_name,view_status,invalidation_reason,"
                + "view_status_update_time FROM views() WHERE view_name='" + GUARDED_ETF_AGGREGATE_VIEW + "'", 2, rs -> {
            if (!rs.next()) throw new IllegalStateException("Guarded ETF aggregate view is absent");
            String sql = rs.getString("view_sql");
            String directory = rs.getString("view_table_dir_name");
            String reason = rs.getString("invalidation_reason");
            String updated = rs.getString("view_status_update_time");
            if (!"valid".equals(rs.getString("view_status")) || directory == null || directory.isBlank()
                    || updated == null || updated.isBlank() || reason != null && !reason.isBlank()
                    || !Objects.equals(etfSqlTokens(ETF_AGGREGATE_SQL), etfSqlTokens(sql)))
                throw new IllegalStateException("ETF aggregate view is invalid or differs from the fixed original SQL");
            var result = new AliasState(directory, sqlHash(sql), updated);
            if (rs.next()) throw new IllegalStateException("Duplicate guarded ETF aggregate view metadata");
            return result;
        });
    }

    /** Discard only lexical whitespace/case and quoting of the known source/alias identifiers. */
    private static List<String> etfSqlTokens(String sql) {
        if (sql == null) return null;
        var tokens = new ArrayList<String>();
        for (int position = 0; position < sql.length();) {
            if (Character.isWhitespace(sql.charAt(position))) { position++; continue; }
            var match = ETF_SQL_TOKEN.matcher(sql).region(position, sql.length());
            if (!match.lookingAt()) return null;
            String token = match.group(1) != null ? match.group(1) : match.group();
            token = token.toLowerCase(Locale.ROOT);
            if (match.group(1) != null && !ETF_SQL_IDENTIFIERS.contains(token)) return null;
            tokens.add(token);
            position = match.end();
        }
        return List.copyOf(tokens);
    }

    private String requireEtfAggregateSource(String table, DatasetDefinition expected) {
        String version = queryEtfMetadata("SELECT t.id AS table_id,t.directoryName AS table_directory,"
                + "t.table_txn AS table_physical_txn,t.table_suspended AS table_suspended,"
                + "t.wal_pending_row_count AS table_pending_rows,t.walEnabled AS table_wal_enabled,"
                + "t.partitionBy AS table_partition,t.designatedTimestamp AS table_timestamp,"
                + "t.dedup AS table_dedup,t.matView AS table_is_materialized,"
                + "w.sequencerTxn AS table_seq_txn,w.writerTxn AS table_writer_txn,"
                + "w.bufferedTxnSize AS table_buffered_txns,w.suspended AS table_wal_suspended "
                + "FROM tables() t JOIN wal_tables() w ON w.name=t.table_name WHERE t.table_name='" + table + "'", 2, rs -> {
            if (!rs.next()) throw new IllegalStateException("ETF aggregate source table or WAL metadata is absent");
            long id = requiredCounter(rs, "table_id");
            String directory = rs.getString("table_directory");
            long physicalTxn = requiredCounter(rs, "table_physical_txn");
            long seqTxn = requiredCounter(rs, "table_seq_txn");
            long writerTxn = requiredCounter(rs, "table_writer_txn");
            long pending = requiredCounter(rs, "table_pending_rows");
            long buffered = requiredCounter(rs, "table_buffered_txns");
            if (directory == null || directory.isBlank() || !requiredBoolean(rs, "table_wal_enabled")
                    || requiredBoolean(rs, "table_suspended") || requiredBoolean(rs, "table_wal_suspended")
                    || !requiredBoolean(rs, "table_dedup") || requiredBoolean(rs, "table_is_materialized")
                    || !"YEAR".equals(rs.getString("table_partition"))
                    || !"timestamp".equals(rs.getString("table_timestamp"))
                    || pending != 0 || buffered != 0 || writerTxn != seqTxn)
                throw new IllegalStateException("ETF aggregate source physical or settled WAL contract differs");
            if (rs.next()) throw new IllegalStateException("Duplicate ETF aggregate source metadata");
            // Physical commits and WAL sequence counters are independent version domains.
            return table + ":id:" + id + ":directory:" + directory + ":txn:" + physicalTxn + ":wal-seq:" + seqTxn;
        });
        requireEtfSourceSchema(table, expected);
        return version;
    }

    private void requireEtfSourceSchema(String table, DatasetDefinition expected) {
        queryEtfMetadata("SELECT \"column\",\"type\",designated,upsertKey FROM table_columns('" + table + "') LIMIT 18", 18, rs -> {
            var types = new LinkedHashMap<String, String>();
            var designated = new ArrayList<String>();
            var upsertKeys = new HashSet<String>();
            while (rs.next()) {
                String column = rs.getString("column");
                String type = rs.getString("type");
                if (column == null || types.putIfAbsent(column, type) != null || type == null)
                    throw new IllegalStateException("Incomplete or duplicate ETF aggregate source schema");
                if (requiredBoolean(rs, "designated")) designated.add(column);
                if (requiredBoolean(rs, "upsertKey")) upsertKeys.add(column);
            }
            var expectedTypes = new LinkedHashMap<String, String>();
            expected.columns().forEach(c -> expectedTypes.put(c.storageName(), c.storageType().name()));
            if (!types.equals(expectedTypes) || !List.copyOf(types.keySet()).equals(List.copyOf(expectedTypes.keySet()))
                    || !designated.equals(List.of("timestamp")) || !upsertKeys.equals(Set.of("ts_code", "timestamp")))
                throw new IllegalStateException("ETF aggregate source full schema, timestamp precision or complete key differs");
            return Boolean.TRUE;
        });
    }

    private <T> T queryEtfMetadata(String sql, int rowLimit, ResultSetExtractor<T> extractor) {
        return jdbc.query(connection -> {
            var statement = connection.prepareStatement(sql);
            statement.setQueryTimeout(20);
            statement.setMaxRows(rowLimit);
            statement.setFetchSize(rowLimit);
            return statement;
        }, extractor);
    }

    /** This is a physical cache snapshot, independent of the row's source_version and current upstream freshness. */
    private String requireCurrentCacheTable(String objectName) {
        return jdbc.query("SELECT t.id AS table_id,t.directoryName AS table_directory,t.table_txn AS table_physical_txn,"
                + "t.table_suspended AS table_suspended,t.wal_pending_row_count AS table_pending_rows,"
                + "t.walEnabled AS table_wal_enabled,w.sequencerTxn AS table_seq_txn,"
                + "w.writerTxn AS table_writer_txn,w.bufferedTxnSize AS table_buffered_txns,"
                + "w.suspended AS table_wal_suspended FROM tables() t JOIN wal_tables() w ON w.name=t.table_name "
                + "WHERE t.table_name='" + objectName + "'", rs -> {
            if (!rs.next()) throw new IllegalStateException("Guarded cache table or WAL metadata is absent");
            long id = requiredCounter(rs, "table_id");
            String directory = rs.getString("table_directory");
            long physicalTxn = requiredCounter(rs, "table_physical_txn");
            long sequenceTxn = requiredCounter(rs, "table_seq_txn");
            long writerTxn = requiredCounter(rs, "table_writer_txn");
            long pendingRows = requiredCounter(rs, "table_pending_rows");
            long bufferedTxns = requiredCounter(rs, "table_buffered_txns");
            if (directory == null || directory.isBlank() || !requiredBoolean(rs, "table_wal_enabled")
                    || requiredBoolean(rs, "table_suspended") || requiredBoolean(rs, "table_wal_suspended")
                    || pendingRows != 0 || bufferedTxns != 0 || writerTxn != sequenceTxn)
                throw new IllegalStateException("Guarded cache table WAL is unsettled, suspended or unavailable");
            if (rs.next()) throw new IllegalStateException("Duplicate guarded cache table metadata");
            // Table and WAL counters are independent; both are bound, never compared to one another.
            return "table:" + objectName + ":id:" + id + ":directory:" + directory
                    + ":txn:" + physicalTxn + ":wal-seq:" + sequenceTxn;
        });
    }

    private static boolean requiredBoolean(ResultSet rs, String column) throws SQLException {
        boolean value = rs.getBoolean(column);
        if (rs.wasNull()) throw new IllegalStateException("Source status metadata is unavailable: " + column);
        return value;
    }

    private record AliasState(String directory, String sqlHash, String statusUpdated) {}

    /** A registered alias requires its fixed native MV dependency; unrelated views keep their existing contract. */
    private String requireCurrentAlias(DatasetDefinition definition) {
        String dependency = GUARDED_MATERIALIZED_ALIASES.get(definition.objectName());
        if (!definition.dependencies().equals(List.of(dependency)))
            throw new IllegalStateException("Guarded alias differs from its registered materialized dependency");
        AliasState before = aliasState(definition.objectName(), dependency);
        var materialized = requireCurrentMaterializedView(dependency);
        if (!before.equals(aliasState(definition.objectName(), dependency)))
            throw new IllegalStateException("Alias definition changed while reading its materialized dependency");
        return "view-dir:" + before.directory + ":view-sql:" + before.sqlHash
                + ":view-status-updated:" + before.statusUpdated + ":dependency:" + dependency
                + ":" + materialized.sourceVersion();
    }

    private AliasState aliasState(String objectName, String dependency) {
        // Both names have already passed DatasetDefinition's identifier validation.
        return jdbc.query("SELECT view_sql,view_table_dir_name,view_status,invalidation_reason,"
                + "view_status_update_time FROM views() WHERE view_name='" + objectName + "'", rs -> {
            if (!rs.next()) throw new IllegalStateException("Guarded alias is absent");
            String sql = rs.getString("view_sql");
            String directory = rs.getString("view_table_dir_name");
            String reason = rs.getString("invalidation_reason");
            if (!"valid".equals(rs.getString("view_status")) || directory == null || directory.isBlank()
                    || reason != null && !reason.isBlank() || !isExactAliasSql(sql, dependency))
                throw new IllegalStateException("Alias is invalid or differs from its declared materialized dependency");
            var result = new AliasState(directory, sqlHash(sql), rs.getString("view_status_update_time"));
            if (rs.next()) throw new IllegalStateException("Duplicate guarded alias metadata");
            return result;
        });
    }

    private static boolean isExactAliasSql(String sql, String dependency) {
        if (sql == null) return false;
        // Each admitted alias is the exact SELECT * of its fixed native materialized view.
        // Only identifier quoting and whitespace/case variation of that exact statement are accepted.
        String identifier = java.util.regex.Pattern.quote(dependency);
        return java.util.regex.Pattern.compile("(?i)^\\s*SELECT\\s+\\*\\s+FROM\\s+(?:"
                + identifier + "|\"" + identifier + "\")\\s*$").matcher(sql).matches();
    }

    private static String normalizedSql(String sql) {
        return sql == null ? "" : sql.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    private static String sqlHash(String sql) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(sql.trim().getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private record MaterializedViewState(long baseTableId, long basePhysicalTxn, long baseSeqTxn,
                                         long refreshTxn, long viewTableId, long viewPhysicalTxn,
                                         long viewSeqTxn) {
        String sourceVersion() {
            // RANGE/FULL refresh can replace MV values without advancing the base checkpoint.
            // Table identity also prevents a drop/recreate from reusing an old cursor's counters.
            return "base-id:" + baseTableId + ":base-txn:" + basePhysicalTxn + ":base-seq-txn:" + baseSeqTxn
                    + ":refresh-txn:" + refreshTxn + ":mv-id:" + viewTableId + ":mv-txn:" + viewPhysicalTxn
                    + ":mv-seq-txn:" + viewSeqTxn;
        }
    }

    private MaterializedViewState requireCurrentMaterializedView(String objectName) {
        // DatasetDefinition validates objectName as an identifier, so this fixed metadata lookup is safe.
        // tables().table_txn is the data version; WAL application uses wal_tables() counters.
        // These counter domains can differ after FULL/TRUNCATE and must not be compared to one another.
        return jdbc.query("SELECT m.view_status,m.refresh_base_table_txn,m.base_table_txn,m.base_table_name,m.view_sql,"
                + "b.id AS base_table_id,b.table_txn AS base_physical_txn,"
                + "bw.writerTxn AS base_writer_txn,bw.sequencerTxn AS base_seq_txn,"
                + "bw.bufferedTxnSize AS base_buffered_txns,bw.suspended AS base_suspended,"
                + "v.id AS view_table_id,v.table_txn AS view_physical_txn,"
                + "vw.writerTxn AS view_writer_txn,vw.sequencerTxn AS view_seq_txn,"
                + "vw.bufferedTxnSize AS view_buffered_txns,vw.suspended AS view_suspended,"
                + "v.matView AS is_materialized_view "
                + "FROM materialized_views() m JOIN tables() b ON b.table_name=m.base_table_name "
                + "JOIN tables() v ON v.table_name=m.view_name "
                + "JOIN wal_tables() bw ON bw.name=m.base_table_name "
                + "JOIN wal_tables() vw ON vw.name=m.view_name "
                + "WHERE m.view_name='" + objectName + "'", rs -> {
            if (!rs.next()) throw new IllegalStateException("Materialized view is absent");
            if (MarketBreadthDailyV1MaterializationPort.OUTPUT.equals(objectName)
                    && (!MarketBreadthDailyV1MaterializationPort.SOURCE.equals(rs.getString("base_table_name"))
                        || !normalizedSql(MarketBreadthDailyV1MaterializationPort.DEFINITION_SQL)
                                .equals(normalizedSql(rs.getString("view_sql")))))
                throw new IllegalStateException("Breadth MV differs from its fixed base-table or aggregation definition");
            if (RetailSentimentDailyV1MaterializationPort.OUTPUT.equals(objectName)
                    && (!RetailSentimentDailyV1MaterializationPort.SOURCE.equals(rs.getString("base_table_name"))
                        || !normalizedSql(RetailSentimentDailyV1MaterializationPort.DEFINITION_SQL)
                                .equals(normalizedSql(rs.getString("view_sql")))))
                throw new IllegalStateException("Retail sentiment MV differs from its fixed base-table or aggregation definition");
            String status = rs.getString("view_status");
            long refreshTxn = requiredCounter(rs, "refresh_base_table_txn");
            long baseTxn = requiredCounter(rs, "base_table_txn");
            long baseTableId = requiredCounter(rs, "base_table_id");
            long basePhysicalTxn = requiredCounter(rs, "base_physical_txn");
            long baseWriterTxn = requiredCounter(rs, "base_writer_txn");
            long baseSeqTxn = requiredCounter(rs, "base_seq_txn");
            long baseBufferedTxns = requiredCounter(rs, "base_buffered_txns");
            long viewTableId = requiredCounter(rs, "view_table_id");
            long viewPhysicalTxn = requiredCounter(rs, "view_physical_txn");
            long viewWriterTxn = requiredCounter(rs, "view_writer_txn");
            long viewSeqTxn = requiredCounter(rs, "view_seq_txn");
            long viewBufferedTxns = requiredCounter(rs, "view_buffered_txns");
            if (!"valid".equals(status) || refreshTxn != baseTxn || baseTxn != baseSeqTxn
                    || baseWriterTxn != baseSeqTxn || baseBufferedTxns != 0
                    || viewWriterTxn != viewSeqTxn || viewBufferedTxns != 0
                    || rs.getBoolean("base_suspended") || rs.getBoolean("view_suspended")
                    || !rs.getBoolean("is_materialized_view")) {
                throw new IllegalStateException("Materialized view is invalid or behind its base table");
            }
            if (rs.next()) throw new IllegalStateException("Duplicate materialized view metadata");
            return new MaterializedViewState(baseTableId, basePhysicalTxn, baseSeqTxn, refreshTxn,
                    viewTableId, viewPhysicalTxn, viewSeqTxn);
        });
    }

    private static long requiredCounter(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        if (rs.wasNull() || value < 0) {
            throw new IllegalStateException("Source version metadata is unavailable: " + column);
        }
        return value;
    }

    public PreparedRead prepare(DatasetDefinition definition, DatasetReadQuery query, String sourceVersion) {
        definition.requireCapability(Capability.READ);
        validateEtfAggregateQuery(definition, query);
        DerivedMonthlyReadGuards.validateEquityStyle(definition, query);
        DerivedMonthlyReadGuards.validateMacroCore(definition, query);
        DerivedMonthlyReadGuards.validateMacroCoreView(definition, query);
        validateCacheGenerationFilters(definition, query);
        var columns = columns(definition);
        if (!columns.keySet().containsAll(query.columns()) || !columns.keySet().containsAll(query.equalities().keySet())
                || !query.columns().containsAll(definition.businessKey())) {
            throw new IllegalArgumentException("Unknown field or missing complete key in explicit projection");
        }
        for (var key : definition.businessKey()) requireOrdered(columns.get(key));
        String fingerprint = fingerprint(definition, query);
        if (query.cursor() != null && (!fingerprint.equals(query.cursor().queryFingerprint())
                || !Objects.equals(sourceVersion, query.cursor().sourceVersion())
                || query.cursor().keyValues().size() != definition.businessKey().size())) {
            throw new IllegalArgumentException("Cursor belongs to a different query/schema/source version");
        }
        var sql = new StringBuilder("SELECT ");
        sql.append(String.join(", ", query.columns().stream().map(name -> {
            var column = columns.get(name);
            String expression = quoted(column.storageName());
            if (isTemporalStorage(column)) expression = "cast(" + expression + " as long)";
            return expression + " AS " + quoted(name);
        }).toList()));
        sql.append(" FROM ").append(quoted(definition.objectName()));
        var conditions = new ArrayList<String>();
        var parameters = new ArrayList<Bound>();
        for (var entry : new TreeMap<>(query.equalities()).entrySet()) {
            var column = columns.get(entry.getKey());
            if (entry.getValue() == null) {
                if (!column.nullable()) throw new IllegalArgumentException("Null filter on required field");
                var nullCases = new ArrayList<String>();
                nullCases.add(quoted(column.storageName()) + " IS NULL");
                for (var sentinel : new TreeSet<>(column.legacyNullSentinels())) {
                    nullCases.add(quoted(column.storageName()) + " = ?");
                    parameters.add(new Bound(column, sentinel));
                }
                conditions.add("(" + String.join(" OR ", nullCases) + ")");
            } else conditions.add(comparison(column, "=", entry.getValue(), parameters));
        }
        if (query.rangeColumn() != null) {
            var column = columns.get(query.rangeColumn());
            if (column == null) throw new IllegalArgumentException("Unknown range column");
            requireOrdered(column);
            Object from = storageValue(column, query.fromInclusive()), to = storageValue(column, query.toExclusive());
            if (compare(from, to) >= 0) throw new IllegalArgumentException("Range must increase");
            conditions.add(comparison(column, ">=", query.fromInclusive(), parameters));
            conditions.add(comparison(column, "<", query.toExclusive(), parameters));
        }
        if (query.cursor() != null) {
            var alternatives = new ArrayList<String>();
            for (int i = 0; i < definition.businessKey().size(); i++) {
                var prefix = new ArrayList<String>();
                for (int j = 0; j <= i; j++) prefix.add(comparison(columns.get(definition.businessKey().get(j)),
                        j == i ? ">" : "=", query.cursor().keyValues().get(j), parameters));
                alternatives.add("(" + String.join(" AND ", prefix) + ")");
            }
            conditions.add("(" + String.join(" OR ", alternatives) + ")");
        }
        if (!conditions.isEmpty()) sql.append(" WHERE ").append(String.join(" AND ", conditions));
        sql.append(" ORDER BY ").append(String.join(", ", definition.businessKey().stream()
                .map(name -> quoted(columns.get(name).storageName()) + " ASC").toList()));
        sql.append(" LIMIT ").append(query.pageSize() + 1); // Validated bounded integer, not user SQL.
        return new PreparedRead(sql.toString(), parameters, fingerprint);
    }

    private void verifySchema(DatasetDefinition definition, List<String> projection, Map<String, Column> columns) {
        var required = new HashSet<>(projection);
        // Validate every declared column so filter-only fields cannot silently change their physical type.
        required.addAll(columns.keySet());
        Map<String, String> actual = jdbc.query(connection -> {
            var statement = connection.prepareStatement("SELECT \"column\", \"type\" FROM table_columns('"
                    + definition.objectName() + "') LIMIT 4097");
            statement.setQueryTimeout(20);
            statement.setMaxRows(4097);
            return statement;
        }, rs -> {
            var map = new HashMap<String, String>();
            while (rs.next()) map.put(rs.getString("column"), rs.getString("type"));
            if (map.size() > 4096) throw new IllegalStateException("Schema exceeds reader column budget");
            return map;
        });
        for (var name : required) {
            var column = columns.get(name);
            if (!column.storageType().name().equals(actual.get(column.storageName()))) {
                throw new IllegalStateException("Actual QuestDB column type differs from definition: " + name);
            }
        }
    }

    private static Map<String, Column> columns(DatasetDefinition definition) {
        var result = new LinkedHashMap<String, Column>();
        definition.columns().forEach(c -> result.put(c.logicalName(), c));
        return result;
    }
    private static String quoted(String identifier) { DatasetDefinition.identifier(identifier); return '"' + identifier + '"'; }
    private static boolean isTemporalStorage(Column c) {
        return c.storageType() == StorageType.TIMESTAMP || c.storageType() == StorageType.TIMESTAMP_NS || c.storageType() == StorageType.DATE;
    }
    private static void requireOrdered(Column c) {
        if (UNORDERED_STORAGE_TYPES.contains(c.storageType())) {
            throw new IllegalArgumentException("No verified keyset ordering for type " + c.storageType());
        }
    }
    private static String comparison(Column c, String operator, Object value, List<Bound> params) {
        params.add(new Bound(c, storageValue(c, value)));
        return quoted(c.storageName()) + " " + operator + " "
                + (isTemporalStorage(c) ? "cast(? as " + c.storageType().name() + ")" : "?");
    }

    public static Object storageValue(Column c, Object value) {
        return DatasetStorageValues.storageValue(c, value);
    }

    private static Object readValue(ResultSet rs, Column c) throws SQLException {
        Object raw = isTemporalStorage(c) ? rs.getObject(c.logicalName(), Long.class) : switch (c.storageType()) {
            case SYMBOL, STRING, VARCHAR, LONG256, IPV4 -> rs.getString(c.logicalName());
            case UUID -> { String s = rs.getString(c.logicalName()); yield s == null ? null : UUID.fromString(s); }
            case CHAR -> { String s = rs.getString(c.logicalName()); if (s != null && s.length() != 1) throw new SQLException("Invalid CHAR"); yield s == null ? null : s.charAt(0); }
            case BINARY -> rs.getBytes(c.logicalName());
            case BOOLEAN -> rs.getObject(c.logicalName(), Boolean.class);
            case BYTE -> rs.getObject(c.logicalName(), Byte.class);
            case SHORT -> rs.getObject(c.logicalName(), Short.class);
            case INT -> rs.getObject(c.logicalName(), Integer.class);
            case LONG -> rs.getObject(c.logicalName(), Long.class);
            case FLOAT -> rs.getObject(c.logicalName(), Float.class);
            case DOUBLE -> rs.getObject(c.logicalName(), Double.class);
            default -> throw new SQLException("Unsupported physical value");
        };
        if (raw == null) {
            if (!c.nullable()) throw new SQLException("Null in required column: " + c.logicalName());
            return null;
        }
        if (c.temporal() == null) return raw;
        if (!isTemporalStorage(c)) {
            if (c.temporal().kind() != TemporalKind.BUSINESS_DATE) throw new SQLException("Unsupported non-timestamp temporal storage");
            if (c.legacyNullSentinels().contains(raw)) return null;
            return TemporalValues.businessDate((String) raw, TemporalValues.DateFormat.valueOf(c.temporal().sourceFormat()));
        }
        var unit = c.storageType() == StorageType.TIMESTAMP_NS ? TemporalValues.EpochUnit.NANOS
                : c.storageType() == StorageType.TIMESTAMP ? TemporalValues.EpochUnit.MICROS : TemporalValues.EpochUnit.MILLIS;
        var instant = TemporalValues.epoch((Long) raw, unit, TemporalValues.Precision.NANOS);
        return switch (c.temporal().kind()) {
            case BUSINESS_DATE -> TemporalValues.CalendarTimestamp.fromStorage(instant).date();
            case INSTANT -> instant;
            case TECHNICAL -> new TemporalValues.TechnicalTimestamp(instant, c.temporal().meaning());
        };
    }

    private static void bind(PreparedStatement statement, int position, Bound bound) throws SQLException {
        var value = bound.storageValue();
        if (value instanceof Character character) statement.setString(position, character.toString());
        else if (value instanceof byte[] bytes) statement.setBytes(position, bytes);
        else statement.setObject(position, value);
    }
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static int compare(Object left, Object right) { return ((Comparable) left).compareTo(right); }
    private static String fingerprint(DatasetDefinition definition, DatasetReadQuery query) {
        try {
            var parts = new ArrayList<Object>();
            parts.add(definition.datasetId()); parts.add(definition.schemaVersion()); parts.add(definition.objectName());
            parts.add(definition.columns().toString()); parts.add(definition.businessKey()); parts.add(query.columns());
            for (var entry : new TreeMap<>(query.equalities()).entrySet()) parts.add(List.of(entry.getKey(), canonical(entry.getValue())));
            parts.add(Arrays.asList(query.rangeColumn(), canonical(query.fromInclusive()), canonical(query.toExclusive())));
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(JSON
                    .writeValueAsString(parts).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception error) { throw new IllegalArgumentException("Cannot fingerprint read scope", error); }
    }
    private static String canonical(Object value) {
        return value == null ? "null" : value.getClass().getName() + ":" +
                (value instanceof byte[] bytes ? Base64.getEncoder().encodeToString(bytes) : value.toString());
    }
}
