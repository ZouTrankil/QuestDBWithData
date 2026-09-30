package com.zoutrankil.data.service;

import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Serial, bounded write batches. An acknowledgement is never a visibility receipt. */
public final class VerifiedBatchExecutor<T, K> {
    public record Policy(int maxRowsPerBatch, int maxBytesPerBatch, int maxBatches,
                         Duration visibilityTimeout, Duration pollInterval) {
        public Policy {
            if (maxRowsPerBatch < 1 || maxRowsPerBatch > 10_000 || maxBytesPerBatch < 1
                    || maxBytesPerBatch > 32 * 1024 * 1024 || maxBatches < 1 || maxBatches > 10_000
                    || visibilityTimeout == null || visibilityTimeout.isNegative() || visibilityTimeout.isZero()
                    || visibilityTimeout.compareTo(Duration.ofMinutes(5)) > 0
                    || pollInterval == null || pollInterval.toMillis() < 1
                    || pollInterval.compareTo(visibilityTimeout) > 0
                    || (long) maxRowsPerBatch * maxBatches > 100_000) {
                throw new IllegalArgumentException("Finite positive write budgets required");
            }
        }
    }
    public enum Status { EMPTY, VERIFIED, PARTIAL, CANCELLED, IN_DOUBT }
    public enum Delivery { ACKNOWLEDGED, ACKNOWLEDGED_UNVERIFIED, UNKNOWN_RECONCILED, UNKNOWN_UNRESOLVED }
    public record Receipt(int batch, int submittedRows, int verifiedRows, int bytes,
                          String digest, Delivery delivery) {}
    public record Result(Status status, int submittedRows, int verifiedRows,
                         List<Receipt> receipts, String reason) {
        public Result { receipts = List.copyOf(receipts); }
    }
    public interface Codec<T, K> {
        /** Full business key, including period/version/category dimensions. */
        K key(T row);
        /** Deterministic encoding of all business columns, including null markers. */
        byte[] canonicalBytes(T row);
        /** Application byte budget including adapter overhead; not an exact wire-byte measurement. */
        default int estimatedTransportBytes(T row, byte[] canonical) { return canonical.length; }
    }
    public interface Port<T, K> {
        /** Must fail before sending if schema, WAL, target or ownership is not ready. */
        void preflight() throws Exception;
        /** One finite batch. An exception after the call begins has unknown delivery. */
        void send(List<T> rows) throws Exception;
        /** Actual rows by the exact requested keys; duplicates must remain observable. */
        List<T> readback(List<K> keys) throws Exception;
        /** True only after the relevant WAL frontier is settled. */
        boolean walSettled() throws Exception;
        /** Explicit proof that an uncertain sender cannot deliver additional writes. */
        default boolean uncertainSenderStopped() throws Exception { return false; }
    }

    private final Policy policy;
    private final Codec<T, K> codec;
    private final Port<T, K> port;

    public VerifiedBatchExecutor(Policy policy, Codec<T, K> codec, Port<T, K> port) {
        this.policy = Objects.requireNonNull(policy);
        this.codec = Objects.requireNonNull(codec);
        this.port = Objects.requireNonNull(port);
    }

