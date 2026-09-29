package com.zoutrankil.questdbwithdata.repository;

import com.zoutrankil.questdbwithdata.domain.*;
import com.zoutrankil.questdbwithdata.service.DatasetIntervalLock;
import java.nio.file.*;
import java.sql.*;
import java.time.Instant;
import java.util.*;

/** Dataset-scoped, durable publication phases for reference tables with independently verified stages. */
public final class ReferencePublicationJournal {
    public enum State { PREPARED,OLD_MOVED,PUBLISHED,VERIFIED,IN_DOUBT,RESUMING }
    public record Intent(String id,String dataset,String runId,String target,String backup,String stage,
                         String initialTarget,long originalId,String originalDirectory,long replacementId,String beforeFingerprint,String afterFingerprint) {
        public Intent {
            DatasetDefinition.identifier(dataset);DatasetDefinition.identifier(target);
            DatasetDefinition.identifier(backup);DatasetDefinition.identifier(stage);
            if(Set.of(target,backup,stage).size()!=3) throw new IllegalArgumentException("Distinct publication names required");
            for(String value:List.of(id,runId,initialTarget,originalDirectory,beforeFingerprint,afterFingerprint))
                if(value.isBlank()) throw new IllegalArgumentException("Complete publication evidence required");
        }
    }
    public record Entry(Intent intent,State state,long revision) {}
    private final Path path;
    private final String dataset;
    public ReferencePublicationJournal(Path path,String dataset) throws SQLException {
        this.path=path.toAbsolutePath().normalize();DatasetDefinition.identifier(dataset);this.dataset=dataset;
        if(!Files.isRegularFile(this.path)) throw new IllegalArgumentException("Existing run ledger required");
        try(var db=open();var statement=db.createStatement()) {
            statement.execute("CREATE TABLE IF NOT EXISTS reference_publications (id TEXT PRIMARY KEY,dataset TEXT NOT NULL,"
                    +"run_id TEXT NOT NULL REFERENCES sync_runs(id),intent_json TEXT NOT NULL,state TEXT NOT NULL,"
                    +"revision INTEGER NOT NULL,updated_at TEXT NOT NULL,UNIQUE(dataset,run_id))");
        }
    }
    private Connection open() throws SQLException {
        var db=DriverManager.getConnection("jdbc:sqlite:"+path);
        try(var s=db.createStatement()) { s.execute("PRAGMA foreign_keys=ON");s.execute("PRAGMA busy_timeout=5000"); }
        return db;
    }
    public DatasetIntervalLock.Lease requireLease(DatasetIntervalLock.Lease lease,boolean allowUncertain) {
        if(!lease.scope().equals(DatasetIntervalLock.Scope.allDates(dataset))) throw new IllegalArgumentException("Whole owning dataset lease required");
        var actual=new DatasetIntervalLock(path).findOwned(lease.runId(),lease.scope());
        if(actual==null || !actual.id().equals(lease.id()) || actual.inDoubt() && !allowUncertain)
            throw new IllegalStateException("Publication lease absent, changed or uncertain");
        return actual;
    }
    public Entry create(Intent intent) throws Exception {
        if(!intent.dataset().equals(dataset)) throw new IllegalArgumentException("Publication dataset mismatch");
        try(var db=open();var s=db.prepareStatement("INSERT INTO reference_publications VALUES(?,?,?,?,'PREPARED',0,?)")) {
            s.setString(1,intent.id());s.setString(2,dataset);s.setString(3,intent.runId());
            s.setString(4,JobDefinitionJson.mapper().writeValueAsString(intent));s.setString(5,Instant.now().toString());s.executeUpdate();
        }
        return new Entry(intent,State.PREPARED,0);
    }
    public Entry forRun(String run) throws Exception {
        try(var db=open();var s=db.prepareStatement("SELECT intent_json,state,revision FROM reference_publications WHERE dataset=? AND run_id=?")) {
            s.setString(1,dataset);s.setString(2,run);try(var rows=s.executeQuery()) {
                if(!rows.next()) throw new IllegalStateException("No publication for dataset/run");
                return new Entry(JobDefinitionJson.mapper().readValue(rows.getString(1),Intent.class),State.valueOf(rows.getString(2)),rows.getLong(3));
            }
        }
    }
    public Entry advance(Entry prior,State next) throws Exception {
        boolean valid=prior.state()!=State.VERIFIED && prior.state()!=next && next==State.IN_DOUBT
                || prior.state()==State.PREPARED && next==State.OLD_MOVED
                || prior.state()==State.OLD_MOVED && next==State.PUBLISHED
                || prior.state()==State.PUBLISHED && next==State.VERIFIED
                || prior.state()==State.IN_DOUBT && next==State.RESUMING
                || prior.state()==State.RESUMING && next==State.PUBLISHED;
        if(!valid || !prior.intent().dataset().equals(dataset)) throw new IllegalArgumentException("Invalid publication transition");
        try(var db=open();var s=db.prepareStatement("UPDATE reference_publications SET state=?,revision=revision+1,updated_at=? WHERE id=? AND dataset=? AND state=? AND revision=?")) {
            s.setString(1,next.name());s.setString(2,Instant.now().toString());s.setString(3,prior.intent().id());
            s.setString(4,dataset);s.setString(5,prior.state().name());s.setLong(6,prior.revision());
            if(s.executeUpdate()!=1) throw new IllegalStateException("Publication changed concurrently");
        }
        return new Entry(prior.intent(),next,prior.revision()+1);
    }
}
