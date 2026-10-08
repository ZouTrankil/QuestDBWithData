package com.zoutrankil.data.repository;

import com.zoutrankil.data.domain.*;
import org.springframework.jdbc.core.JdbcTemplate;
import java.nio.file.*;
import java.sql.*;
import java.security.MessageDigest;
import java.util.*;
import static com.zoutrankil.data.repository.BacktestDailyMaterializationPort.*;

/** Read-only guard of the fixed public native MV and enriched physical base. SQLite is the publication authority. */
public final class BacktestDailyMaterializationReadGuard {
    private BacktestDailyMaterializationReadGuard() {}
    /** Expected publication gates; integrity and schema failures remain ordinary failures. */
    public static final class NotReadyException extends IllegalStateException {
        public enum Reason { LEDGER_ABSENT, PUBLICATION_PENDING, WRITER_ACTIVE, NOT_PUBLISHED, SOURCES_CHANGED }
        private final Reason reason;
        public NotReadyException(Reason reason,String message){super(message);this.reason=reason;}
        public Reason reason(){return reason;}
    }
    public static boolean applies(DatasetDefinition definition){return BASE.equals(definition.objectName())||MV.equals(definition.objectName());}
    public static String version(JdbcTemplate jdbc,DatasetDefinition definition,Path ledgerPath){
        if(!applies(definition))throw new IllegalArgumentException("Backtest dataset required");
        if(MV.equals(definition.objectName())&&definition.objectKind()!=DatasetDefinition.ObjectKind.MATERIALIZED_VIEW
                ||BASE.equals(definition.objectName())&&definition.objectKind()!=DatasetDefinition.ObjectKind.TABLE)throw new IllegalStateException("Backtest requires its actual registered native MV or enriched TABLE");
        Path ledger=ledgerPath.toAbsolutePath().normalize();if(!Files.isRegularFile(ledger))throw new NotReadyException(NotReadyException.Reason.LEDGER_ABSENT,"Backtest durable publication ledger absent");
        var port=new BacktestDailyMaterializationPort(jdbc);
        try(var db=DriverManager.getConnection("jdbc:sqlite:"+ledger.toUri().toASCIIString()+"?mode=ro");var s=db.createStatement()){
            s.execute("PRAGMA query_only=ON");s.execute("PRAGMA busy_timeout=5000");db.setAutoCommit(false);
            try(var r=s.executeQuery("SELECT count(*) FROM sqlite_master WHERE type='table' AND name IN ('reference_publications','backtest_publication_proofs','sync_interval_locks','sync_runs','sync_entries')")){if(!r.next()||r.getInt(1)!=5)throw new IllegalStateException("Backtest publication authority schema absent");}
            try(var r=s.executeQuery("SELECT run_id FROM reference_publications WHERE dataset='backtest_daily' AND state<>'VERIFIED' LIMIT 1")){if(r.next())throw new NotReadyException(NotReadyException.Reason.PUBLICATION_PENDING,"Backtest publication is unresolved: "+r.getString(1));}
            try(var r=s.executeQuery("SELECT run_id FROM sync_interval_locks WHERE dataset_id='backtest_daily' LIMIT 1")){if(r.next())throw new NotReadyException(NotReadyException.Reason.WRITER_ACTIVE,"Backtest owning writer/uncertain lease is active");}
            var base=port.identity(BASE);var nativeState=port.nativeState(MV);String pin=port.sourcePin();
            String pub,run,intentText,evidence,evidenceSha;long revision;
            try(var r=s.executeQuery("SELECT p.id,p.run_id,p.intent_json,p.revision,q.evidence_path,q.evidence_sha256,r.job_id,r.job_version,r.target_id,e.state "
                    +"FROM reference_publications p JOIN backtest_publication_proofs q ON q.publication_id=p.id AND q.run_id=p.run_id "
                    +"JOIN sync_runs r ON r.id=p.run_id JOIN sync_entries e ON e.id=p.run_id AND e.kind='RUN' "
                    +"WHERE p.dataset='backtest_daily' AND p.state='VERIFIED' ORDER BY p.updated_at DESC,p.id DESC LIMIT 1")){
                if(!r.next())throw new NotReadyException(NotReadyException.Reason.NOT_PUBLISHED,"No completed original Java backtest owner publication");
                if(!"data.backtest_daily".equals(r.getString("job_id"))||r.getInt("job_version")!=1||!"VERIFIED".equals(r.getString("state")))throw new IllegalStateException("Completed backtest publication disagrees with its original Java owner");
                pub=r.getString("id");run=r.getString("run_id");intentText=r.getString("intent_json");revision=r.getLong("revision");evidence=r.getString("evidence_path");evidenceSha=r.getString("evidence_sha256");
                var intent=JobDefinitionJson.mapper().readValue(intentText,ReferencePublicationJournal.Intent.class);if(!run.equals(intent.runId())||!BASE.equals(intent.dataset())||!BASE.equals(intent.target())||!r.getString("target_id").equals(intent.initialTarget())||base.id()!=intent.replacementId()||nativeState.baseId()!=base.id())throw new IllegalStateException("Published physical base/native binding differs");
            }
            var json=JobDefinitionJson.mapper();var intent=json.readValue(intentText,ReferencePublicationJournal.Intent.class);var scope=json.readTree(intent.scope());Path owned=ledger.getParent().resolve("sync-evidence").resolve(run).normalize();
            Path source=Path.of(scope.path("sourceEvidence").asText()).toAbsolutePath().normalize(),proofPath=Path.of(evidence).toAbsolutePath().normalize();
            if(!source.startsWith(owned)||!proofPath.equals(owned.resolve("publication.json"))||!Files.isRegularFile(source)||!Files.isRegularFile(proofPath)||Files.size(source)>4*1024*1024||Files.size(proofPath)>2*1024*1024||!hash(source).equals(scope.path("sourceEvidenceSha256").asText())||!hash(proofPath).equals(evidenceSha))throw new IllegalStateException("Immutable backtest source/final attestation is absent or changed");
            var proof=json.readTree(proofPath.toFile());
            if(!scope.path("sourcePin").asText().equals(proof.path("sourcePin").asText())||!scope.path("sourceEvidenceSha256").asText().equals(proof.path("sourceEvidenceSha256").asText())||!json.writeValueAsString(base).equals(proof.path("base").toString())||!json.writeValueAsString(nativeState).equals(proof.path("native").toString()))throw new IllegalStateException("Backtest base/native or immutable evidence changed after verified publication");
            if(!pin.equals(scope.path("sourcePin").asText()))throw new NotReadyException(NotReadyException.Reason.SOURCES_CHANGED,"Backtest sources changed; a verified backtest materialization is required before reading");
            if(!base.equals(port.identity(BASE))||!nativeState.equals(port.nativeState(MV))||!pin.equals(port.sourcePin()))throw new IllegalStateException("Backtest publication changed during guard verification");
            return "backtest-publication:"+pub+":"+revision+":run:"+run+":source:"+pin+":base:"+base.fingerprint()+":native:"+sha(json.writeValueAsString(nativeState))+":proof:"+evidenceSha;
        }catch(IllegalStateException failure){throw failure;}catch(Exception failure){throw new IllegalStateException("Cannot verify current backtest publication authority",failure);}
    }
    private static String hash(Path file)throws Exception{var digest=MessageDigest.getInstance("SHA-256");try(var input=Files.newInputStream(file)){byte[] buffer=new byte[65536];for(int n;(n=input.read(buffer))>=0;)digest.update(buffer,0,n);}return HexFormat.of().formatHex(digest.digest());}
}
