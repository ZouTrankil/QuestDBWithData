package com.zoutrankil.data.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zoutrankil.data.derived.domain.RetailSentimentDailyLayout;
import com.zoutrankil.data.derived.storage.DerivedMonthlyReadGuards;
import com.zoutrankil.data.derived.storage.MarketBreadthDailyMaterializationPort;
import com.zoutrankil.data.derived.storage.RetailSentimentDailyMaterializationPort;
import com.zoutrankil.data.domain.DatasetDefinition;
import com.zoutrankil.data.domain.DatasetDefinition.Capability;
import com.zoutrankil.data.domain.DatasetDefinition.Column;
import com.zoutrankil.data.domain.DatasetDefinition.ObjectKind;
import com.zoutrankil.data.domain.DatasetDefinition.StorageType;
import com.zoutrankil.data.domain.DatasetDefinition.TemporalKind;
import com.zoutrankil.data.domain.DatasetReadCursor;
import com.zoutrankil.data.domain.DatasetReadPage;
import com.zoutrankil.data.domain.DatasetReadQuery;
import com.zoutrankil.data.domain.DatasetStorageValues;
import com.zoutrankil.data.domain.DatasetValues;
import com.zoutrankil.data.domain.derived.RetailSentimentDailyDataset;
import com.zoutrankil.data.domain.temporal.TemporalValues;
import com.zoutrankil.market.cn.model.CnTradeDateLayout;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Function;

/** 每页执行一次有界 SELECT，并预先校验表结构；不依赖 JDBC 缓冲整张表。 */
@Repository
public class QuestDbBoundedReader {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<StorageType> UNORDERED_STORAGE_TYPES =
            Set.of(
                    StorageType.BINARY,
                    StorageType.LONG256,
                    StorageType.UUID,
                    StorageType.BOOLEAN,
                    StorageType.IPV4);

    public record Bound(Column column, Object storageValue) {}

    public record PreparedRead(String sql, List<Bound> parameters, String fingerprint) {
        public PreparedRead {
            parameters = List.copyOf(parameters);
        }
    }

    private static final String GUARDED_ETF_AGGREGATE_VIEW =
            EtfMarketOverviewMaterializedViewReadGuard.OUTPUT;
    private static final List<String> GUARDED_ETF_AGGREGATE_SOURCES =
            List.of("etf_share", "etf_daily");
    private static final Set<String> ETF_AGGREGATE_COLUMNS =
            Set.of("trade_date", "etf_count", "total_share", "total_size_yi");
    private final JdbcTemplate jdbc;
    private final Path ledgerPath;

    public QuestDbBoundedReader(JdbcTemplate jdbc) {
        this(jdbc, System.getProperty("app.sync.ledger-path", "var/sync-ledger.sqlite3"));
    }

    @Autowired
    public QuestDbBoundedReader(
            JdbcTemplate jdbc,
            @Value("${app.sync.ledger-path:var/sync-ledger.sqlite3}") String ledger) {
        this.jdbc = jdbc;
        this.ledgerPath = Path.of(ledger).toAbsolutePath().normalize();
    }

