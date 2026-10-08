package com.zoutrankil.batch;

import java.nio.file.*;
import java.util.*;

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
    public DurableWriter(SqliteLedger ledger) {
        this.ledger=Objects.requireNonNull(ledger);
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
                int claimed = ledger.claimWriteUnknown(intent.batchId());
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
                ledger.acknowledgeWrite(intent.batchId());
                state=Delivery.ACKNOWLEDGED;
            }
            Proof proof=port.inspect(intent);
            if (proof.suspended()) {
                ledger.blockWrite(intent.batchId(), Json.write(proof));
                return Delivery.BLOCKED;
            }
            if (!proof.verified(state == Delivery.ACKNOWLEDGED)) return state;
            ledger.verifyWriteAndRelease(intent.batchId(), intent.target(), Json.write(proof));
            return Delivery.VERIFIED;
        }
    }
    private void persist(Intent intent) {
        ledger.reserveWriteIntent(new SqliteLedger.WriteIntentRecord(intent.batchId(), intent.instanceId(),
                intent.target(), intent.owner(), intent.sourceFingerprint(), intent.artifact(), intent.expectedRows()));
    }
    public Delivery delivery(String batchId) {
        return Delivery.valueOf(ledger.writeDelivery(batchId));
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
