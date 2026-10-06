package com.zoutrankil.data.service;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class VerifiedBatchExecutorTest {
    private record Row(String key, String value) {}
    private final VerifiedBatchExecutor.Codec<Row, String> codec = new VerifiedBatchExecutor.Codec<>() {
        public String key(Row row) { return row.key(); }
        public byte[] canonicalBytes(Row row) {
            return (row.key() + "\u0000" + row.value()).getBytes(StandardCharsets.UTF_8);
        }
    };
    private VerifiedBatchExecutor.Policy policy() {
        return new VerifiedBatchExecutor.Policy(2, 100, 3, Duration.ofMillis(15), Duration.ofMillis(1));
    }
    private static class Port implements VerifiedBatchExecutor.Port<Row, String> {
        final List<Row> stored = new ArrayList<>();
        int sends;
        boolean throwAfterStore;
        boolean settled = true;
        boolean partialSecond;
        boolean stopped = true;
        public void preflight() {}
        public void send(List<Row> rows) {
            sends++;
            if (partialSecond && sends == 2) stored.add(rows.getFirst());
            else stored.addAll(rows);
            if (throwAfterStore) throw new IllegalStateException("ack lost");
        }
        public List<Row> readback(List<String> keys) {
            return stored.stream().filter(r -> keys.contains(r.key())).toList();
        }
        public boolean walSettled() { return settled; }
        public boolean uncertainSenderStopped() { return stopped; }
    }

    @Test void emptyInputNeverSends() {
        var port = new Port();
        var result = new VerifiedBatchExecutor<>(policy(), codec, port).execute(List.<Row>of().iterator());
        assertEquals(VerifiedBatchExecutor.Status.EMPTY, result.status());
        assertEquals(0, port.sends);
    }
    @Test void boundedBatchesRequireExactValuesAndWal() {
        var port = new Port();
        var result = new VerifiedBatchExecutor<>(policy(), codec, port).execute(List.of(
                new Row("a", "1"), new Row("b", "2"), new Row("c", "3")).iterator());
        assertEquals(VerifiedBatchExecutor.Status.VERIFIED, result.status());
        assertEquals(3, result.verifiedRows());
        assertEquals(2, port.sends);
        assertEquals(2, result.receipts().size());
        assertEquals(64, result.receipts().getFirst().digest().length());
    }
    @Test void lostAcknowledgementCanOnlyBeReconciledByReadback() {
        var port = new Port(); port.throwAfterStore = true;
        var result = new VerifiedBatchExecutor<>(policy(), codec, port).execute(
                List.of(new Row("a", "1")).iterator());
        assertEquals(VerifiedBatchExecutor.Status.VERIFIED, result.status());
        assertEquals(VerifiedBatchExecutor.Delivery.UNKNOWN_RECONCILED, result.receipts().getFirst().delivery());
    }
    @Test void partialSecondBatchStaysInDoubtWithFirstReceiptIntact() {
        var port = new Port(); port.partialSecond = true;
        var result = new VerifiedBatchExecutor<>(policy(), codec, port).execute(List.of(
                new Row("a", "1"), new Row("b", "2"), new Row("c", "3"), new Row("d", "4")).iterator());
        assertEquals(VerifiedBatchExecutor.Status.IN_DOUBT, result.status());
        assertEquals(4, result.submittedRows());
        assertEquals(2, result.verifiedRows());
        assertEquals(VerifiedBatchExecutor.Delivery.ACKNOWLEDGED_UNVERIFIED, result.receipts().getLast().delivery());
    }
    @Test void matchingReadbackCannotReconcileSenderThatMayStillWrite() {
        var port = new Port(); port.throwAfterStore = true; port.stopped = false;
        var result = new VerifiedBatchExecutor<>(policy(), codec, port).execute(
                List.of(new Row("a", "1")).iterator());
        assertEquals(VerifiedBatchExecutor.Status.IN_DOUBT, result.status());
        assertEquals(0, result.verifiedRows());
        assertEquals(1, port.sends);
        assertEquals(VerifiedBatchExecutor.Delivery.UNKNOWN_UNRESOLVED,
                result.receipts().getFirst().delivery());
    }
    @Test void unsettledWalAndDuplicateKeyCannotBeVerified() {
        var port = new Port(); port.settled = false;
        var result = new VerifiedBatchExecutor<>(policy(), codec, port).execute(
                List.of(new Row("a", "1")).iterator());
        assertEquals(VerifiedBatchExecutor.Status.IN_DOUBT, result.status());
        var other = new Port();
        var duplicate = new VerifiedBatchExecutor<>(policy(), codec, other).execute(
                List.of(new Row("a", "1"), new Row("a", "2")).iterator());
        assertEquals(VerifiedBatchExecutor.Status.PARTIAL, duplicate.status());
        assertEquals(0, other.sends);
    }
    @Test void equalCountsWithWrongValueRemainInDoubt() {
        var port = new Port() {
            @Override public List<Row> readback(List<String> keys) {
                return List.of(new Row("a", "modified"));
            }
        };
        var result = new VerifiedBatchExecutor<>(policy(), codec, port).execute(
                List.of(new Row("a", "original")).iterator());
        assertEquals(VerifiedBatchExecutor.Status.IN_DOUBT, result.status());
        assertEquals(0, result.verifiedRows());
    }
    @Test void transportByteEstimateAndBatchBudgetAreEnforcedBeforeSend() {
        var port = new Port();
        var expanded = new VerifiedBatchExecutor.Codec<Row, String>() {
            public String key(Row row) { return row.key(); }
            public byte[] canonicalBytes(Row row) { return codec.canonicalBytes(row); }
            public int estimatedTransportBytes(Row row, byte[] canonical) { return 80; }
        };
        var tooLarge = new VerifiedBatchExecutor<>(
                new VerifiedBatchExecutor.Policy(2, 60, 2, Duration.ofMillis(15), Duration.ofMillis(1)),
                expanded, port).execute(List.of(new Row("a", "1")).iterator());
        assertEquals(VerifiedBatchExecutor.Status.PARTIAL, tooLarge.status());
        assertEquals(0, port.sends);
        var split = new VerifiedBatchExecutor<>(
                new VerifiedBatchExecutor.Policy(2, 100, 2, Duration.ofMillis(15), Duration.ofMillis(1)),
                expanded, port).execute(List.of(new Row("a", "1"), new Row("b", "2")).iterator());
        assertEquals(VerifiedBatchExecutor.Status.VERIFIED, split.status());
        assertEquals(2, port.sends);
        assertEquals(80, split.receipts().getFirst().bytes());
    }
    @Test void invalidSourceRowFailsBeforeTransport() {
        var port = new Port();
        var invalid = new VerifiedBatchExecutor<>(policy(), codec, port).execute(
                List.of(new Row("a", "1"), new Row(null, "2")).iterator());
        assertEquals(VerifiedBatchExecutor.Status.PARTIAL, invalid.status());
        assertEquals(0, port.sends);
    }
    @Test void sourceFailureAfterVerifiedBatchPreservesReceipt() {
        var port = new Port();
        var source = new java.util.Iterator<Row>() {
            boolean consumed;
            public boolean hasNext() {
                if (port.sends > 0) throw new IllegalStateException("source disconnected");
                return !consumed;
            }
            public Row next() { consumed = true; return new Row("a", "1"); }
        };
        var result = new VerifiedBatchExecutor<>(policy(), codec, port).execute(source);
        assertEquals(VerifiedBatchExecutor.Status.PARTIAL, result.status());
        assertEquals(1, result.verifiedRows());
        assertEquals(1, result.receipts().size());
        assertEquals(VerifiedBatchExecutor.Delivery.ACKNOWLEDGED, result.receipts().getFirst().delivery());
    }
    @Test void cancellationDuringPreflightCannotStartTransport() {
        var port = new Port() {
            @Override public void preflight() { Thread.currentThread().interrupt(); }
        };
        try {
            var result = new VerifiedBatchExecutor<>(policy(), codec, port).execute(
                    List.of(new Row("a", "1")).iterator());
            assertEquals(VerifiedBatchExecutor.Status.CANCELLED, result.status());
            assertEquals(0, port.sends);
        } finally { Thread.interrupted(); }
    }
    @Test void cancellationBeforeSendAndAfterSendHaveDifferentOutcomes() {
        var before = new Port();
        Thread.currentThread().interrupt();
        try {
            var result = new VerifiedBatchExecutor<>(policy(), codec, before).execute(
                    List.of(new Row("a", "1")).iterator());
            assertEquals(VerifiedBatchExecutor.Status.CANCELLED, result.status());
            assertEquals(0, before.sends);
        } finally { Thread.interrupted(); }
        var after = new Port() {
            @Override public List<Row> readback(List<String> keys) {
                Thread.currentThread().interrupt();
                return List.of();
            }
        };
        try {
            var result = new VerifiedBatchExecutor<>(policy(), codec, after).execute(
                    List.of(new Row("a", "1")).iterator());
            assertEquals(VerifiedBatchExecutor.Status.IN_DOUBT, result.status());
            assertEquals(1, after.sends);
        } finally { Thread.interrupted(); }
    }
    private VerifiedBatchExecutor.Codec<Row, String> tolerantCodec() {
        return new VerifiedBatchExecutor.Codec<>() {
            public String key(Row row) { return codec.key(row); }
            public byte[] canonicalBytes(Row row) { return codec.canonicalBytes(row); }
            @Override public boolean equivalent(Row expected, Row actual) {
                double left = Double.parseDouble(expected.value()), right = Double.parseDouble(actual.value());
                return java.util.Objects.equals(expected.key(), actual.key()) && Double.isFinite(left)
                        && Double.isFinite(right) && Math.abs(left - right) <= 1e-8;
            }
        };
    }

    @Test void defaultComparisonRejectsEvenSmallNumericReadbackDifferences() {
        var source = new Row("a", "1.0");
        var port = new Port() {
            @Override public List<Row> readback(List<String> keys) {
                return List.of(new Row("a", "1.000000001"));
            }
        };
        var result = new VerifiedBatchExecutor<>(policy(), codec, port).execute(List.of(source).iterator());
        assertEquals(VerifiedBatchExecutor.Status.IN_DOUBT, result.status());
        assertEquals(0, result.verifiedRows());
        assertEquals(1, port.sends);
    }

    @Test void optInToleranceAcceptsRoundingOnlyAndPreservesTheSourceDigest() {
        var source = new Row("a", "1.0");
        var exact = new VerifiedBatchExecutor<>(policy(), codec, new Port()).execute(List.of(source).iterator());
        var rounded = new Port() {
            @Override public List<Row> readback(List<String> keys) {
                return List.of(new Row("a", "1.000000001"));
            }
        };
        var result = new VerifiedBatchExecutor<>(policy(), tolerantCodec(), rounded)
                .execute(List.of(source).iterator());
        assertEquals(VerifiedBatchExecutor.Status.VERIFIED, result.status());
        assertEquals(1, result.verifiedRows());
        assertEquals(1, rounded.sends);
        assertEquals(exact.receipts().getFirst().digest(), result.receipts().getFirst().digest(),
                "Tolerance must not replace the canonical source receipt with rounded readback values");
    }

    @Test void optInToleranceStillRejectsOutOfToleranceValuesAndDifferentKeys() {
        for (Row actual : List.of(new Row("a", "1.001"), new Row("b", "1.000000001"))) {
            var port = new Port() {
                @Override public List<Row> readback(List<String> keys) { return List.of(actual); }
            };
            var result = new VerifiedBatchExecutor<>(policy(), tolerantCodec(), port)
                    .execute(List.of(new Row("a", "1.0")).iterator());
            assertEquals(VerifiedBatchExecutor.Status.IN_DOUBT, result.status());
            assertEquals(0, result.verifiedRows());
            assertEquals(1, port.sends);
        }
    }
}