    public Result execute(Iterator<T> source) {
        Objects.requireNonNull(source);
        var receipts = new ArrayList<Receipt>();
        var seen = new HashSet<K>();
        T pending = null;
        int submitted = 0, verified = 0;
        for (int batchNumber = 1; batchNumber <= policy.maxBatches(); batchNumber++) {
            if (Thread.currentThread().isInterrupted()) {
                return new Result(Status.CANCELLED, submitted, verified, receipts, "cancelled before next send");
            }
            var rows = new ArrayList<T>();
            var keys = new ArrayList<K>();
            var expected = new HashMap<K, byte[]>();
            int bytes = 0;
            try {
            while (pending != null || source.hasNext()) {
                if (Thread.currentThread().isInterrupted()) {
                    return new Result(Status.CANCELLED, submitted, verified, receipts, "cancelled before send");
                }
                T row = pending != null ? pending : source.next();
                pending = null;
                K key = Objects.requireNonNull(codec.key(row), "complete key required");
                byte[] encoded = Objects.requireNonNull(codec.canonicalBytes(row), "canonical bytes required");
                int estimatedBytes = codec.estimatedTransportBytes(row, encoded);
                if (estimatedBytes < encoded.length || estimatedBytes > policy.maxBytesPerBatch()) {
                    return new Result(Status.PARTIAL, submitted, verified, receipts, "row exceeds byte budget before send");
                }
                if (!rows.isEmpty() && (rows.size() == policy.maxRowsPerBatch()
                        || bytes + estimatedBytes > policy.maxBytesPerBatch())) {
                    pending = row;
                    break;
                }
                if (!seen.add(key)) return new Result(Status.PARTIAL, submitted, verified, receipts,
                        "duplicate source complete key before send");
                rows.add(row);
                keys.add(key);
                expected.put(key, encoded.clone());
                bytes += estimatedBytes;
            }
            } catch (RuntimeException invalidSource) {
                return new Result(Status.PARTIAL, submitted, verified, receipts,
                        "source row or key invalid before send: " + invalidSource.getClass().getSimpleName());
            }
            if (rows.isEmpty()) return new Result(submitted == 0 ? Status.EMPTY : Status.VERIFIED,
                    submitted, verified, receipts, null);
            try {
                port.preflight();
                if (Thread.currentThread().isInterrupted()) {
                    return new Result(Status.CANCELLED, submitted, verified, receipts, "cancelled during preflight");
                }
            } catch (Exception error) {
                if (error instanceof InterruptedException) Thread.currentThread().interrupt();
                return new Result(Status.PARTIAL, submitted, verified, receipts,
                        "pre-send preflight failed: " + error.getClass().getSimpleName());
            }
            String digest = digest(keys, expected);
            boolean acknowledged = true;
            try {
                port.send(List.copyOf(rows));
            } catch (Exception unknown) {
                if (unknown instanceof InterruptedException) Thread.currentThread().interrupt();
                acknowledged = false;
            }
            submitted += rows.size();
            long deadline = System.nanoTime() + policy.visibilityTimeout().toNanos();
            boolean matched = false;
            do {
                try {
                    matched = (acknowledged || port.uncertainSenderStopped())
                            && exactMatch(keys, expected, port.readback(keys)) && port.walSettled();
                } catch (Exception transientReadFailure) {
                    matched = false;
                }
                if (matched || System.nanoTime() >= deadline) break;
                try {
                    Thread.sleep(policy.pollInterval().toMillis());
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    receipts.add(new Receipt(batchNumber, rows.size(), 0, bytes, digest,
                            acknowledged ? Delivery.ACKNOWLEDGED_UNVERIFIED : Delivery.UNKNOWN_UNRESOLVED));
                    return new Result(Status.IN_DOUBT, submitted, verified, receipts,
                            "cancelled after send; reconcile before any replay");
                }
            } while (true);
            if (!matched) {
                receipts.add(new Receipt(batchNumber, rows.size(), 0, bytes, digest,
                        acknowledged ? Delivery.ACKNOWLEDGED_UNVERIFIED : Delivery.UNKNOWN_UNRESOLVED));
                return new Result(Status.IN_DOUBT, submitted, verified, receipts,
                        "batch values or WAL frontier not verified before deadline; do not replay");
            }
            verified += rows.size();
            receipts.add(new Receipt(batchNumber, rows.size(), rows.size(), bytes, digest,
                    acknowledged ? Delivery.ACKNOWLEDGED : Delivery.UNKNOWN_RECONCILED));
            try {
                if (pending == null && !source.hasNext()) {
                    return new Result(Status.VERIFIED, submitted, verified, receipts, null);
                }
            } catch (RuntimeException sourceFailure) {
                return new Result(Status.PARTIAL, submitted, verified, receipts,
                        "source failed after verified batch: " + sourceFailure.getClass().getSimpleName());
            }
        }
        return new Result(Status.PARTIAL, submitted, verified, receipts, "maximum batch count reached");
    }

    private boolean exactMatch(List<K> keys, Map<K, byte[]> expected, List<T> actual) {
        if (actual == null || actual.size() != keys.size()) return false;
        var found = new HashSet<K>();
        for (T row : actual) {
            K key = codec.key(row);
            if (!found.add(key) || !expected.containsKey(key)
                    || !Arrays.equals(expected.get(key), codec.canonicalBytes(row))) return false;
        }
        return found.size() == keys.size();
    }

    private static <K> String digest(List<K> keys, Map<K, byte[]> expected) {
        try {
            var hash = MessageDigest.getInstance("SHA-256");
            for (K key : keys) {
                byte[] value = expected.get(key);
                hash.update(ByteBuffer.allocate(4).putInt(value.length).array());
                hash.update(value);
            }
            return HexFormat.of().formatHex(hash.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