    public <T> DatasetReadPage<T> read(
            DatasetDefinition definition,
            DatasetReadQuery query,
            String sourceVersion,
            Function<DatasetValues, T> mapper) {
        validateEtfAggregateQuery(definition, query);
        DerivedMonthlyReadGuards.validateEquityStyle(definition, query);
        DerivedMonthlyReadGuards.validateMacroCore(definition, query);
        // 将本页绑定到读取前的来源版本；读取后再次核验，拒绝跨版本混合结果。
        String guardedBefore = guardedSourceVersion(definition);
        String effectiveSourceVersion = guardedBefore == null ? sourceVersion : guardedBefore;
        var prepared = prepare(definition, query, effectiveSourceVersion);
        var columns = columns(definition);
        verifySchema(definition, query.columns(), columns);
        List<DatasetValues> values =
                jdbc.query(
                        connection -> {
                            var statement = connection.prepareStatement(prepared.sql());
                            statement.setQueryTimeout(20);
                            statement.setMaxRows(query.pageSize() + 1);
                            statement.setFetchSize(query.pageSize() + 1);
                            for (int i = 0; i < prepared.parameters().size(); i++)
                                bind(statement, i + 1, prepared.parameters().get(i));
                            return statement;
                        },
                        (rs, index) -> {
                            var row = new LinkedHashMap<String, Object>();
                            for (var name : query.columns())
                                row.put(name, readValue(rs, columns.get(name)));
                            return new DatasetValues(row);
                        });
        var seen = new HashSet<List<Object>>();
        for (var row : values) {
            var key =
                    definition.businessKey().stream()
                            .map(name -> row.get(name, Object.class))
                            .toList();
            if (key.stream().anyMatch(Objects::isNull) || !seen.add(key)) {
                throw new IllegalStateException(
                        "Null/duplicate complete business key; stable pagination cannot be proven");
            }
        }
        boolean more = values.size() > query.pageSize();
        var selected = more ? values.subList(0, query.pageSize()) : values;
        DatasetReadCursor cursor = null;
        if (more) {
            var last = selected.getLast();
            cursor =
                    new DatasetReadCursor(
                            prepared.fingerprint(),
                            definition.businessKey().stream()
                                    .map(name -> last.get(name, Object.class))
                                    .toList(),
                            effectiveSourceVersion);
        }
        if (guardedBefore != null && !guardedBefore.equals(guardedSourceVersion(definition))) {
            throw new IllegalStateException("Guarded source changed during bounded read");
        }
        return new DatasetReadPage<>(
                definition.datasetId(),
                definition.schemaVersion(),
                effectiveSourceVersion,
                Instant.now(),
                selected.stream().map(mapper).toList(),
                cursor);
    }

    /** 日期条件限制输出桶范围；LIMIT 本身不能证明原生 JOIN 只扫描这些源行。 */
    private static void validateEtfAggregateQuery(
            DatasetDefinition definition, DatasetReadQuery query) {
        if (!GUARDED_ETF_AGGREGATE_VIEW.equals(definition.objectName())) return;
        if (definition.objectKind() != ObjectKind.MATERIALIZED_VIEW
                || !definition.dependencies().equals(GUARDED_ETF_AGGREGATE_SOURCES))
            throw new IllegalStateException(
                    "ETF aggregate requires its registered native MV and exact two-source contract");
        if (query.columns().size() != ETF_AGGREGATE_COLUMNS.size()
                || !new HashSet<>(query.columns()).equals(ETF_AGGREGATE_COLUMNS)
                || query.pageSize() > 31)
            throw new IllegalArgumentException(
                    "ETF aggregate requires all four fields and a page of at most 31 daily buckets");
        boolean exactDate = query.equalities().containsKey("trade_date");
        if (exactDate && !(query.equalities().get("trade_date") instanceof LocalDate))
            throw new IllegalArgumentException(
                    "ETF aggregate exact date requires a non-null LocalDate");
        if (query.rangeColumn() != null) {
            if (!"trade_date".equals(query.rangeColumn())
                    || !(query.fromInclusive() instanceof LocalDate from)
                    || !(query.toExclusive() instanceof LocalDate to)
                    || !from.isBefore(to)
                    || ChronoUnit.DAYS.between(from, to) > 31)
                throw new IllegalArgumentException(
                        "ETF aggregate requires an increasing LocalDate range of at most 31 days");
        } else if (!exactDate) {
            throw new IllegalArgumentException(
                    "ETF aggregate requires an exact date or a bounded daily range");
        }
    }

