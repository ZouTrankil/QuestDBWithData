package com.zoutrankil.data.repository;

import com.zoutrankil.data.domain.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.sql.*;
import java.time.LocalDate;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static com.zoutrankil.data.repository.BacktestDailyMaterializationPort.*;

class BacktestDailyMaterializationReadGuardTest {
    @TempDir Path root;
    private Path ledger,source,proof;
    private final LocalDate day=LocalDate.of(2026,10,16);
    private final Identity base=new Identity(7,"backtest_daily~7",5576,day,day,19,19,19);
    private final ViewState view=new ViewState(ALIAS,20,"v_backtest_daily~20",VIEW_SQL);
    private final String pin="a".repeat(64);
    private final JdbcTemplate jdbc=mock(JdbcTemplate.class);

    @BeforeEach void publication() throws Exception {
        ledger=root.resolve("ledger.sqlite3");
        var evidence=root.resolve("sync-evidence/run-one");Files.createDirectories(evidence);
        source=Files.writeString(evidence.resolve("source.json"),"{\"sourceComplete\":true}");
        proof=evidence.resolve("publication.json");
        var json=JobDefinitionJson.mapper();
        // A legacy immutable proof may describe the removed duplicate MV. Its base attestation remains authoritative.
        Files.writeString(proof,json.writeValueAsString(Map.of("base",base,"native",Map.of("mvTxn",58),
                "sourcePin",pin,"sourceEvidenceSha256",hash(source),"historyCertified",true)));
        var scope=json.writeValueAsString(Map.of("sourceEvidence",source.toString(),"sourceEvidenceSha256",hash(source),"sourcePin",pin));
        var intent=new ReferencePublicationJournal.Intent("pub-one",BASE,"run-one",BASE,
                "java_backtest_daily_backup_fixture","java_backtest_daily_stage_fixture","fixture-target",7,
                "backtest_daily~7",7,"before","after",scope);
        try(var db=DriverManager.getConnection("jdbc:sqlite:"+ledger);var s=db.createStatement()) {
            s.execute("CREATE TABLE reference_publications(id TEXT,dataset TEXT,run_id TEXT,state TEXT,intent_json TEXT,revision INTEGER,updated_at TEXT)");
            s.execute("CREATE TABLE backtest_publication_proofs(publication_id TEXT,run_id TEXT,evidence_path TEXT,evidence_sha256 TEXT)");
            s.execute("CREATE TABLE sync_runs(id TEXT,job_id TEXT,job_version INTEGER,target_id TEXT)");
            s.execute("CREATE TABLE sync_entries(id TEXT,kind TEXT,state TEXT)");
            s.execute("CREATE TABLE sync_interval_locks(dataset_id TEXT,run_id TEXT)");
            try(var insert=db.prepareStatement("INSERT INTO reference_publications VALUES('pub-one','backtest_daily','run-one','VERIFIED',?,1,'2026-10-16')")) {
                insert.setString(1,json.writeValueAsString(intent));insert.executeUpdate();
            }
            try(var insert=db.prepareStatement("INSERT INTO backtest_publication_proofs VALUES('pub-one','run-one',?,?)")) {
                insert.setString(1,proof.toString());insert.setString(2,hash(proof));insert.executeUpdate();
            }
            s.execute("INSERT INTO sync_runs VALUES('run-one','data.backtest_daily',1,'fixture-target')");
            s.execute("INSERT INTO sync_entries VALUES('run-one','RUN','VERIFIED')");
        }
    }
    private org.mockito.MockedConstruction<BacktestDailyMaterializationPort> port() {
        return mockConstruction(BacktestDailyMaterializationPort.class,(mock,context)->{
            when(mock.identity(BASE)).thenReturn(base);when(mock.viewState()).thenReturn(view);when(mock.sourcePin()).thenReturn(pin);
        });
    }
    private String read() { return BacktestDailyMaterializationReadGuard.version(jdbc,BacktestDailyViewDataset.DEFINITION,ledger); }
    private void sql(String command) throws Exception {
        try(var db=DriverManager.getConnection("jdbc:sqlite:"+ledger);var s=db.createStatement()) {s.execute(command);}
    }
    @Test void legacyBaseProofSurvivesRemovalOfTheDuplicateMvWithoutRewritingEvidence() throws Exception {
        byte[] original=Files.readAllBytes(proof);
        try(var ignored=port()) {assertTrue(read().contains(":view:"));}
        assertArrayEquals(original,Files.readAllBytes(proof));
    }
    @Test void currentOwnerVersionUsesTheSameBaseAuthority() throws Exception {
        sql("UPDATE sync_runs SET job_version=2");
        try(var ignored=port()) {assertNotNull(read());}
    }
    @Test void changedBaseOrSourceStillRejectsThePreviousPublication() {
        try(var construction=port()) {
            // First read constructs the port; later reads let the construction callback provide changed state.
            assertNotNull(read());
        }
        try(var construction=mockConstruction(BacktestDailyMaterializationPort.class,(mock,context)->{
            when(mock.identity(BASE)).thenReturn(new Identity(7,"backtest_daily~7",5576,day,day,20,20,20));
            when(mock.viewState()).thenReturn(view);when(mock.sourcePin()).thenReturn(pin);
        })) {assertThrows(IllegalStateException.class,this::read);}
        try(var construction=mockConstruction(BacktestDailyMaterializationPort.class,(mock,context)->{
            when(mock.identity(BASE)).thenReturn(base);when(mock.viewState()).thenReturn(view);when(mock.sourcePin()).thenReturn("b".repeat(64));
        })) {
            var failure=assertThrows(BacktestDailyMaterializationReadGuard.NotReadyException.class,this::read);
            assertEquals(BacktestDailyMaterializationReadGuard.NotReadyException.Reason.SOURCES_CHANGED,failure.reason());
        }
    }
    @Test void mutatedImmutableEvidenceCannotBeUsedEvenWithAnUnchangedBase() throws Exception {
        Files.writeString(proof,"{}");
        try(var ignored=port()) {assertThrows(IllegalStateException.class,this::read);}
    }
    @Test void anActiveWriterStillBlocksReads() throws Exception {
        sql("INSERT INTO sync_interval_locks VALUES('backtest_daily','active-writer')");
        try(var ignored=port()) {
            var failure=assertThrows(BacktestDailyMaterializationReadGuard.NotReadyException.class,this::read);
            assertEquals(BacktestDailyMaterializationReadGuard.NotReadyException.Reason.WRITER_ACTIVE,failure.reason());
        }
    }
    @Test void aViewReplacementDuringTheReadStillFailsClosed() {
        try(var construction=mockConstruction(BacktestDailyMaterializationPort.class,(mock,context)->{
            when(mock.identity(BASE)).thenReturn(base);when(mock.sourcePin()).thenReturn(pin);
            when(mock.viewState()).thenReturn(view,new ViewState(ALIAS,21,"v_backtest_daily~21",VIEW_SQL));
        })) {assertThrows(IllegalStateException.class,this::read);}
    }
    private static String hash(Path file) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
    }
}
