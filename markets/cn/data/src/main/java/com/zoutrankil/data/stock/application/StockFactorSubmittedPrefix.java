package com.zoutrankil.data.stock.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.zoutrankil.data.domain.stock.feature.StockFactor;
import com.zoutrankil.data.domain.stock.feature.StockFactorKey;
import com.zoutrankil.data.service.VerifiedBatchExecutor;

import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;

/** Read-only recovery admission. An acknowledged batch is accepted only after exact current readback. */
public final class StockFactorSubmittedPrefix {
    private StockFactorSubmittedPrefix() {}

    public record Inspection(int submittedRows, int matchingRows, List<StockFactor> missingRows) {
        public Inspection {
            missingRows = List.copyOf(missingRows);
        }
    }

    public static Inspection inspect(
            List<StockFactor> source,
            JsonNode writeResult,
            VerifiedBatchExecutor.Codec<StockFactor, StockFactorKey> codec,
            VerifiedBatchExecutor.Port<StockFactor, StockFactorKey> port)
            throws Exception {
        if (source.isEmpty()
                || source.size() >= StockFactorSource.SOURCE_ROW_CAP
                || !"IN_DOUBT".equals(writeResult.path("status").asText()))
            throw new IllegalArgumentException("Bounded uncertain source page required");
        var receipts = writeResult.path("receipts");
        if (!receipts.isArray() || receipts.isEmpty() || receipts.size() > 40)
            throw new IllegalArgumentException("Bounded batch receipts required");
        int submitted = 0, verified = 0, index = 0;
        for (var receipt : receipts) {
            int n = receipt.path("submittedRows").asInt(-1);
            int v = receipt.path("verifiedRows").asInt(-1);
            if (receipt.path("batch").asInt(-1) != ++index
                    || n < 1
                    || n > 250
                    || submitted + n > source.size()
                    || v != 0 && v != n
                    || index < receipts.size() && v != n)
                throw new IllegalStateException("Invalid contiguous submitted batch prefix");
            var digest = MessageDigest.getInstance("SHA-256");
            int bytes = 0;
            for (var row : source.subList(submitted, submitted + n)) {
                byte[] canonical = codec.canonicalBytes(row);
                digest.update(ByteBuffer.allocate(4).putInt(canonical.length).array());
                digest.update(canonical);
                bytes = Math.addExact(bytes, codec.estimatedTransportBytes(row, canonical));
            }
            if (!HexFormat.of().formatHex(digest.digest()).equals(receipt.path("digest").asText())
                    || bytes != receipt.path("bytes").asInt(-1))
                throw new IllegalStateException(
                        "Submitted batch differs from frozen source values");
            submitted += n;
            verified += v;
        }
        if (submitted != writeResult.path("submittedRows").asInt(-1)
                || verified != writeResult.path("verifiedRows").asInt(-1))
            throw new IllegalStateException("Submitted receipt totals differ");
        port.preflight();
        if (!port.walSettled()) throw new IllegalStateException("Unsettled recovery WAL");
        var unique = new HashSet<StockFactorKey>();
        var missing = new ArrayList<StockFactor>();
        int matching = 0;
        for (int start = 0; start < source.size(); start += 250) {
            int end = Math.min(start + 250, source.size());
            var expected = new LinkedHashMap<StockFactorKey, StockFactor>();
            for (var row : source.subList(start, end)) {
                var key = codec.key(row);
                if (!unique.add(key)) throw new IllegalStateException("Duplicate frozen key");
                expected.put(key, row);
            }
            var found = new HashSet<StockFactorKey>();
            for (var row : port.readback(List.copyOf(expected.keySet()))) {
                var key = codec.key(row);
                if (!found.add(key)
                        || !expected.containsKey(key)
                        || !codec.equivalent(expected.get(key), row))
                    throw new IllegalStateException(
                            "Recovery target has duplicate, unexpected or different values");
                matching++;
            }
            for (int i = start; i < end; i++)
                if (!found.contains(codec.key(source.get(i)))) {
                    if (i < submitted)
                        throw new IllegalStateException(
                                "Previously submitted row is missing; delivery unresolved");
                    missing.add(source.get(i));
                }
        }
        port.preflight();
        if (!port.walSettled()) throw new IllegalStateException("Recovery WAL changed");
        return new Inspection(submitted, matching, missing);
    }
}
