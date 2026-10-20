package com.zoutrankil.data.factor.storage;

import com.zoutrankil.data.factor.application.GlobalObservationCoverage.Key;
import com.zoutrankil.data.factor.application.GlobalObservationPublisher;
import com.zoutrankil.data.factor.domain.GlobalFactorObservation;
import com.zoutrankil.data.repository.QuestDbWriteChecks;
import com.zoutrankil.data.repository.StaticTargetIdentity;

import io.questdb.client.QuestDB;
import io.questdb.client.Sender;

import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** One publication attempt owns this adapter and its pinned physical table identity. */
public final class QuestDbGlobalObservationStore implements GlobalObservationPublisher.Store {
    private static final Map<String, String> TYPES =
            Map.of(
                    "signal_date",
                    "TIMESTAMP",
                    "entity_type",
                    "SYMBOL",
                    "entity_id",
                    "SYMBOL",
                    "factor_id",
                    "SYMBOL",
                    "definition_version",
                    "SYMBOL",
                    "factor_value",
                    "DOUBLE",
                    "available_at",
                    "TIMESTAMP",
                    "quality_status",
                    "SYMBOL",
                    "scope",
                    "SYMBOL",
                    "l2_source_lineage_digest",
                    "SYMBOL");
    private static final Set<String> KEYS =
            Set.of("signal_date", "entity_type", "entity_id", "factor_id", "definition_version");
    private final JdbcTemplate jdbc;
    private final QuestDB questdb;
    private final QuestDbGlobalObservations reader;
    private String targetIdentity;

    public String targetIdentity() {
        preflight();
        return targetIdentity;
    }

    private volatile boolean senderStopped;

    public boolean uncertainSenderStopped() {
        return senderStopped;
    }

    public QuestDbGlobalObservationStore(JdbcTemplate jdbc, QuestDB questdb) {
        this.jdbc = new JdbcTemplate(Objects.requireNonNull(jdbc.getDataSource()));
        this.jdbc.setQueryTimeout(20);
        this.questdb = Objects.requireNonNull(questdb);
        this.reader = new QuestDbGlobalObservations(this.jdbc);
    }

    @Override
    public void preflight() {
        // One metadata round trip keeps repeated page checks bounded without caching schema or WAL
        // state.
        var states =
                jdbc.queryForList(
                        "SELECT t.id,t.directoryName,t.designatedTimestamp,t.walEnabled,"
                                + "t.table_suspended,t.table_txn,t.wal_txn,t.wal_pending_row_count,w.suspended AS writer_suspended,"
                                + "w.writerTxn,w.sequencerTxn,w.bufferedTxnSize,c.\"column\",c.\"type\",c.upsertKey,c.designated"
                                + " FROM tables() t CROSS JOIN table_columns('factor_observation') c CROSS JOIN wal_tables() w"
                                + " WHERE t.table_name='factor_observation' AND w.name='factor_observation'");
        if (states.size() != TYPES.size())
            throw new IllegalStateException("Missing observation target or changed schema");
        var state = states.getFirst();
        for (var row : states)
            for (String field :
                    List.of(
                            "id",
                            "directoryName",
                            "designatedTimestamp",
                            "walEnabled",
                            "table_suspended",
                            "table_txn",
                            "wal_txn",
                            "wal_pending_row_count",
                            "writer_suspended",
                            "writerTxn",
                            "sequencerTxn",
                            "bufferedTxnSize"))
                if (!Objects.equals(row.get(field), state.get(field)))
                    throw new IllegalStateException(
                            "Observation metadata changed during preflight");
        if (!Boolean.TRUE.equals(state.get("walEnabled"))
                || !Boolean.FALSE.equals(state.get("table_suspended"))
                || !Boolean.FALSE.equals(state.get("writer_suspended"))
                || !zero(state.get("wal_pending_row_count"))
                || !zero(state.get("bufferedTxnSize"))
                || !same(state.get("table_txn"), state.get("wal_txn"))
                || !same(state.get("writerTxn"), state.get("sequencerTxn"))
                || !same(state.get("table_txn"), state.get("writerTxn")))
            throw new IllegalStateException("Observation WAL must be healthy and settled");
        if (!(state.get("id") instanceof Number id)
                || !(state.get("directoryName") instanceof String directory)
                || !"signal_date".equals(state.get("designatedTimestamp")))
            throw new IllegalStateException("Missing observation physical identity or timestamp");
        String identity =
                StaticTargetIdentity.identify(
                        jdbc, "factor_observation", id.longValue(), directory);
        if (targetIdentity != null && !targetIdentity.equals(identity))
            throw new IllegalStateException(
                    "Physical observation target changed during publication");
        var types = new HashMap<String, String>();
        var keys = new HashSet<String>();
        for (var column : states) {
            String name = (String) column.get("column");
            types.put(name, (String) column.get("type"));
            if (Boolean.TRUE.equals(column.get("upsertKey"))) keys.add(name);
            if (Boolean.TRUE.equals(column.get("designated")) && !name.equals("signal_date"))
                throw new IllegalStateException("Wrong designated timestamp");
        }
        if (!types.equals(TYPES) || !keys.equals(KEYS))
            throw new IllegalStateException(
                    "Observation schema or dedup key does not match canonical contract");
        targetIdentity = identity;
    }

    private static boolean zero(Object value) {
        return value instanceof Number number && number.longValue() == 0;
    }

    private static boolean same(Object a, Object b) {
        return a instanceof Number left
                && b instanceof Number right
                && left.longValue() == right.longValue();
    }

    @Override
    public List<GlobalFactorObservation> read(GlobalObservationPublisher.Section section) {
        preflight();
        return reader.read(
                section.date(),
                section.entityType(),
                section.factorId(),
                section.version(),
                100_000);
    }

