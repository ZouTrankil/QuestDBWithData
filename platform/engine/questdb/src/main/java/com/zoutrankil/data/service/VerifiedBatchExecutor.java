package com.zoutrankil.data.service;

import java.nio.ByteBuffer;
import java.nio.file.Path;
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

/** 串行执行有界写入批次；收到发送确认不等于数据已可读取。 */
public final class VerifiedBatchExecutor<T, K> {
    public record Policy(
            int maxRowsPerBatch,
            int maxBytesPerBatch,
            int maxBatches,
            Duration visibilityTimeout,
            Duration pollInterval) {
        public Policy {
            if (maxRowsPerBatch < 1
                    || maxRowsPerBatch > 10_000
                    || maxBytesPerBatch < 1
                    || maxBytesPerBatch > 32 * 1024 * 1024
                    || maxBatches < 1
                    || maxBatches > 10_000
                    || visibilityTimeout == null
                    || visibilityTimeout.isNegative()
                    || visibilityTimeout.isZero()
                    || visibilityTimeout.compareTo(Duration.ofMinutes(5)) > 0
                    || pollInterval == null
                    || pollInterval.toMillis() < 1
                    || pollInterval.compareTo(visibilityTimeout) > 0
                    || (long) maxRowsPerBatch * maxBatches > 100_000) {
                throw new IllegalArgumentException("Finite positive write budgets required");
            }
        }
    }

    public enum Status {
        EMPTY,
        VERIFIED,
        PARTIAL,
        CANCELLED,
        IN_DOUBT
    }

    public enum Delivery {
        ACKNOWLEDGED,
        ACKNOWLEDGED_UNVERIFIED,
        UNKNOWN_RECONCILED,
        UNKNOWN_UNRESOLVED
    }

    public record Receipt(
            int batch,
            int submittedRows,
            int verifiedRows,
            int bytes,
            String digest,
            Delivery delivery) {}

    public record Result(
            Status status,
            int submittedRows,
            int verifiedRows,
            List<Receipt> receipts,
            String reason) {
        public Result {
            receipts = List.copyOf(receipts);
        }
    }

    public interface Codec<T, K> {
        /** 完整业务键必须包含报告期、版本、类别等维度。 */
        K key(T row);

        /** 确定性编码全部业务列，包含显式的空值标记。 */
        byte[] canonicalBytes(T row);

        /** 按数据集规则比较值；普通写入默认仍要求编码字节完全一致。 */
        default boolean equivalent(T expected, T actual) {
            return Arrays.equals(canonicalBytes(expected), canonicalBytes(actual));
        }

        /** 应用侧字节预算包含适配器开销，不等于精确的网络报文大小。 */
        default int estimatedTransportBytes(T row, byte[] canonical) {
            return canonical.length;
        }
    }

    /** 共享 SQLite 提交事务完成后，才向执行方提供此上下文。 */
    public record Submission(
            Path ledgerPath,
            String runId,
            String sliceId,
            long revision,
            String sourceFingerprint) {
        public Submission {
            ledgerPath = Objects.requireNonNull(ledgerPath).toAbsolutePath().normalize();
            if (runId == null
                    || runId.isBlank()
                    || sliceId == null
                    || sliceId.isBlank()
                    || revision < 1
                    || sourceFingerprint == null
                    || sourceFingerprint.isBlank())
                throw new IllegalArgumentException("Complete durable submission context required");
        }
    }

    public interface Port<T, K> {
        /** 委托方启动外部发布前，须独立核验此 SQLite 账本。 */
        default void submissionRecorded(Submission submission) throws Exception {}

        /** 表结构、WAL、目标身份或占用归属未准备好时，必须在发送前失败。 */
        void preflight() throws Exception;

        /** 只发送一个有限批次；调用开始后若抛出异常，投递结果视为未决。 */
        void send(List<T> rows) throws Exception;

        /** 按请求的准确业务键读取实际行；不能在接口内隐藏重复记录。 */
        List<T> readback(List<K> keys) throws Exception;

        /** 显式限定检查点复核容量；现有存储端口默认使用保守上限。 */
        default int recoveryReadbackBatchRows() {
            return 100;
        }

        /** 只有相关 WAL 进度已经稳定时，才返回 true。 */
        boolean walSettled() throws Exception;

