package com.zoutrankil.batch;

import java.nio.file.*;
import java.util.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.jdbc.support.JdbcTransactionManager;

/** Cross-database write protocol. Unknown delivery is reconciled, never automatically resent. */
public final class DurableWriter {
    public enum Delivery { INTENT, UNKNOWN, ACKNOWLEDGED, VERIFIED, BLOCKED }
    public record Intent(String batchId, String instanceId, String target, String owner,
                         String sourceFingerprint, String artifact, long expectedRows) {
        public Intent {
            for (String part : List.of(batchId, instanceId, target, owner, sourceFingerprint, artifact))
                if (part.isBlank()) throw new IllegalArgumentException("Complete write intent required");
            if (!target.matches("jdb_test_[a-z0-9_]+") || expectedRows < 0)
                throw new IllegalArgumentException("Only nonnegative isolated test writes are allowed");
            if (!sourceFingerprint.matches("[a-f0-9]{64}")) throw new IllegalArgumentException("SHA-256 required");
        }
    }
    public record Proof(boolean exactKeysAndValues, boolean batchBoundaryVisible, boolean senderStopped,
                        boolean suspended, String artifact) {
        boolean verified(boolean acknowledged) {
            return exactKeysAndValues && batchBoundaryVisible && (acknowledged || senderStopped)
                    && !suspended && artifact != null && !artifact.isBlank();
        }
    }
    public interface Port {
        void preflight(Intent intent) throws Exception;
        void send(Intent intent) throws Exception;
        /** One bounded probe for this batch's keys/content/boundary; not global WAL equality. */
        Proof inspect(Intent intent) throws Exception;
    }
    private final SqliteLedger ledger;
    private final TransactionTemplate transactions;
    public DurableWriter(SqliteLedger ledger) {
        this.ledger=ledger;
        this.transactions=new TransactionTemplate(new JdbcTransactionManager(Objects.requireNonNull(ledger.jdbc().getDataSource())));
    }
    public Delivery execute(Intent intent, Port port) throws Exception {
        try (var guard = ledger.tryLock("target:"+intent.target()).orElseThrow(() -> new IllegalStateException("Target busy"))) {
            persist(intent);
            Delivery state = delivery(intent.batchId());
            if (state == Delivery.VERIFIED || state == Delivery.BLOCKED) return state;
            if (state == Delivery.INTENT) {
                verifyRetainedArtifact(intent);
                port.preflight(intent);
                guard.requireAlive();
                // Committed UNKNOWN *before* network I/O closes the send/ledger crash gap.
                int claimed = ledger.jdbc().update("UPDATE write_intent SET delivery='UNKNOWN',attempt=attempt+1,updated_at=current_timestamp WHERE batch_id=? AND delivery='INTENT'", intent.batchId());
                if (claimed != 1) throw new IllegalStateException("Write intent no longer sendable");
                guard.requireAlive();
                try { port.send(intent); }
                catch (Exception uncertain) {
                    if (uncertain instanceof InterruptedException) Thread.currentThread().interrupt();
                    String detail=intent.batchId()+":"+uncertain.getClass().getSimpleName()+":"+
                            Objects.toString(uncertain.getMessage(),"").replaceAll("[\\r\\n\\t]"," ");
                    if(detail.length()>512) detail=detail.substring(0,512);
                    ledger.audit(intent.instanceId(), "delivery-unknown", detail);
                    return Delivery.UNKNOWN;
                }
                ledger.jdbc().update("UPDATE write_intent SET delivery='ACKNOWLEDGED',updated_at=current_timestamp WHERE batch_id=? AND delivery='UNKNOWN'", intent.batchId());
                state=Delivery.ACKNOWLEDGED;
            }
            Proof proof=port.inspect(intent);
            if (proof.suspended()) {
                ledger.jdbc().update("UPDATE write_intent SET delivery='BLOCKED',proof=? WHERE batch_id=?", Json.write(proof), intent.batchId());
                return Delivery.BLOCKED;
            }
            if (!proof.verified(state == Delivery.ACKNOWLEDGED)) return state;
            transactions.executeWithoutResult(tx -> {
                ledger.jdbc().update("UPDATE write_intent SET delivery='VERIFIED',proof=?,updated_at=current_timestamp WHERE batch_id=?", Json.write(proof), intent.batchId());
                ledger.jdbc().update("DELETE FROM target_reservation WHERE target=? AND batch_id=?", intent.target(), intent.batchId());
            });
            return Delivery.VERIFIED;
        }
    }
    private void persist(Intent intent) {
        transactions.executeWithoutResult(tx -> {
            ledger.jdbc().update("""
                INSERT INTO write_intent(batch_id,instance_id,target,owner,source_fingerprint,artifact,expected_rows,delivery)
                VALUES(?,?,?,?,?,?,?,'INTENT') ON CONFLICT(batch_id) DO NOTHING
                """, intent.batchId(), intent.instanceId(), intent.target(), intent.owner(), intent.sourceFingerprint(), intent.artifact(), intent.expectedRows());
            var existing=ledger.jdbc().queryForMap("SELECT * FROM write_intent WHERE batch_id=?", intent.batchId());
            if (!intent.instanceId().equals(existing.get("instance_id")) || !intent.target().equals(existing.get("target"))
                    || !intent.owner().equals(existing.get("owner")) || !intent.sourceFingerprint().equals(existing.get("source_fingerprint"))
                    || !intent.artifact().equals(existing.get("artifact")) || intent.expectedRows()!=((Number)existing.get("expected_rows")).longValue())
                throw new IllegalArgumentException("Batch identity reused for different content");
            if (Delivery.VERIFIED.name().equals(existing.get("delivery"))) return;
            ledger.jdbc().update("INSERT INTO target_reservation(target,batch_id) VALUES(?,?) ON CONFLICT(target) DO NOTHING", intent.target(), intent.batchId());
            String owner=ledger.jdbc().queryForObject("SELECT batch_id FROM target_reservation WHERE target=?", String.class, intent.target());
            if (!intent.batchId().equals(owner)) throw new IllegalStateException("Target has unresolved sender: "+owner);
        });
    }
    public Delivery delivery(String batchId) {
        return Delivery.valueOf(ledger.jdbc().queryForObject("SELECT delivery FROM write_intent WHERE batch_id=?", String.class, batchId));
    }
    private void verifyRetainedArtifact(Intent intent) throws Exception {
        var hash=java.security.MessageDigest.getInstance("SHA-256");
        try (var stream=Files.newInputStream(Path.of(intent.artifact()))) {
            byte[] buffer=new byte[65536]; int n;
            while ((n=stream.read(buffer))!=-1) hash.update(buffer,0,n);
        }
        if (!HexFormat.of().formatHex(hash.digest()).equals(intent.sourceFingerprint()))
            throw new IllegalArgumentException("Retained source fingerprint mismatch");
    }
}