    private String guardedSourceVersion(DatasetDefinition definition) {
        if (BacktestDailyMaterializationReadGuard.applies(definition))
            return BacktestDailyMaterializationReadGuard.version(jdbc, definition);
        if (DerivedMonthlyReadGuards.appliesEquityStyle(definition))
            return DerivedMonthlyReadGuards.versionEquityStyle(jdbc, definition);
        if (DerivedMonthlyReadGuards.appliesMacroCore(definition))
            return DerivedMonthlyReadGuards.versionMacroCore(jdbc, definition);
        if (GUARDED_ETF_AGGREGATE_VIEW.equals(definition.objectName()))
            return EtfMarketOverviewMaterializedViewReadGuard.versionIfActivated(jdbc, ledgerPath);
        if (definition.objectKind() == ObjectKind.MATERIALIZED_VIEW)
            return requireCurrentMaterializedView(definition).sourceVersion();
        return null;
    }

    private static String normalizedSql(String sql) {
        return sql == null ? "" : sql.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    private record MaterializedViewState(
            long baseTableId,
            long basePhysicalTxn,
            long baseSeqTxn,
            long refreshTxn,
            long viewTableId,
            long viewPhysicalTxn,
            long viewSeqTxn) {
        String sourceVersion() {
            // RANGE 或 FULL 刷新可能改写物化结果，而基表检查点保持不变。
            // 同时绑定表身份，防止删除重建后误用旧游标中的事务计数。
            return "base-id:"
                    + baseTableId
                    + ":base-txn:"
                    + basePhysicalTxn
                    + ":base-seq-txn:"
                    + baseSeqTxn
                    + ":refresh-txn:"
                    + refreshTxn
                    + ":mv-id:"
                    + viewTableId
                    + ":mv-txn:"
                    + viewPhysicalTxn
                    + ":mv-seq-txn:"
                    + viewSeqTxn;
        }
    }

    private MaterializedViewState requireCurrentMaterializedView(DatasetDefinition definition) {
        String objectName = definition.objectName();
        RetailSentimentDailyLayout retail = null;
        if ("mv_retail_sentiment_daily".equals(definition.datasetId())) {
            if (definition.schemaVersion() == 1) retail = RetailSentimentDailyLayout.LEGACY;
            else if (definition.schemaVersion() == 2) {
                String source = definition.columns().getFirst().sourceName();
                if (!source.endsWith(".trade_date"))
                    throw new IllegalArgumentException("MV v2 日期来源不明确");
                retail =
                        new RetailSentimentDailyLayout(
                                CnTradeDateLayout.TRADE_DATE_V2,
                                source.substring(0, source.length() - ".trade_date".length()),
                                objectName);
            } else throw new IllegalArgumentException("未知 MV 契约版本");
            if (!RetailSentimentDailyDataset.definition(
                            retail.outputTable(), retail.sourceTable(), retail.layout())
                    .equals(definition)) throw new IllegalArgumentException("MV 读取契约与显式作用域不同");
        }
        if (retail == null && RetailSentimentDailyMaterializationPort.OUTPUT.equals(objectName))
            retail = RetailSentimentDailyLayout.LEGACY;
        if (retail == null && objectName.startsWith(RetailSentimentDailyLayout.V2_OUTPUT_PREFIX))
            throw new IllegalArgumentException("隔离 MV 读取必须使用明确的散户情绪契约");
        final var retailScope = retail;
        // objectName 已由 DatasetDefinition 校验为合法标识符，可用于此固定元数据查询。
        // tables().table_txn 表示数据版本；WAL 应用进度使用 wal_tables() 的计数。
        // FULL 或 TRUNCATE 后两类计数可能不同，不能直接互相比较。
        return jdbc.query(
                "SELECT m.view_status,m.refresh_base_table_txn,m.base_table_txn,m.base_table_name,m.view_sql,"
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
                        + "WHERE m.view_name='"
                        + objectName
                        + "'",
                rs -> {
                    if (!rs.next()) throw new IllegalStateException("Materialized view is absent");
                    if (MarketBreadthDailyMaterializationPort.OUTPUT.equals(objectName)
                            && (!MarketBreadthDailyMaterializationPort.SOURCE.equals(
                                            rs.getString("base_table_name"))
                                    || !normalizedSql(
                                                    MarketBreadthDailyMaterializationPort
                                                            .DEFINITION_SQL)
                                            .equals(normalizedSql(rs.getString("view_sql")))))
                        throw new IllegalStateException(
                                "Breadth MV differs from its fixed base-table or aggregation definition");
                    if (retailScope != null
                            && (!retailScope.sourceTable().equals(rs.getString("base_table_name"))
                                    || !normalizedSql(
                                                    RetailSentimentDailyMaterializationPort
                                                            .definitionSql(retailScope))
                                            .equals(normalizedSql(rs.getString("view_sql")))))
                        throw new IllegalStateException("散户情绪 MV 与显式来源或聚合 SQL 不同");
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
                    if (!"valid".equals(status)
                            || refreshTxn != baseTxn
                            || baseTxn != baseSeqTxn
                            || baseWriterTxn != baseSeqTxn
                            || baseBufferedTxns != 0
                            || viewWriterTxn != viewSeqTxn
                            || viewBufferedTxns != 0
                            || rs.getBoolean("base_suspended")
                            || rs.getBoolean("view_suspended")
                            || !rs.getBoolean("is_materialized_view")) {
                        throw new IllegalStateException(
                                "Materialized view is invalid or behind its base table");
                    }
                    if (rs.next())
                        throw new IllegalStateException("Duplicate materialized view metadata");
                    return new MaterializedViewState(
                            baseTableId,
                            basePhysicalTxn,
                            baseSeqTxn,
                            refreshTxn,
                            viewTableId,
                            viewPhysicalTxn,
                            viewSeqTxn);
                });
    }

    private static long requiredCounter(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        if (rs.wasNull() || value < 0) {
            throw new IllegalStateException("Source version metadata is unavailable: " + column);
        }
        return value;
    }

    public PreparedRead prepare(
            DatasetDefinition definition, DatasetReadQuery query, String sourceVersion) {
        definition.requireCapability(Capability.READ);
        validateEtfAggregateQuery(definition, query);
        DerivedMonthlyReadGuards.validateEquityStyle(definition, query);
        DerivedMonthlyReadGuards.validateMacroCore(definition, query);
        var columns = columns(definition);
        if (!columns.keySet().containsAll(query.columns())
                || !columns.keySet().containsAll(query.equalities().keySet())
                || !query.columns().containsAll(definition.businessKey())) {
            throw new IllegalArgumentException(
                    "Unknown field or missing complete key in explicit projection");
        }
        for (var key : definition.businessKey()) requireOrdered(columns.get(key));
        String fingerprint = fingerprint(definition, query);
        if (query.cursor() != null
                && (!fingerprint.equals(query.cursor().queryFingerprint())
                        || !Objects.equals(sourceVersion, query.cursor().sourceVersion())
                        || query.cursor().keyValues().size() != definition.businessKey().size())) {
            throw new IllegalArgumentException(
                    "Cursor belongs to a different query/schema/source version");
        }
        var sql = new StringBuilder("SELECT ");
        sql.append(
                String.join(
                        ", ",
                        query.columns().stream()
                                .map(
                                        name -> {
                                            var column = columns.get(name);
                                            String expression = quoted(column.storageName());
                                            if (isTemporalStorage(column))
                                                expression = "cast(" + expression + " as long)";
                                            return expression + " AS " + quoted(name);
                                        })
                                .toList()));
        sql.append(" FROM ").append(quoted(definition.objectName()));
        var conditions = new ArrayList<String>();
        var parameters = new ArrayList<Bound>();
        for (var entry : new TreeMap<>(query.equalities()).entrySet()) {
            var column = columns.get(entry.getKey());
            if (entry.getValue() == null) {
                if (!column.nullable())
                    throw new IllegalArgumentException("Null filter on required field");
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
            Object from = storageValue(column, query.fromInclusive()),
                    to = storageValue(column, query.toExclusive());
            if (compare(from, to) >= 0) throw new IllegalArgumentException("Range must increase");
            conditions.add(comparison(column, ">=", query.fromInclusive(), parameters));
            conditions.add(comparison(column, "<", query.toExclusive(), parameters));
        }
        if (query.cursor() != null) {
            var alternatives = new ArrayList<String>();
            for (int i = 0; i < definition.businessKey().size(); i++) {
                var prefix = new ArrayList<String>();
                for (int j = 0; j <= i; j++)
                    prefix.add(
                            comparison(
                                    columns.get(definition.businessKey().get(j)),
                                    j == i ? ">" : "=",
                                    query.cursor().keyValues().get(j),
                                    parameters));
                alternatives.add("(" + String.join(" AND ", prefix) + ")");
            }
            conditions.add("(" + String.join(" OR ", alternatives) + ")");
        }
        if (!conditions.isEmpty()) sql.append(" WHERE ").append(String.join(" AND ", conditions));
        sql.append(" ORDER BY ")
                .append(
                        String.join(
                                ", ",
                                definition.businessKey().stream()
                                        .map(
                                                name ->
                                                        quoted(columns.get(name).storageName())
                                                                + " ASC")
                                        .toList()));
        sql.append(" LIMIT ").append(query.pageSize() + 1); // 这里拼接的是经过边界校验的整数，不是用户提供的 SQL。
        return new PreparedRead(sql.toString(), parameters, fingerprint);
    }

    private void verifySchema(
            DatasetDefinition definition, List<String> projection, Map<String, Column> columns) {
        var required = new HashSet<>(projection);
        // 校验所有声明列，防止仅用于过滤的字段悄然改变存储类型。
        required.addAll(columns.keySet());
        Map<String, String> actual =
                jdbc.query(
                        connection -> {
                            var statement =
                                    connection.prepareStatement(
                                            "SELECT \"column\", \"type\" FROM table_columns('"
                                                    + definition.objectName()
                                                    + "') LIMIT 4097");
                            statement.setQueryTimeout(20);
                            statement.setMaxRows(4097);
                            return statement;
                        },
                        rs -> {
                            var map = new HashMap<String, String>();
                            while (rs.next()) map.put(rs.getString("column"), rs.getString("type"));
                            if (map.size() > 4096)
                                throw new IllegalStateException(
                                        "Schema exceeds reader column budget");
                            return map;
                        });
        for (var name : required) {
            var column = columns.get(name);
            if (!column.storageType().name().equals(actual.get(column.storageName()))) {
                throw new IllegalStateException(
                        "Actual QuestDB column type differs from definition: " + name);
            }
        }
    }

    private static Map<String, Column> columns(DatasetDefinition definition) {
        var result = new LinkedHashMap<String, Column>();
        definition.columns().forEach(c -> result.put(c.logicalName(), c));
        return result;
    }

    private static String quoted(String identifier) {
        // 定义已分别校验表名、逻辑名；物理列还可能是明确登记的 1w/1m。
        DatasetDefinition.storageIdentifier(identifier);
        return '"' + identifier + '"';
    }

    private static boolean isTemporalStorage(Column c) {
        return c.storageType() == StorageType.TIMESTAMP
                || c.storageType() == StorageType.TIMESTAMP_NS
                || c.storageType() == StorageType.DATE;
    }

    private static void requireOrdered(Column c) {
        if (UNORDERED_STORAGE_TYPES.contains(c.storageType())) {
            throw new IllegalArgumentException(
                    "No verified keyset ordering for type " + c.storageType());
        }
    }

    private static String comparison(Column c, String operator, Object value, List<Bound> params) {
        params.add(new Bound(c, storageValue(c, value)));
        return quoted(c.storageName())
                + " "
                + operator
                + " "
                + (isTemporalStorage(c) ? "cast(? as " + c.storageType().name() + ")" : "?");
    }

    public static Object storageValue(Column c, Object value) {
        return DatasetStorageValues.storageValue(c, value);
    }

    private static Object readValue(ResultSet rs, Column c) throws SQLException {
        Object raw =
                isTemporalStorage(c)
                        ? rs.getObject(c.logicalName(), Long.class)
                        : switch (c.storageType()) {
                            case SYMBOL, STRING, VARCHAR, LONG256, IPV4 ->
                                    rs.getString(c.logicalName());
                            case UUID -> {
                                String s = rs.getString(c.logicalName());
                                yield s == null ? null : UUID.fromString(s);
                            }
                            case CHAR -> {
                                String s = rs.getString(c.logicalName());
                                if (s != null && s.length() != 1)
                                    throw new SQLException("Invalid CHAR");
                                yield s == null ? null : s.charAt(0);
                            }
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
            if (!c.nullable())
                throw new SQLException("Null in required column: " + c.logicalName());
            return null;
        }
        if (c.temporal() == null) return raw;
        if (!isTemporalStorage(c)) {
            if (c.temporal().kind() != TemporalKind.BUSINESS_DATE)
                throw new SQLException("Unsupported non-timestamp temporal storage");
            if (c.legacyNullSentinels().contains(raw)) return null;
            return TemporalValues.businessDate(
                    (String) raw, TemporalValues.DateFormat.valueOf(c.temporal().sourceFormat()));
        }
        var unit =
                c.storageType() == StorageType.TIMESTAMP_NS
                        ? TemporalValues.EpochUnit.NANOS
                        : c.storageType() == StorageType.TIMESTAMP
                                ? TemporalValues.EpochUnit.MICROS
                                : TemporalValues.EpochUnit.MILLIS;
        var instant = TemporalValues.epoch((Long) raw, unit, TemporalValues.Precision.NANOS);
        return switch (c.temporal().kind()) {
            case BUSINESS_DATE -> TemporalValues.CalendarTimestamp.fromStorage(instant).date();
            case INSTANT -> instant;
            case TECHNICAL ->
                    new TemporalValues.TechnicalTimestamp(instant, c.temporal().meaning());
        };
    }

    private static void bind(PreparedStatement statement, int position, Bound bound)
            throws SQLException {
        var value = bound.storageValue();
        if (value instanceof Character character)
            statement.setString(position, character.toString());
        else if (value instanceof byte[] bytes) statement.setBytes(position, bytes);
        else statement.setObject(position, value);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static int compare(Object left, Object right) {
        return ((Comparable) left).compareTo(right);
    }

    private static String fingerprint(DatasetDefinition definition, DatasetReadQuery query) {
        try {
            var parts = new ArrayList<Object>();
            parts.add(definition.datasetId());
            parts.add(definition.schemaVersion());
            parts.add(definition.objectName());
            parts.add(definition.columns().toString());
            parts.add(definition.businessKey());
            parts.add(query.columns());
            for (var entry : new TreeMap<>(query.equalities()).entrySet())
                parts.add(List.of(entry.getKey(), canonical(entry.getValue())));
            parts.add(
                    Arrays.asList(
                            query.rangeColumn(),
                            canonical(query.fromInclusive()),
                            canonical(query.toExclusive())));
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(
                                            JSON.writeValueAsString(parts)
                                                    .getBytes(StandardCharsets.UTF_8)));
        } catch (Exception error) {
            throw new IllegalArgumentException("Cannot fingerprint read scope", error);
        }
    }

    private static String canonical(Object value) {
        return value == null
                ? "null"
                : value.getClass().getName()
                        + ":"
                        + (value instanceof byte[] bytes
                                ? Base64.getEncoder().encodeToString(bytes)
                                : value.toString());
    }
}
