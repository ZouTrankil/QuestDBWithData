package com.zoutrankil.questdbwithdata.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zoutrankil.questdbwithdata.domain.DatasetDefinition;
import com.zoutrankil.questdbwithdata.service.DatasetIntervalLock;
import java.nio.file.*;
import java.sql.*;
import java.time.Instant;
import java.util.*;

/** Immutable static-publication intent plus optimistic persisted phases in the existing run ledger. */
public final class StockDetailPublicationJournal {
    public enum State { PREPARED, OLD_RENAMED, NEW_RENAMED, VERIFIED, IN_DOUBT, RESTORING_OLD, ROLLED_BACK, RESUMING }
    public record Intent(String id,String runId,String target,String backup,String stage,
                         long oldTableId,long newTableId,String beforeFingerprint,String afterFingerprint) {
        public Intent {
            Objects.requireNonNull(id);Objects.requireNonNull(runId);
            DatasetDefinition.identifier(target);DatasetDefinition.identifier(backup);DatasetDefinition.identifier(stage);
            if(Set.of(target,backup,stage).size()!=3) throw new IllegalArgumentException("Distinct publication tables required");
            Objects.requireNonNull(beforeFingerprint);Objects.requireNonNull(afterFingerprint);
        }
    }
    public record Entry(Intent intent,State state,long revision) {}
    private final Path ledger;
    private final ObjectMapper json=new ObjectMapper();
    public StockDetailPublicationJournal(Path ledger) throws SQLException {
        this.ledger=ledger.toAbsolutePath().normalize();
        if(!Files.isRegularFile(this.ledger)) throw new IllegalArgumentException("Existing sync ledger required");
        try(var db=open();var s=db.createStatement()) {
            s.execute("CREATE TABLE IF NOT EXISTS stock_detail_publications (id TEXT PRIMARY KEY,run_id TEXT NOT NULL REFERENCES sync_runs(id),"
                    +"intent_json TEXT NOT NULL,state TEXT NOT NULL,revision INTEGER NOT NULL,updated_at TEXT NOT NULL)");
        }
    }
    private Connection open() throws SQLException {
        var db=DriverManager.getConnection("jdbc:sqlite:"+ledger);
        try(var s=db.createStatement()) { s.execute("PRAGMA foreign_keys=ON");s.execute("PRAGMA busy_timeout=5000"); }
        return db;
    }
    public boolean requireLease(DatasetIntervalLock.Lease lease,boolean allowInDoubt) throws SQLException {
        if(!lease.scope().equals(DatasetIntervalLock.Scope.allDates("stock_detail_info")))
            throw new IllegalArgumentException("Whole stock-detail dataset lease required");
        try(var db=open();var s=db.prepareStatement("SELECT in_doubt FROM sync_interval_locks WHERE id=? AND run_id=? AND dataset_id=? AND from_day=? AND to_day=?")) {
            s.setString(1,lease.id());s.setString(2,lease.runId());s.setString(3,"stock_detail_info");
            s.setLong(4,lease.scope().from().toEpochDay());s.setLong(5,lease.scope().to().toEpochDay());
            try(var rows=s.executeQuery()) {
                if(!rows.next() || !allowInDoubt && rows.getInt(1)!=0) throw new IllegalStateException("Publication lease absent or uncertain");
                return rows.getInt(1)!=0;
            }
        }
    }
    public Entry create(Intent intent) throws Exception {
        try(var db=open();var s=db.prepareStatement("INSERT INTO stock_detail_publications VALUES(?,?,?,'PREPARED',0,?)")) {
            s.setString(1,intent.id());s.setString(2,intent.runId());s.setString(3,json.writeValueAsString(intent));
            s.setString(4,Instant.now().toString());s.executeUpdate();
        }
        return new Entry(intent,State.PREPARED,0);
    }
    public Entry get(String id) throws Exception {
        try(var db=open();var s=db.prepareStatement("SELECT intent_json,state,revision FROM stock_detail_publications WHERE id=?")) {
            s.setString(1,id);try(var r=s.executeQuery()) {
                if(!r.next()) throw new IllegalArgumentException("Unknown stock-detail publication");
                return new Entry(json.readValue(r.getString(1),Intent.class),State.valueOf(r.getString(2)),r.getLong(3));
            }
        }
    }
    public Entry requireSingleForRun(String runId) throws Exception {
        var entry=findSingleForRun(runId);
        if(entry==null) throw new IllegalStateException("No publication intent for interrupted run");
        return entry;
    }
    public Entry findSingleForRun(String runId) throws Exception {
        try(var db=open();var s=db.prepareStatement("SELECT id FROM stock_detail_publications WHERE run_id=? LIMIT 2")) {
            s.setString(1,runId);try(var rows=s.executeQuery()) {
                if(!rows.next()) return null;
                String id=rows.getString(1);
                if(rows.next()) throw new IllegalStateException("Multiple publication intents for interrupted run");
                return get(id);
            }
        }
    }
    public Entry advance(Entry prior,State next) throws Exception {
        boolean valid=next==State.IN_DOUBT && prior.state()!=State.VERIFIED && prior.state()!=State.ROLLED_BACK
                || prior.state()==State.PREPARED && next==State.OLD_RENAMED
                || prior.state()==State.OLD_RENAMED && next==State.NEW_RENAMED
                || prior.state()==State.NEW_RENAMED && next==State.VERIFIED
                || prior.state()==State.IN_DOUBT && next==State.RESTORING_OLD
                || prior.state()==State.IN_DOUBT && next==State.RESUMING
                || prior.state()==State.RESUMING && next==State.NEW_RENAMED
                || prior.state()==State.IN_DOUBT && next==State.VERIFIED
                || prior.state()==State.RESTORING_OLD && next==State.ROLLED_BACK;
        if(!valid) throw new IllegalArgumentException("Invalid publication transition");
        try(var db=open();var s=db.prepareStatement("UPDATE stock_detail_publications SET state=?,revision=revision+1,updated_at=? WHERE id=? AND revision=? AND state=?")) {
            s.setString(1,next.name());s.setString(2,Instant.now().toString());s.setString(3,prior.intent().id());
            s.setLong(4,prior.revision());s.setString(5,prior.state().name());
            if(s.executeUpdate()!=1) throw new IllegalStateException("Publication phase changed concurrently");
        }
        return new Entry(prior.intent(),next,prior.revision()+1);
    }
}