        /** 显式证明结果未决的发送方不会继续追加写入。 */
        default boolean uncertainSenderStopped() throws Exception {
            return false;
        }
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
                return new Result(
                        Status.CANCELLED,
                        submitted,
                        verified,
                        receipts,
                        "cancelled before next send");
            }
            var rows = new ArrayList<T>();
            var keys = new ArrayList<K>();
            var expected = new HashMap<K, byte[]>();
            var expectedRows = new HashMap<K, T>();
            int bytes = 0;
            try {
                while (pending != null || source.hasNext()) {
                    if (Thread.currentThread().isInterrupted()) {
                        return new Result(
                                Status.CANCELLED,
                                submitted,
                                verified,
                                receipts,
                                "cancelled before send");
                    }
                    T row = pending != null ? pending : source.next();
                    pending = null;
                    K key = Objects.requireNonNull(codec.key(row), "complete key required");
                    byte[] encoded =
                            Objects.requireNonNull(
                                    codec.canonicalBytes(row), "canonical bytes required");
                    int estimatedBytes = codec.estimatedTransportBytes(row, encoded);
                    if (estimatedBytes < encoded.length
                            || estimatedBytes > policy.maxBytesPerBatch()) {
                        return new Result(
                                Status.PARTIAL,
                                submitted,
                                verified,
                                receipts,
                                "row exceeds byte budget before send");
                    }
                    if (!rows.isEmpty()
                            && (rows.size() == policy.maxRowsPerBatch()
                                    || bytes + estimatedBytes > policy.maxBytesPerBatch())) {
                        pending = row;
                        break;
                    }
                    if (!seen.add(key))
                        return new Result(
                                Status.PARTIAL,
                                submitted,
                                verified,
                                receipts,
                                "duplicate source complete key before send");
                    rows.add(row);
                    keys.add(key);
                    expected.put(key, encoded.clone());
                    expectedRows.put(key, row);
                    bytes += estimatedBytes;
                }
            } catch (RuntimeException invalidSource) {
                return new Result(
                        Status.PARTIAL,
                        submitted,
                        verified,
                        receipts,
                        "source row or key invalid before send: "
                                + invalidSource.getClass().getSimpleName());
            }
            if (rows.isEmpty())
                return new Result(
                        submitted == 0 ? Status.EMPTY : Status.VERIFIED,
                        submitted,
                        verified,
                        receipts,
                        null);
            try {
                port.preflight();
                if (Thread.currentThread().isInterrupted()) {
                    return new Result(
                            Status.CANCELLED,
                            submitted,
                            verified,
                            receipts,
                            "cancelled during preflight");
                }
            } catch (Exception error) {
                if (error instanceof InterruptedException) Thread.currentThread().interrupt();
                return new Result(
                        Status.PARTIAL,
                        submitted,
                        verified,
                        receipts,
                        "pre-send preflight failed: " + error.getClass().getSimpleName());
            }
            String digest = digest(keys, expected);
            // 发送后不再走普通失败重试：异常可能发生在服务端已接收数据之后。
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
                    // 三个条件缺一不可：发送已确认或已停止、业务键和值精确匹配、相关 WAL 已稳定。
                    matched =
                            (acknowledged || port.uncertainSenderStopped())
                                    && exactMatch(keys, expectedRows, port.readback(keys))
                                    && port.walSettled();
                } catch (Exception transientReadFailure) {
                    matched = false;
                }
                if (matched || System.nanoTime() >= deadline) break;
                try {
                    Thread.sleep(policy.pollInterval().toMillis());
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    receipts.add(
                            new Receipt(
                                    batchNumber,
                                    rows.size(),
                                    0,
                                    bytes,
                                    digest,
                                    acknowledged
                                            ? Delivery.ACKNOWLEDGED_UNVERIFIED
                                            : Delivery.UNKNOWN_UNRESOLVED));
                    return new Result(
                            Status.IN_DOUBT,
                            submitted,
                            verified,
                            receipts,
                            "cancelled after send; reconcile before any replay");
                }
            } while (true);
            if (!matched) {
                receipts.add(
                        new Receipt(
                                batchNumber,
                                rows.size(),
                                0,
                                bytes,
                                digest,
                                acknowledged
                                        ? Delivery.ACKNOWLEDGED_UNVERIFIED
                                        : Delivery.UNKNOWN_UNRESOLVED));
                return new Result(
                        Status.IN_DOUBT,
                        submitted,
                        verified,
                        receipts,
                        "batch values or WAL frontier not verified before deadline; do not replay");
            }
            verified += rows.size();
            receipts.add(
                    new Receipt(
                            batchNumber,
                            rows.size(),
                            rows.size(),
                            bytes,
                            digest,
                            acknowledged ? Delivery.ACKNOWLEDGED : Delivery.UNKNOWN_RECONCILED));
            try {
                if (pending == null && !source.hasNext()) {
                    return new Result(Status.VERIFIED, submitted, verified, receipts, null);
                }
            } catch (RuntimeException sourceFailure) {
                return new Result(
                        Status.PARTIAL,
                        submitted,
                        verified,
                        receipts,
                        "source failed after verified batch: "
                                + sourceFailure.getClass().getSimpleName());
            }
        }
        return new Result(
                Status.PARTIAL, submitted, verified, receipts, "maximum batch count reached");
    }

    /** 行数相等仍可能包含重复键或错误值，因此逐键核对，且每个预期键只能出现一次。 */
    private boolean exactMatch(List<K> keys, Map<K, T> expected, List<T> actual) {
        if (actual == null || actual.size() != keys.size()) return false;
        var found = new HashSet<K>();
        for (T row : actual) {
            K key = codec.key(row);
            if (!found.add(key)
                    || !expected.containsKey(key)
                    || !codec.equivalent(expected.get(key), row)) return false;
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
