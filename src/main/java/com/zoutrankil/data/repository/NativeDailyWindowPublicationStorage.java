package com.zoutrankil.data.repository;

import com.zoutrankil.data.domain.JobDefinitionJson;
import org.springframework.jdbc.core.JdbcTemplate;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Objects;
import java.util.Optional;
import static com.zoutrankil.data.repository.ReferencePublicationJournal.State;

/** Database operations for the application-owned native daily publication protocol. */
public final class NativeDailyWindowPublicationStorage {
    private final JdbcTemplate jdbc;

    public NativeDailyWindowPublicationStorage(JdbcTemplate source) {
        jdbc=new JdbcTemplate(Objects.requireNonNull(source.getDataSource()));jdbc.setQueryTimeout(120);
    }

    public void requireNoPendingPublication(Path ledgerPath,String dataset)throws SQLException {
        if(!Files.isRegularFile(ledgerPath))return;
        try(var db=sqliteReadOnly(ledgerPath);var exists=db.prepareStatement("SELECT count(*) FROM sqlite_master WHERE type='table' AND name='reference_publications'");var rows=exists.executeQuery()) {
            if(!rows.next()||rows.getInt(1)==0)return;
            try(var query=db.prepareStatement("SELECT run_id FROM reference_publications WHERE dataset=? AND state<>'VERIFIED' LIMIT 1")) {
                query.setString(1,dataset);try(var pending=query.executeQuery()) {
                    if(pending.next())throw new IllegalStateException("Native daily publication requires explicit reconciliation: "+pending.getString(1));
                }
            }
        }
    }

    public Optional<ReferencePublicationJournal.Entry> findForRun(Path ledgerPath,String dataset,String runId)throws Exception {
        if(!Files.isRegularFile(ledgerPath))return Optional.empty();
        try(var db=sqliteReadOnly(ledgerPath);var exists=db.prepareStatement("SELECT count(*) FROM sqlite_master WHERE type='table' AND name='reference_publications'");var rows=exists.executeQuery()) {
            if(!rows.next()||rows.getInt(1)==0)return Optional.empty();
            try(var query=db.prepareStatement("SELECT intent_json,state,revision FROM reference_publications WHERE dataset=? AND run_id=?")) {
                query.setString(1,dataset);query.setString(2,runId);try(var result=query.executeQuery()) {
                    if(!result.next())return Optional.empty();
                    return Optional.of(new ReferencePublicationJournal.Entry(JobDefinitionJson.mapper().readValue(result.getString(1),ReferencePublicationJournal.Intent.class),State.valueOf(result.getString(2)),result.getLong(3)));
                }
            }
        }
    }

    public void rename(String from,String to) { jdbc.execute("RENAME TABLE \""+from+"\" TO \""+to+"\""); }
    public boolean tableExists(String table) { return !jdbc.queryForList("SELECT id FROM tables() WHERE table_name=?",table).isEmpty(); }
    public boolean identityMatches(String table,long id) {
        var metadata=jdbc.queryForList("SELECT id FROM tables() WHERE table_name=?",table);
        return metadata.size()==1&&metadata.getFirst().get("id") instanceof Number n&&n.longValue()==id;
    }
    private Connection sqliteReadOnly(Path ledgerPath)throws SQLException {
        var db=DriverManager.getConnection("jdbc:sqlite:"+ledgerPath.toUri().toASCIIString()+"?mode=ro");
        try(var statement=db.createStatement()){statement.execute("PRAGMA query_only=ON");statement.execute("PRAGMA busy_timeout=5000");}catch(SQLException failure){db.close();throw failure;}return db;
    }
}
