package com.zoutrankil.data.l2.storage;

import com.zoutrankil.data.repository.*;

import com.zoutrankil.data.domain.DatasetDefinition;
import com.zoutrankil.data.domain.DatasetValues;
import com.zoutrankil.data.domain.L2EventResponseFeatureField;
import com.zoutrankil.data.domain.L2EventResponseFeatures;
import com.zoutrankil.data.domain.L2EventResponseFeaturesDataset;
import com.zoutrankil.data.domain.L2EventResponseFeaturesKey;
import com.zoutrankil.data.domain.temporal.TemporalValues;
import com.zoutrankil.data.l2.mapper.L2EventResponseFeaturesMapper;
import com.zoutrankil.data.l2.domain.L2EventResponseFeaturesRows;
import com.zoutrankil.data.l2.port.L2EventResponseFeaturesWriteSession;
import com.zoutrankil.data.service.VerifiedBatchExecutor;
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
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;

/** Bounded D088 QWP writer; all writes require an explicitly named isolated acceptance table. */
public final class L2EventResponseFeaturesWritePort
        implements L2EventResponseFeaturesWriteSession {
    public static final String ISOLATED_TABLE_PREFIX = L2EventResponseFeaturesRows.ISOLATED_TABLE_PREFIX;
    public static final int MAX_BATCH_ROWS = L2EventResponseFeaturesRows.MAX_BATCH_ROWS;
    public static final int MAX_BATCH_BYTES = L2EventResponseFeaturesRows.MAX_BATCH_BYTES;
    public static final int MAX_READBACK_KEYS = L2EventResponseFeaturesRows.MAX_READBACK_KEYS;
    private static final ZoneId EXCHANGE_ZONE = ZoneId.of("Asia/Shanghai");
    private static final List<String> SELECT_COLUMNS = L2EventResponseFeaturesMapper.columns();

    public static final VerifiedBatchExecutor.Codec<L2EventResponseFeatures, L2EventResponseFeaturesKey> CODEC =
            new VerifiedBatchExecutor.Codec<>() {
                @Override public L2EventResponseFeaturesKey key(L2EventResponseFeatures row) { return L2EventResponseFeaturesRows.key(row); }
                @Override public byte[] canonicalBytes(L2EventResponseFeatures row) {
                    return L2EventResponseFeaturesRows.canonicalBytes(row);
                }
                @Override public int estimatedTransportBytes(L2EventResponseFeatures row, byte[] canonical) {
                    return L2EventResponseFeaturesRows.estimatedTransportBytes(row, canonical);
                }
            };

    private final String table;
    private final JdbcTemplate jdbc;
    private final QuestDB questdb;
    private final L2EventResponseFeaturesMapper mapper = new L2EventResponseFeaturesMapper();
    private final AtomicBoolean senderActive = new AtomicBoolean();

    public L2EventResponseFeaturesWritePort(String table, JdbcTemplate jdbc, QuestDB questdb) {
        requireIsolatedTableName(table);
        this.table = table;
        this.jdbc = new JdbcTemplate(Objects.requireNonNull(jdbc).getDataSource());
        this.jdbc.setQueryTimeout(20);
        this.questdb = Objects.requireNonNull(questdb);
    }

    @Override public VerifiedBatchExecutor.Codec<L2EventResponseFeatures, L2EventResponseFeaturesKey> codec() { return CODEC; }

    public String tableName() { return table; }

    public void createIsolatedTargetIfMissing() {
        requireIsolatedTableName(table);
        String columns = java.util.Arrays.stream(L2EventResponseFeatureField.values())
                .map(L2EventResponseFeaturesWritePort::ddlColumn).collect(Collectors.joining(", "));
        jdbc.execute("CREATE TABLE IF NOT EXISTS \"" + table + "\" (" + columns
                + ") TIMESTAMP(minute) PARTITION BY DAY WAL DEDUP UPSERT KEYS(symbol,minute,event_type)");
        preflight();
    }

    @Override public void preflight() {
        requireIsolatedTableName(table);
        QuestDbWriteChecks.preflight(jdbc, table, L2EventResponseFeaturesDataset.DEFINITION);
    }

    @Override public void send(List<L2EventResponseFeatures> rows) {
        if (rows == null || rows.isEmpty() || rows.size() > MAX_BATCH_ROWS)
            throw new IllegalArgumentException("One nonempty bounded D088 write batch is required");
        var normalized = DatasetWritePreparation.prepare(L2EventResponseFeaturesDataset.DEFINITION, rows,
                mapper::values, new DatasetWritePreparation.Limits(MAX_BATCH_ROWS, MAX_BATCH_BYTES));
        long estimated = rows.stream().mapToLong(row -> CODEC.estimatedTransportBytes(row,
                CODEC.canonicalBytes(row))).sum();
        if (normalized.empty() || estimated > MAX_BATCH_BYTES)
            throw new IllegalArgumentException("D088 batch exceeds its row or transport byte budget");
        if (!senderActive.compareAndSet(false, true)) throw new IllegalStateException("D088 writer is already active");
        try (Sender sender = questdb.borrowSender()) {
            for (L2EventResponseFeatures value : rows) {
                var line = sender.table(table).symbol("symbol", value.symbol())
                        .stringColumn("trade_date", TemporalValues.formatDate(value.tradeDate(), TemporalValues.DateFormat.BASIC))
                        .stringColumn("market", value.market()).stringColumn("board", value.board())
                        .stringColumn("event_type", value.eventType());
                for (var field : L2EventResponseFeatureField.values()) {
                    if (!field.metric()) continue;
                    Object metric = value.features().get(field);
                    if (metric == null) continue;
                    line = switch (field.storageType()) {
                        case LONG -> line.longColumn(field.fieldName(), (Long) metric);
                        case DOUBLE -> line.doubleColumn(field.fieldName(), (Double) metric);
                        default -> throw new IllegalArgumentException("Unexpected D088 feature type: " + field.fieldName());
                    };
                }
                line.at(value.minute());
            }
            long sequence = sender.flushAndGetSequence();
            if (sequence < 0 || !sender.awaitAckedFsn(sequence, 10_000))
                throw new IllegalStateException("D088 QWP acknowledgement is unknown; reconcile the complete key batch before replay");
        } finally {
            senderActive.set(false);
        }
    }

    @Override public List<L2EventResponseFeatures> readback(List<L2EventResponseFeaturesKey> keys) {
        if (keys == null || keys.isEmpty() || keys.size() > MAX_READBACK_KEYS
                || new HashSet<>(keys).size() != keys.size())
            throw new IllegalArgumentException("At most 200 unique D088 keys may be read back per batch");
        var clauses = new ArrayList<String>(keys.size());
        var parameters = new ArrayList<Object>(keys.size() * 3);
        for (var key : keys) {
            clauses.add("(symbol=? AND minute=cast(? as TIMESTAMP) AND event_type=?)");
            parameters.add(key.symbol());
            parameters.add(TemporalValues.epochValue(key.minute(), TemporalValues.EpochUnit.MICROS));
            parameters.add(key.eventType());
        }
        String projection = String.join(", ", SELECT_COLUMNS.stream().map(column -> column.equals("minute")
                ? "cast(minute as long) AS minute_micros" : column).toList());
        String sql = "SELECT " + projection + " FROM \"" + table + "\" WHERE "
                + String.join(" OR ", clauses) + " ORDER BY symbol,minute,event_type LIMIT " + (keys.size() + 1);
        return jdbc.query(connection -> {
            var statement = connection.prepareStatement(sql);
            statement.setQueryTimeout(20);
            statement.setMaxRows(keys.size() + 1);
            statement.setFetchSize(keys.size() + 1);
            for (int i = 0; i < parameters.size(); i++) {
                Object parameter = parameters.get(i);
                if (parameter instanceof String text) statement.setString(i + 1, text);
                else if (parameter instanceof Long number) statement.setLong(i + 1, number);
                else throw new SQLException("Unsupported D088 business-key parameter");
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
            throw new IllegalStateException("D088 latest-minute aggregate did not return a numeric value");
        long micros = value.longValue();
        return TemporalValues.epoch(micros, TemporalValues.EpochUnit.MICROS, TemporalValues.Precision.MICROS)
                .atZone(EXCHANGE_ZONE).toLocalDate();
    }

    public int rowCount() {
        Long count = jdbc.queryForObject("SELECT count() FROM \"" + table + "\"", Long.class);
        if (count == null) return 0;
        if (count > 300_000 || count > Integer.MAX_VALUE)
            throw new IllegalStateException("D088 isolated target exceeds its planning scan budget");
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
        if (count > Integer.MAX_VALUE) throw new IllegalStateException("D088 selected date row count exceeds its bound");
        return count.intValue();
    }

    @Override public boolean walSettled() { return QuestDbWriteChecks.walSettled(jdbc, table); }
    @Override public boolean uncertainSenderStopped() { return !senderActive.get(); }

    public static void requireIsolatedTableName(String table) {
        L2EventResponseFeaturesRows.requireIsolatedTableName(table);
    }

    private DatasetValues readValues(ResultSet rs) throws SQLException {
        long micros = rs.getLong("minute_micros");
        if (rs.wasNull()) throw new SQLException("D088 designated minute is null");
        var values = new LinkedHashMap<String, Object>();
        values.put("trade_date", rs.getString("trade_date"));
        values.put("symbol", rs.getString("symbol"));
        values.put("market", rs.getString("market"));
        values.put("board", rs.getString("board"));
        values.put("minute", micros);
        values.put("event_type", rs.getString("event_type"));
        for (var field : L2EventResponseFeatureField.values()) {
            if (!field.metric()) continue;
            Object value = rs.getObject(field.fieldName());
            if (value != null) {
                try { value = field.normalize(value); }
                catch (IllegalArgumentException failure) {
                    throw new SQLException("D088 readback type mismatch: " + field.fieldName(), failure);
                }
            }
            values.put(field.fieldName(), value);
        }
        return new DatasetValues(values);
    }


    private static String ddlColumn(L2EventResponseFeatureField field) {
        String type = switch (field.storageType()) {
            case TIMESTAMP -> "TIMESTAMP";
            case SYMBOL -> "SYMBOL";
            case STRING -> "STRING";
            case LONG -> "LONG";
            case DOUBLE -> "DOUBLE";
            default -> throw new IllegalArgumentException("Unsupported D088 QuestDB type: " + field.fieldName());
        };
        return field.fieldName() + " " + type;
    }
}
