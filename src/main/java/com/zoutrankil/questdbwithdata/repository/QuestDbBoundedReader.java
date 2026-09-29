package com.zoutrankil.questdbwithdata.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zoutrankil.questdbwithdata.domain.*;
import com.zoutrankil.questdbwithdata.domain.temporal.TemporalValues;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.function.Function;
import static com.zoutrankil.questdbwithdata.domain.DatasetDefinition.*;

/** One bounded SELECT per page plus schema preflight; never relies on JDBC full-table buffering. */
@Repository
public class QuestDbBoundedReader {
    public record Bound(Column column, Object storageValue) {}
    public record PreparedRead(String sql, List<Bound> parameters, String fingerprint) {
        public PreparedRead { parameters = List.copyOf(parameters); }
    }
    private final JdbcTemplate jdbc;
    public QuestDbBoundedReader(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public <T> DatasetReadPage<T> read(DatasetDefinition definition, DatasetReadQuery query, String sourceVersion,
                                      Function<DatasetValues, T> mapper) {
        var prepared = prepare(definition, query, sourceVersion);
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
                    .map(name -> last.get(name, Object.class)).toList(), sourceVersion);
        }
        return new DatasetReadPage<>(definition.datasetId(), definition.schemaVersion(), sourceVersion, Instant.now(),
                selected.stream().map(mapper).toList(), cursor);
    }

    public PreparedRead prepare(DatasetDefinition definition, DatasetReadQuery query, String sourceVersion) {
        definition.requireCapability(Capability.READ);
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
        if (Set.of(StorageType.BINARY, StorageType.LONG256, StorageType.UUID, StorageType.BOOLEAN, StorageType.IPV4).contains(c.storageType())) {
            throw new IllegalArgumentException("No verified keyset ordering for type " + c.storageType());
        }
    }
    private static String comparison(Column c, String operator, Object value, List<Bound> params) {
        params.add(new Bound(c, storageValue(c, value)));
        return quoted(c.storageName()) + " " + operator + " "
                + (isTemporalStorage(c) ? "cast(? as " + c.storageType().name() + ")" : "?");
    }

    public static Object storageValue(Column c, Object value) {
        if (value == null) throw new IllegalArgumentException("Null ordered/bound value");
        if (c.temporal() != null) {
            if (c.temporal().kind() == TemporalKind.BUSINESS_DATE) {
                if (!(value instanceof LocalDate date)) throw new IllegalArgumentException("LocalDate required for " + c.logicalName());
                if (isTemporalStorage(c)) return epoch(c, new TemporalValues.CalendarTimestamp(date).storageCarrier());
                if (c.storageType() == StorageType.STRING || c.storageType() == StorageType.VARCHAR || c.storageType() == StorageType.SYMBOL) {
                    return TemporalValues.formatDate(date, TemporalValues.DateFormat.valueOf(c.temporal().sourceFormat()));
                }
                throw new IllegalArgumentException("Unsupported business date storage");
            }
            Instant instant;
            if (c.temporal().kind() == TemporalKind.TECHNICAL) {
                if (!(value instanceof TemporalValues.TechnicalTimestamp technical)) throw new IllegalArgumentException("Technical marker required");
                instant = technical.storageCarrier();
            } else {
                if (!(value instanceof Instant input)) throw new IllegalArgumentException("Instant required for " + c.logicalName());
                instant = input;
            }
            if (!isTemporalStorage(c)) throw new IllegalArgumentException("Unsupported instant storage");
            return epoch(c, instant);
        }
        Class<?> expected = switch (c.storageType()) {
            case SYMBOL, STRING, VARCHAR, LONG256, IPV4 -> String.class;
            case CHAR -> Character.class;
            case BOOLEAN -> Boolean.class;
            case BYTE -> Byte.class;
            case SHORT -> Short.class;
            case INT -> Integer.class;
            case LONG -> Long.class;
            case FLOAT -> Float.class;
            case DOUBLE -> Double.class;
            case UUID -> UUID.class;
            case BINARY -> byte[].class;
            default -> throw new IllegalArgumentException("Missing temporal contract");
        };
        if (!expected.isInstance(value) || value instanceof Double d && !Double.isFinite(d)
                || value instanceof Float f && !Float.isFinite(f)) throw new IllegalArgumentException("Wrong or nonfinite value for " + c.logicalName());
        return value;
    }

    private static long epoch(Column c, Instant instant) {
        var precision = switch (c.storageType()) {
            case DATE -> TemporalValues.Precision.MILLIS;
            case TIMESTAMP -> TemporalValues.Precision.MICROS;
            case TIMESTAMP_NS -> TemporalValues.Precision.NANOS;
            default -> throw new IllegalArgumentException("Not a time storage type");
        };
        TemporalValues.requirePrecision(instant, precision);
        long perSecond = precision == TemporalValues.Precision.NANOS ? 1000000000L
                : precision == TemporalValues.Precision.MICROS ? 1000000L : 1000L;
        return Math.addExact(Math.multiplyExact(instant.getEpochSecond(), perSecond), instant.getNano() / (1000000000L / perSecond));
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
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(new ObjectMapper()
                    .writeValueAsString(parts).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception error) { throw new IllegalArgumentException("Cannot fingerprint read scope", error); }
    }
    private static String canonical(Object value) {
        return value == null ? "null" : value.getClass().getName() + ":" +
                (value instanceof byte[] bytes ? Base64.getEncoder().encodeToString(bytes) : value.toString());
    }
}
