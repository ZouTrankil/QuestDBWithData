package com.zoutrankil.data.factor.storage;

import com.zoutrankil.data.factor.domain.GlobalFactorObservation;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Reads one physical GLOBAL date/factor/version section for bounded replay checks. */
@Repository
public class QuestDbGlobalObservations {
    private final JdbcTemplate jdbc;
    public QuestDbGlobalObservations(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public com.zoutrankil.data.factor.application.GlobalObservationIncrementalRead.Result reconcile(
            List<GlobalFactorObservation> expected) {
        var before=state();
        var result=com.zoutrankil.data.factor.application.GlobalObservationIncrementalRead.reconcile(expected,
                (section,limit)->read(section.date(),section.entityType(),section.factorId(),section.version(),limit));
        if(!before.equals(state()))throw new IllegalStateException("Observations changed during reconciliation; retry");
        return result;
    }

    public List<GlobalFactorObservation> read(LocalDate date, String entityType,
            String factorId, String version, int maxRows) {
        return readSelected(date,entityType,factorId,version,maxRows,List.of());
    }
    /** One query per bounded page, including pages spanning several factors. */
    public List<GlobalFactorObservation> readKeys(List<com.zoutrankil.data.factor.application.GlobalObservationCoverage.Key> keys) {
        if(keys.isEmpty())return List.of();
        if(keys.size()>2000 || new java.util.HashSet<>(keys).size()!=keys.size())
            throw new IllegalArgumentException("Expected at most 2000 unique observation keys");
        var groups=new java.util.LinkedHashMap<com.zoutrankil.data.factor.application.GlobalObservationIncrementalRead.Section,java.util.List<String>>();
        for(var key:keys) {
            java.util.Objects.requireNonNull(key.date());
            for(String value:new String[]{key.entityType(),key.factorId(),key.version(),key.entityId()})
                if(value==null || value.isBlank())throw new IllegalArgumentException("Explicit observation key required");
            var section=new com.zoutrankil.data.factor.application.GlobalObservationIncrementalRead.Section(
                    key.date(),key.entityType(),key.factorId(),key.version());
            groups.computeIfAbsent(section,ignored->new java.util.ArrayList<>()).add(key.entityId());
        }
        var predicates=new java.util.ArrayList<String>();var parameters=new java.util.ArrayList<Object>();
        for(var entry:groups.entrySet()) {
            var section=entry.getKey();var ids=entry.getValue();
            predicates.add("(signal_date>='"+section.date()+"' AND signal_date<'"+section.date().plusDays(1)
                    +"' AND entity_type=? AND factor_id=? AND definition_version=? AND entity_id IN ("
                    +String.join(",",java.util.Collections.nCopies(ids.size(),"?"))+"))");
            parameters.add(section.entityType());parameters.add(section.factorId());parameters.add(section.version());parameters.addAll(ids);
        }
        LocalDate from=keys.stream().map(com.zoutrankil.data.factor.application.GlobalObservationCoverage.Key::date).min(LocalDate::compareTo).orElseThrow();
        LocalDate through=keys.stream().map(com.zoutrankil.data.factor.application.GlobalObservationCoverage.Key::date).max(LocalDate::compareTo).orElseThrow();
        if(through.isAfter(from.plusDays(30)))throw new IllegalArgumentException("Observation readback window exceeds 31 days");
        var before=state();
        var rows=jdbc.query("SELECT signal_date,entity_type,entity_id,factor_id,definition_version,"
                +"factor_value,available_at,quality_status,scope,l2_source_lineage_digest FROM factor_observation WHERE "
                +"signal_date>='"+from+"' AND signal_date<'"+through.plusDays(1)+"' AND ("
                +String.join(" OR ",predicates)+") LIMIT "+(keys.size()+1),QuestDbGlobalObservations::mapRow,parameters.toArray());
        if(rows.size()>keys.size())throw new IllegalStateException("Duplicate physical observation keys");
        if(!before.equals(state()))throw new IllegalStateException("Observations changed during read; retry");
        return List.copyOf(rows);
    }
    public List<GlobalFactorObservation> readSelected(LocalDate date,String entityType,
            String factorId,String version,int maxRows,List<String> entityIds) {
        if(entityIds.size()>250 || new java.util.HashSet<>(entityIds).size()!=entityIds.size())
            throw new IllegalArgumentException("At most 250 unique entity ids required");
        for(String id:entityIds)if(id==null || id.isBlank())throw new IllegalArgumentException("Blank entity id");
        var parameters=new java.util.ArrayList<Object>();
        parameters.add(entityType);parameters.add(factorId);parameters.add(version);parameters.addAll(entityIds);
        String selection=entityIds.isEmpty()?"":" AND entity_id IN ("+String.join(",",java.util.Collections.nCopies(entityIds.size(),"?"))+")";
        java.util.Objects.requireNonNull(date);
        for (String value : new String[]{entityType, factorId, version})
            if (value == null || value.isBlank()) throw new IllegalArgumentException("Explicit section required");
        if (maxRows < 1 || maxRows > 100_000) throw new IllegalArgumentException("Row limit must be 1..100000");
        var before = state();
        var rows = jdbc.query("SELECT signal_date,entity_type,entity_id,factor_id,definition_version,"
                + "factor_value,available_at,quality_status,scope,l2_source_lineage_digest FROM factor_observation"
                + " WHERE signal_date>='" + date + "' AND signal_date<'" + date.plusDays(1)
                + "' AND entity_type=? AND factor_id=? AND definition_version=?"+selection+" ORDER BY entity_id LIMIT "
                + (maxRows + 1), QuestDbGlobalObservations::mapRow, parameters.toArray());
        if (rows.size() > maxRows) throw new IllegalStateException("Observation section exceeds row limit");
        if (!before.equals(state())) throw new IllegalStateException("Observations changed during read; retry");
        return List.copyOf(rows);
    }
    private static GlobalFactorObservation mapRow(java.sql.ResultSet rs,int number) throws java.sql.SQLException {
                    var signal = rs.getTimestamp("signal_date").toLocalDateTime();
                    if (!signal.toLocalTime().equals(java.time.LocalTime.MIDNIGHT))
                        throw new IllegalStateException("Non-midnight signal_date in daily observation");
                    double value = rs.getDouble("factor_value");
                    Double nullableValue = rs.wasNull() || Double.isNaN(value) ? null : value;
                    var available = rs.getTimestamp("available_at");
                    if (available == null) throw new IllegalStateException("Missing physical availability");
                    return new GlobalFactorObservation(signal.toLocalDate(), rs.getString("entity_type"),
                            rs.getString("entity_id"), rs.getString("factor_id"), rs.getString("definition_version"),
                            nullableValue, available.toLocalDateTime(), rs.getString("quality_status"),
                            rs.getString("scope"), rs.getString("l2_source_lineage_digest"));
    }

    private List<Map<String, Object>> state() {
        var rows = jdbc.queryForList("SELECT id,directoryName,table_txn,wal_txn,table_suspended,wal_pending_row_count"
                + " FROM tables() WHERE table_name='factor_observation'");
        if (rows.size() != 1) throw new IllegalStateException("Missing factor_observation table");
        var row = rows.getFirst();
        if (!(row.get("table_txn") instanceof Number table)
                || !(row.get("wal_txn") instanceof Number wal)
                || !(row.get("wal_pending_row_count") instanceof Number pending)
                || !Boolean.FALSE.equals(row.get("table_suspended"))
                || table.longValue() != wal.longValue() || pending.longValue() != 0)
            throw new IllegalStateException("Observation WAL not settled");
        return rows;
    }
}
