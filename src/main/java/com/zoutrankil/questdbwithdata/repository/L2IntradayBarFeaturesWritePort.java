package com.zoutrankil.questdbwithdata.repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zoutrankil.questdbwithdata.domain.DatasetDefinition;
import com.zoutrankil.questdbwithdata.domain.DatasetValues;
import com.zoutrankil.questdbwithdata.domain.L2IntradayBarFeatureField;
import com.zoutrankil.questdbwithdata.domain.L2IntradayBarFeatures;
import com.zoutrankil.questdbwithdata.domain.L2IntradayBarFeaturesDataset;
import com.zoutrankil.questdbwithdata.domain.L2IntradayBarFeaturesKey;
import com.zoutrankil.questdbwithdata.domain.temporal.TemporalValues;
import com.zoutrankil.questdbwithdata.mapper.L2IntradayBarFeaturesMapper;
import com.zoutrankil.questdbwithdata.service.VerifiedBatchExecutor;
import io.questdb.client.QuestDB;
import io.questdb.client.Sender;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;

/** Bounded D087 QWP writer; all writes require an explicitly named isolated acceptance table. */
public final class L2IntradayBarFeaturesWritePort
        implements VerifiedBatchExecutor.Port<L2IntradayBarFeatures, L2IntradayBarFeaturesKey> {
    public static final String ISOLATED_TABLE_PREFIX = "java_d087_l2_intraday_bar_features_";
    public static final int MAX_BATCH_ROWS = 200;
    public static final int MAX_BATCH_BYTES = 1024 * 1024;
    public static final int MAX_READBACK_KEYS = 200;
    private static final ZoneId EXCHANGE_ZONE = ZoneId.of("Asia/Shanghai");
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final List<String> SELECT_COLUMNS = L2IntradayBarFeaturesMapper.columns();

    public static final VerifiedBatchExecutor.Codec<L2IntradayBarFeatures, L2IntradayBarFeaturesKey> CODEC =
            new VerifiedBatchExecutor.Codec<>() {
                @Override public L2IntradayBarFeaturesKey key(L2IntradayBarFeatures row) { return row.key(); }
                @Override public byte[] canonicalBytes(L2IntradayBarFeatures row) {
                    try { return JSON.writeValueAsBytes(canonicalMap(row)); }
                    catch (JsonProcessingException failure) {
                        throw new IllegalStateException("Cannot encode a canonical D087 source row", failure);
                    }
                }
                @Override public int estimatedTransportBytes(L2IntradayBarFeatures row, byte[] canonical) {
                    return Math.addExact(Math.multiplyExact(canonical.length, 8), 512);
                }
            };

    private final String table;
    private final JdbcTemplate jdbc;
    private final QuestDB questdb;
    private final L2IntradayBarFeaturesMapper mapper = new L2IntradayBarFeaturesMapper();
    private final AtomicBoolean senderActive = new AtomicBoolean();

    public L2IntradayBarFeaturesWritePort(String table, JdbcTemplate jdbc, QuestDB questdb) {
        requireIsolatedTableName(table);
        this.table = table;
        this.jdbc = new JdbcTemplate(Objects.requireNonNull(jdbc).getDataSource());
        this.jdbc.setQueryTimeout(20);
        this.questdb = Objects.requireNonNull(questdb);
    }

    public String tableName() { return table; }

    public void createIsolatedTargetIfMissing() {
        requireIsolatedTableName(table);
        String columns = java.util.Arrays.stream(L2IntradayBarFeatureField.values())
                .map(L2IntradayBarFeaturesWritePort::ddlColumn).collect(Collectors.joining(", "));
        jdbc.execute("CREATE TABLE IF NOT EXISTS \"" + table + "\" (" + columns
                + ") TIMESTAMP(minute) PARTITION BY DAY WAL DEDUP UPSERT KEYS(symbol,minute)");
        preflight();
    }

    @Override public void preflight() {
        requireIsolatedTableName(table);
        QuestDbWriteChecks.preflight(jdbc, table, L2IntradayBarFeaturesDataset.DEFINITION);
    }

    @Override public void send(List<L2IntradayBarFeatures> rows) {
        if (rows == null || rows.isEmpty() || rows.size() > MAX_BATCH_ROWS)
            throw new IllegalArgumentException("One nonempty bounded D087 write batch is required");
        var normalized = DatasetWritePreparation.prepare(L2IntradayBarFeaturesDataset.DEFINITION, rows,
                mapper::values, new DatasetWritePreparation.Limits(MAX_BATCH_ROWS, MAX_BATCH_BYTES));
        long estimated = rows.stream().mapToLong(row -> CODEC.estimatedTransportBytes(row,
                CODEC.canonicalBytes(row))).sum();
        if (normalized.empty() || estimated > MAX_BATCH_BYTES)
            throw new IllegalArgumentException("D087 batch exceeds its row or transport byte budget");
        if (!senderActive.compareAndSet(false, true)) throw new IllegalStateException("D087 writer is already active");
        try (Sender sender = questdb.borrowSender()) {
            for (L2IntradayBarFeatures value : rows) {
                var line = sender.table(table).symbol("symbol", value.symbol())
                        .stringColumn("trade_date", TemporalValues.formatDate(value.tradeDate(), TemporalValues.DateFormat.BASIC))
                        .stringColumn("market", value.market()).stringColumn("board", value.board());
                for (var field : L2IntradayBarFeatureField.values()) {
                    if (!field.metric()) continue;
                    Object metric = value.features().get(field);
                    if (metric == null) continue;
                    line = switch (field.storageType()) {
                        case LONG -> line.longColumn(field.fieldName(), (Long) metric);
                        case DOUBLE -> line.doubleColumn(field.fieldName(), (Double) metric);
                        default -> throw new IllegalArgumentException("Unexpected D087 feature type: " + field.fieldName());
                    };
                }
                line.at(value.minute());
            }
            long sequence = sender.flushAndGetSequence();
            if (sequence < 0 || !sender.awaitAckedFsn(sequence, 10_000))
                throw new IllegalStateException("D087 QWP acknowledgement is unknown; reconcile the complete key batch before replay");
        } finally {
            senderActive.set(false);
        }
    }

    @Override public List<L2IntradayBarFeatures> readback(List<L2IntradayBarFeaturesKey> keys) {
        if (keys == null || keys.isEmpty() || keys.size() > MAX_READBACK_KEYS
                || new HashSet<>(keys).size() != keys.size())
            throw new IllegalArgumentException("At most 200 unique D087 keys may be read back per batch");
        var clauses = new ArrayList<String>(keys.size());
        var parameters = new ArrayList<Object>(keys.size() * 2);
        for (var key : keys) {
            clauses.add("(symbol=? AND minute=cast(? as TIMESTAMP))");
            parameters.add(key.symbol());
            parameters.add(TemporalValues.epochValue(key.minute(), TemporalValues.EpochUnit.MICROS));
        }
        String projection = String.join(", ", SELECT_COLUMNS.stream().map(column -> column.equals("minute")
                ? "cast(minute as long) AS minute_micros" : column).toList());
        String sql = "SELECT " + projection + " FROM \"" + table + "\" WHERE "
                + String.join(" OR ", clauses) + " ORDER BY symbol,minute LIMIT " + (keys.size() + 1);
        return jdbc.query(connection -> {
            var statement = connection.prepareStatement(sql);
            statement.setQueryTimeout(20);
            statement.setMaxRows(keys.size() + 1);
            statement.setFetchSize(keys.size() + 1);
            for (int i = 0; i < parameters.size(); i++) {
                Object parameter = parameters.get(i);
                if (parameter instanceof String text) statement.setString(i + 1, text);
                else if (parameter instanceof Long number) statement.setLong(i + 1, number);
                else throw new SQLException("Unsupported D087 business-key parameter");
            }
            return statement;
        }, (rs, index) -> mapper.fromValues(readValues(rs)));
    }

    public LocalDate readLatestTradeDate() {
        var rows = jdbc.queryForList("SELECT cast(max(minute) as long) AS minute_micros FROM \"" + table + "\"");
        if (rows.isEmpty()) return null;
        Object raw = rows.getFirst().get("minute_micros");
        if (raw == null) return null;
        if (!(raw instanceof Number value))
            throw new IllegalStateException("D087 latest-minute aggregate did not return a numeric value");
        long micros = value.longValue();
        return TemporalValues.epoch(micros, TemporalValues.EpochUnit.MICROS, TemporalValues.Precision.MICROS)
                .atZone(EXCHANGE_ZONE).toLocalDate();
    }

    public int rowCount() {
        Long count = jdbc.queryForObject("SELECT count() FROM \"" + table + "\"", Long.class);
        if (count == null) return 0;
        if (count > 300_000 || count > Integer.MAX_VALUE)
            throw new IllegalStateException("D087 isolated target exceeds its planning scan budget");
        return count.intValue();
    }

    public int countRows(LocalDate tradeDate) {
        Instant from = TemporalValues.localInstant(tradeDate.atStartOfDay(), EXCHANGE_ZONE,
                TemporalValues.Precision.MICROS);
        Instant to = TemporalValues.localInstant(tradeDate.plusDays(1).atStartOfDay(), EXCHANGE_ZONE,
                TemporalValues.Precision.MICROS);
        Long count = jdbc.queryForObject("SELECT count() FROM \"" + table
                        + "\" WHERE minute >= cast(? as TIMESTAMP) AND minute < cast(? as TIMESTAMP)", Long.class,
                TemporalValues.epochValue(from, TemporalValues.EpochUnit.MICROS),
                TemporalValues.epochValue(to, TemporalValues.EpochUnit.MICROS));
        if (count == null) return 0;
        if (count > Integer.MAX_VALUE) throw new IllegalStateException("D087 selected date row count exceeds its bound");
        return count.intValue();
    }

    @Override public boolean walSettled() { return QuestDbWriteChecks.walSettled(jdbc, table); }
    @Override public boolean uncertainSenderStopped() { return !senderActive.get(); }

    public static void requireIsolatedTableName(String table) {
        DatasetDefinition.identifier(table);
        if (!table.startsWith(ISOLATED_TABLE_PREFIX) || table.length() <= ISOLATED_TABLE_PREFIX.length())
            throw new IllegalStateException("D087 writes require java_d087_l2_intraday_bar_features_<suffix>");
    }

    private DatasetValues readValues(ResultSet rs) throws SQLException {
        long micros = rs.getLong("minute_micros");
        if (rs.wasNull()) throw new SQLException("D087 designated minute is null");
        var values = new LinkedHashMap<String, Object>();
        values.put("trade_date", rs.getString("trade_date"));
        values.put("symbol", rs.getString("symbol"));
        values.put("market", rs.getString("market"));
        values.put("board", rs.getString("board"));
        values.put("minute", micros);
        for (var field : L2IntradayBarFeatureField.values()) {
            if (!field.metric()) continue;
            Object value = rs.getObject(field.fieldName());
            if (value != null) {
                try { value = field.normalize(value); }
                catch (IllegalArgumentException failure) {
                    throw new SQLException("D087 readback type mismatch: " + field.fieldName(), failure);
                }
            }
            values.put(field.fieldName(), value);
        }
        return new DatasetValues(values);
    }

    private static Map<String, Object> canonicalMap(L2IntradayBarFeatures row) {
        var values = new LinkedHashMap<String, Object>();
        values.put("trade_date", TemporalValues.formatDate(row.tradeDate(), TemporalValues.DateFormat.BASIC));
        values.put("symbol", row.symbol());
        values.put("market", row.market());
        values.put("board", row.board());
        values.put("minute", row.minute().toString());
        for (var field : L2IntradayBarFeatureField.values())
            if (field.metric()) values.put(field.fieldName(), row.features().get(field));
        return values;
    }

    private static String ddlColumn(L2IntradayBarFeatureField field) {
        String type = switch (field.storageType()) {
            case TIMESTAMP -> "TIMESTAMP";
            case SYMBOL -> "SYMBOL";
            case STRING -> "STRING";
            case LONG -> "LONG";
            case DOUBLE -> "DOUBLE";
            default -> throw new IllegalArgumentException("Unsupported D087 QuestDB type: " + field.fieldName());
        };
        return field.fieldName() + " " + type;
    }
}