    public List<GlobalFactorObservation> readSelected(
            GlobalObservationPublisher.Section section, List<String> entityIds) {
        if (entityIds.isEmpty()) return List.of();
        preflight();
        return reader.readSelected(
                section.date(),
                section.entityType(),
                section.factorId(),
                section.version(),
                100_000,
                entityIds);
    }

    public List<GlobalFactorObservation> readKeys(List<Key> keys) {
        preflight();
        return reader.readKeys(keys);
    }

    @Override
    public void send(List<GlobalFactorObservation> rows) throws Exception {
        long started = System.nanoTime();
        if (rows == null || rows.isEmpty() || rows.size() > 2000)
            throw new IllegalArgumentException("Expected 1..2000 observations");
        preflight();
        long chars = 0;
        for (var row : rows) {
            chars +=
                    row.entityId().length()
                            + row.entityType().length()
                            + row.factorId().length()
                            + row.definitionVersion().length()
                            + row.qualityStatus().length()
                            + row.scope().length()
                            + 128L;
            if (row.l2SourceLineageDigest() != null) chars += row.l2SourceLineageDigest().length();
        }
        if (chars > 1024 * 1024)
            throw new IllegalArgumentException("Observation batch exceeds transport budget");
        senderStopped = false;
        Sender sender = questdb.borrowSender();
        String phase = "encode";
        long borrowed = System.nanoTime(),
                encoded = borrowed,
                flushed = borrowed,
                acknowledged = borrowed;
        Exception sendFailure = null;
        try {
            for (var row : rows) {
                var line =
                        sender.table("factor_observation")
                                .symbol("entity_type", row.entityType())
                                .symbol("entity_id", row.entityId())
                                .symbol("factor_id", row.factorId())
                                .symbol("definition_version", row.definitionVersion())
                                .symbol("quality_status", row.qualityStatus())
                                .symbol("scope", row.scope());
                if (row.l2SourceLineageDigest() != null)
                    line.symbol("l2_source_lineage_digest", row.l2SourceLineageDigest());
                if (row.factorValue() != null) line.doubleColumn("factor_value", row.factorValue());
                line.timestampColumn("available_at", row.availableAt().toInstant(ZoneOffset.UTC))
                        .at(row.signalDate().atStartOfDay().toInstant(ZoneOffset.UTC));
            }
            phase = "flush";
            encoded = System.nanoTime();
            long sequence = sender.flushAndGetSequence();
            flushed = System.nanoTime();
            phase = "acknowledgement";
            if (sequence < 0 || !sender.awaitAckedFsn(sequence, 10_000))
                throw new IllegalStateException(
                        "Observation acknowledgement uncertain; reconcile before replay");
            acknowledged = System.nanoTime();
        } catch (Exception failure) {
            sendFailure = failure;
            long failedAt = System.nanoTime();
            switch (phase) {
                case "encode" -> {
                    encoded = failedAt;
                    flushed = failedAt;
                    acknowledged = failedAt;
                }
                case "flush" -> {
                    flushed = failedAt;
                    acknowledged = failedAt;
                }
                case "acknowledgement" -> acknowledged = failedAt;
                default ->
                        throw new IllegalStateException("Unknown observation send phase", failure);
            }
            LoggerFactory.getLogger(QuestDbGlobalObservationStore.class)
                    .warn("Observation send failed phase={} rows={}", phase, rows.size(), failure);
            throw failure;
        } finally {
            long closeStarted = System.nanoTime();
            // Set proof only if close succeeds; failed close leaves delivery uncertain.
            try {
                sender.close();
                senderStopped = true;
            } catch (Exception failure) {
                LoggerFactory.getLogger(QuestDbGlobalObservationStore.class)
                        .warn("Observation sender close failed rows={}", rows.size(), failure);
                if (sendFailure != null) sendFailure.addSuppressed(failure);
                else throw failure;
            } finally {
                long closed = System.nanoTime();
                if (closed - started > Duration.ofSeconds(2).toNanos())
                    LoggerFactory.getLogger(QuestDbGlobalObservationStore.class)
                            .info(
                                    "Observation transport rows={} phase={} completed={} setupMillis={} encodeMillis={} flushMillis={} ackMillis={} closeMillis={}",
                                    rows.size(),
                                    phase,
                                    sendFailure == null && senderStopped,
                                    (borrowed - started) / 1_000_000,
                                    Math.max(0, encoded - borrowed) / 1_000_000,
                                    Math.max(0, flushed - encoded) / 1_000_000,
                                    Math.max(0, acknowledged - flushed) / 1_000_000,
                                    (closed - closeStarted) / 1_000_000);
            }
        }
        long delivered = System.nanoTime();
        // QWP acknowledgement and query visibility are separate. Bound the wait;
        // the owner retains an uncertain attempt if WAL does not settle in time.
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (!QuestDbWriteChecks.walSettled(jdbc, "factor_observation")) {
            if (System.nanoTime() >= deadline) {
                LoggerFactory.getLogger(QuestDbGlobalObservationStore.class)
                        .warn(
                                "Observation WAL visibility timeout rows={} sendMillis={}",
                                rows.size(),
                                (delivered - started) / 1_000_000);
                throw new IllegalStateException(
                        "Observation WAL visibility timed out; reconcile before replay");
            }
            Thread.sleep(100);
        }
        preflight();
        long finished = System.nanoTime();
        if (finished - started > Duration.ofSeconds(2).toNanos())
            LoggerFactory.getLogger(QuestDbGlobalObservationStore.class)
                    .info(
                            "Slow observation batch rows={} sendMillis={} visibilityMillis={}",
                            rows.size(),
                            (delivered - started) / 1_000_000,
                            (finished - delivered) / 1_000_000);
    }
}
