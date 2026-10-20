package com.zoutrankil.data.factor.storage;

import com.zoutrankil.data.factor.application.GlobalObservationCoverage;
import com.zoutrankil.data.factor.application.GlobalObservationCoverage.Key;
import com.zoutrankil.data.factor.application.GlobalObservationIncrementalRead;
import com.zoutrankil.data.factor.application.GlobalObservationIncrementalRead.Result;
import com.zoutrankil.data.factor.application.GlobalObservationIncrementalRead.Section;
import com.zoutrankil.data.factor.domain.GlobalFactorObservation;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/** Reads one physical GLOBAL date/factor/version section for bounded replay checks. */
@Repository
public class QuestDbGlobalObservations {
    private final JdbcTemplate jdbc;

    public QuestDbGlobalObservations(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Result reconcile(List<GlobalFactorObservation> expected) {
        var before = state();
        var result =
                GlobalObservationIncrementalRead.reconcile(
                        expected,
                        (section, limit) ->
                                read(
                                        section.date(),
                                        section.entityType(),
                                        section.factorId(),
                                        section.version(),
                                        limit));
        if (!before.equals(state()))
            throw new IllegalStateException("Observations changed during reconciliation; retry");
        return result;
    }

    public List<GlobalFactorObservation> read(
            LocalDate date, String entityType, String factorId, String version, int maxRows) {
        return readSelected(date, entityType, factorId, version, maxRows, List.of());
    }

    /** One query per bounded page, including pages spanning several factors. */
    public List<GlobalFactorObservation> readKeys(List<Key> keys) {
        if (keys.isEmpty()) return List.of();
        if (keys.size() > 2000 || new HashSet<>(keys).size() != keys.size())
            throw new IllegalArgumentException("Expected at most 2000 unique observation keys");
        var groups = new LinkedHashMap<Section, List<String>>();
        for (var key : keys) {
            Objects.requireNonNull(key.date());
            for (String value :
                    new String[] {key.entityType(), key.factorId(), key.version(), key.entityId()})
                if (value == null || value.isBlank())
                    throw new IllegalArgumentException("Explicit observation key required");
            var section = new Section(key.date(), key.entityType(), key.factorId(), key.version());
            groups.computeIfAbsent(section, ignored -> new ArrayList<>()).add(key.entityId());
        }
        LocalDate from = keys.stream().map(Key::date).min(LocalDate::compareTo).orElseThrow();
        LocalDate through = keys.stream().map(Key::date).max(LocalDate::compareTo).orElseThrow();
        if (through.isAfter(from.plusDays(30)))
            throw new IllegalArgumentException("Observation readback window exceeds 31 days");
        var dimensions = new LinkedHashMap<String, Set<String>>();
        for (String field : List.of("entity_type", "entity_id", "factor_id", "definition_version"))
            dimensions.put(field, new TreeSet<>());
        for (var key : keys) {
            dimensions.get("entity_type").add(key.entityType());
            dimensions.get("entity_id").add(key.entityId());
            dimensions.get("factor_id").add(key.factorId());
            dimensions.get("definition_version").add(key.version());
        }
        long candidates = through.toEpochDay() - from.toEpochDay() + 1;
        for (var values : dimensions.values()) {
            candidates *= values.size();
            if (candidates > 10_000) break;
        }
        if (candidates <= 10_000 && candidates <= Math.max(250, keys.size() * 5L))
            return readCandidateKeys(keys, from, through, dimensions, (int) candidates);
        var predicates = new ArrayList<String>();
        var parameters = new ArrayList<Object>();
        for (var entry : groups.entrySet()) {
            var section = entry.getKey();
            var ids = entry.getValue();
            predicates.add(
                    "(signal_date>='"
                            + section.date()
                            + "' AND signal_date<'"
                            + section.date().plusDays(1)
                            + "' AND entity_type=? AND factor_id=? AND definition_version=? AND entity_id IN ("
                            + String.join(",", Collections.nCopies(ids.size(), "?"))
                            + "))");
            parameters.add(section.entityType());
            parameters.add(section.factorId());
            parameters.add(section.version());
            parameters.addAll(ids);
        }
        var before = state();
        var rows =
                jdbc.query(
                        "SELECT signal_date,entity_type,entity_id,factor_id,definition_version,"
                                + "factor_value,available_at,quality_status,scope,l2_source_lineage_digest FROM factor_observation WHERE "
                                + "signal_date>='"
                                + from
                                + "' AND signal_date<'"
                                + through.plusDays(1)
                                + "' AND ("
                                + String.join(" OR ", predicates)
                                + ") LIMIT "
                                + (keys.size() + 1),
                        QuestDbGlobalObservations::mapRow,
                        parameters.toArray());
        if (rows.size() > keys.size())
            throw new IllegalStateException("Duplicate physical observation keys");
        if (!before.equals(state()))
            throw new IllegalStateException("Observations changed during read; retry");
        return List.copyOf(rows);
    }

    /** Read a small bounded superset with simple IN predicates, then retain exact requested keys. */
    private List<GlobalFactorObservation> readCandidateKeys(
            List<Key> keys,
            LocalDate from,
            LocalDate through,
            Map<String, Set<String>> dimensions,
            int limit) {
        var predicates = new ArrayList<String>();
        var parameters = new ArrayList<Object>();
        for (var entry : dimensions.entrySet()) {
            predicates.add(
                    entry.getKey()
                            + " IN ("
                            + String.join(",", Collections.nCopies(entry.getValue().size(), "?"))
                            + ")");
            parameters.addAll(entry.getValue());
        }
        var before = state();
        var candidates =
                jdbc.query(
                        "SELECT signal_date,entity_type,entity_id,factor_id,definition_version,"
                                + "factor_value,available_at,quality_status,scope,l2_source_lineage_digest FROM factor_observation WHERE "
                                + "signal_date>='"
                                + from
                                + "' AND signal_date<'"
                                + through.plusDays(1)
                                + "' AND "
                                + String.join(" AND ", predicates)
                                + " LIMIT "
                                + (limit + 1),
                        QuestDbGlobalObservations::mapRow,
                        parameters.toArray());
        if (candidates.size() > limit)
            throw new IllegalStateException("Observation candidate read exceeds unique-key bound");
        var wanted = new HashSet<>(keys);
        var rows =
                candidates.stream()
                        .filter(row -> wanted.contains(GlobalObservationCoverage.key(row)))
                        .toList();
        if (rows.size() > keys.size())
            throw new IllegalStateException("Duplicate physical observation keys");
        if (!before.equals(state()))
            throw new IllegalStateException("Observations changed during candidate read; retry");
        return rows;
    }

    public List<GlobalFactorObservation> readSelected(
            LocalDate date,
            String entityType,
            String factorId,
            String version,
            int maxRows,
            List<String> entityIds) {
        if (entityIds.size() > 250 || new HashSet<>(entityIds).size() != entityIds.size())
            throw new IllegalArgumentException("At most 250 unique entity ids required");
        for (String id : entityIds)
            if (id == null || id.isBlank()) throw new IllegalArgumentException("Blank entity id");
        var parameters = new ArrayList<Object>();
        parameters.add(entityType);
        parameters.add(factorId);
        parameters.add(version);
        parameters.addAll(entityIds);
        String selection =
                entityIds.isEmpty()
                        ? ""
                        : " AND entity_id IN ("
                                + String.join(",", Collections.nCopies(entityIds.size(), "?"))
                                + ")";
        Objects.requireNonNull(date);
        for (String value : new String[] {entityType, factorId, version})
            if (value == null || value.isBlank())
                throw new IllegalArgumentException("Explicit section required");
        if (maxRows < 1 || maxRows > 100_000)
            throw new IllegalArgumentException("Row limit must be 1..100000");
        var before = state();
        var rows =
                jdbc.query(
                        "SELECT signal_date,entity_type,entity_id,factor_id,definition_version,"
                                + "factor_value,available_at,quality_status,scope,l2_source_lineage_digest FROM factor_observation"
                                + " WHERE signal_date>='"
                                + date
                                + "' AND signal_date<'"
                                + date.plusDays(1)
                                + "' AND entity_type=? AND factor_id=? AND definition_version=?"
                                + selection
                                + " ORDER BY entity_id LIMIT "
                                + (maxRows + 1),
                        QuestDbGlobalObservations::mapRow,
                        parameters.toArray());
        if (rows.size() > maxRows)
            throw new IllegalStateException("Observation section exceeds row limit");
        if (!before.equals(state()))
            throw new IllegalStateException("Observations changed during read; retry");
        return List.copyOf(rows);
    }

    private static GlobalFactorObservation mapRow(ResultSet rs, int number) throws SQLException {
        var signal = rs.getTimestamp("signal_date").toLocalDateTime();
        if (!signal.toLocalTime().equals(LocalTime.MIDNIGHT))
            throw new IllegalStateException("Non-midnight signal_date in daily observation");
        double value = rs.getDouble("factor_value");
        Double nullableValue = rs.wasNull() || Double.isNaN(value) ? null : value;
        var available = rs.getTimestamp("available_at");
        if (available == null) throw new IllegalStateException("Missing physical availability");
        return new GlobalFactorObservation(
                signal.toLocalDate(),
                rs.getString("entity_type"),
                rs.getString("entity_id"),
                rs.getString("factor_id"),
                rs.getString("definition_version"),
                nullableValue,
                available.toLocalDateTime(),
                rs.getString("quality_status"),
                rs.getString("scope"),
                rs.getString("l2_source_lineage_digest"));
    }

    /** Stable, settled physical generation for a read spanning multiple observation pages. */
    public List<Map<String, Object>> state() {
        var rows =
                jdbc.queryForList(
                        "SELECT id,directoryName,table_txn,wal_txn,table_suspended,wal_pending_row_count"
                                + " FROM tables() WHERE table_name='factor_observation'");
        if (rows.size() != 1) throw new IllegalStateException("Missing factor_observation table");
        var row = rows.getFirst();
        if (!(row.get("table_txn") instanceof Number table)
                || !(row.get("wal_txn") instanceof Number wal)
                || !(row.get("wal_pending_row_count") instanceof Number pending)
                || !Boolean.FALSE.equals(row.get("table_suspended"))
                || table.longValue() != wal.longValue()
                || pending.longValue() != 0)
            throw new IllegalStateException("Observation WAL not settled");
        return rows.stream().map(Map::copyOf).toList();
    }
}
