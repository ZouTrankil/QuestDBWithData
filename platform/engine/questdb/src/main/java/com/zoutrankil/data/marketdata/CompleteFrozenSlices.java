package com.zoutrankil.data.marketdata;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zoutrankil.data.domain.DatasetDefinition;
import com.zoutrankil.data.domain.SyncRunState;
import com.zoutrankil.data.repository.SyncRunLedger;

import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** 从原始提交事件重建全部冻结页；缺页、游标漂移或未发送尾部均不能按完整来源恢复。 */
final class CompleteFrozenSlices {
    private static final ObjectMapper JSON = new ObjectMapper();

    record Slice(SyncRunLedger.Entry entry, int offset, int rows) {}

    static List<Slice> require(
            SyncRunLedger ledger,
            String run,
            Path prepared,
            DatasetDefinition definition,
            List<Map<String, Object>> rows,
            String sourceSha,
            VerifiedMarketDataStore.PublicationPage page,
            List<SyncRunLedger.Entry> entries)
            throws Exception {
        var roots = entries.stream().filter(e -> e.kind() == SyncRunLedger.Kind.RUN).toList();
        var attempts =
                entries.stream().filter(e -> e.kind() == SyncRunLedger.Kind.ATTEMPT).toList();
        var slices = entries.stream().filter(e -> e.kind() == SyncRunLedger.Kind.SLICE).toList();
        if (roots.size() != 1
                || !roots.getFirst().id().equals(run)
                || roots.getFirst().parentId() != null
                || !Set.of(SyncRunState.RUNNING, SyncRunState.IN_DOUBT, SyncRunState.VERIFIED)
                        .contains(roots.getFirst().state())
                || attempts.size() != 1
                || !run.equals(attempts.getFirst().parentId())
                || !Set.of(SyncRunState.RUNNING, SyncRunState.IN_DOUBT, SyncRunState.VERIFIED)
                        .contains(attempts.getFirst().state())
                || slices.size() != (rows.size() + page.rows() - 1) / page.rows())
            throw new IllegalStateException("原账本必须覆盖同一次尝试的全部冻结分页");
        var codec = VerifiedMarketDataStore.codec(definition);
        var result = new TreeMap<Integer, Slice>();
        for (var entry : slices) {
            if (!run.equals(entry.runId())
                    || !attempts.getFirst().id().equals(entry.parentId())
                    || !Set.of(SyncRunState.IN_DOUBT, SyncRunState.VERIFIED)
                            .contains(entry.state()))
                throw new IllegalStateException("存在未发送或不属于原尝试的分页");
            var events = ledger.events(entry.id(), -1, 100);
            var latest = events.getLast();
            if (latest.revision() != entry.revision()
                    || latest.state() != entry.state()
                    || !latest.payloadJson().equals(entry.payloadJson()))
                throw new IllegalStateException("原分页当前修订与不可变事件不符");
            var fetched = event(events, 2, SyncRunState.FETCHED);
            var source = JSON.readTree(fetched.payloadJson());
            int offset;
            try {
                offset = Integer.parseInt(source.path("cursor").asText());
            } catch (NumberFormatException e) {
                throw new IllegalStateException("原分页游标非法", e);
            }
            int count = Math.min(page.rows(), rows.size() - offset);
            if (offset < 0
                    || offset >= rows.size()
                    || offset % page.rows() != 0
                    || !Integer.toString(offset).equals(source.path("cursor").asText())
                    || source.path("returnedRows").asInt(-1) != count
                    || !sourceSha.equals(source.path("sourceFingerprint").asText())
                    || !prepared.equals(
                            Path.of(source.path("responseEvidence").asText())
                                    .toAbsolutePath()
                                    .normalize())
                    || !fetched.payloadJson()
                            .equals(event(events, 3, SyncRunState.VALIDATED).payloadJson())
                    || !fetched.payloadJson()
                            .equals(event(events, 4, SyncRunState.SUBMITTED).payloadJson())
                    || result.put(offset, new Slice(entry, offset, count)) != null)
                throw new IllegalStateException("原冻结分页缺失、重复或来源事件不符");
            ledger.requireUncancelledRevision4SubmissionEvent(
                    run, entry.id(), fetched.payloadJson());
            // 发送页可能按字节再次拆批，逐批重算原编码、边界和摘要，不能只相加行数。
            var written = events.stream().filter(e -> e.revision() == 5).findFirst().orElseThrow();
            if (!Set.of(SyncRunState.ACKNOWLEDGED, SyncRunState.IN_DOUBT).contains(written.state()))
                throw new IllegalStateException("原发送结果不明");
            var write = JSON.readTree(written.payloadJson()).path("writeResult");
            var receipts = write.path("receipts");
            int cursor = offset, batch = 0;
            for (var receipt : receipts) {
                int first = cursor, bytes = 0;
                var hash = MessageDigest.getInstance("SHA-256");
                while (cursor < offset + count) {
                    byte[] value = codec.canonicalBytes(rows.get(cursor));
                    if (value.length > page.bytes()) throw new IllegalStateException("原行超过发送字节预算");
                    if (cursor > first
                            && (cursor - first == page.rows()
                                    || bytes + value.length > page.bytes())) break;
                    bytes += value.length;
                    hash.update(ByteBuffer.allocate(4).putInt(value.length).array());
                    hash.update(value);
                    cursor++;
                }
                if (cursor == first
                        || receipt.path("batch").asInt() != ++batch
                        || receipt.path("submittedRows").asInt() != cursor - first
                        || receipt.path("bytes").asInt() != bytes
                        || !receipt.path("digest")
                                .asText()
                                .equals(HexFormat.of().formatHex(hash.digest()))
                        || !Set.of(
                                        "ACKNOWLEDGED",
                                        "ACKNOWLEDGED_UNVERIFIED",
                                        "UNKNOWN_RECONCILED",
                                        "UNKNOWN_UNRESOLVED")
                                .contains(receipt.path("delivery").asText()))
                    throw new IllegalStateException("原分页发送回执与冻结内容不符");
            }
            if (!receipts.isArray()
                    || batch == 0
                    || cursor != offset + count
                    || write.path("submittedRows").asInt() != count)
                throw new IllegalStateException("原分页尚未全部发送");
            if (entry.state() == SyncRunState.VERIFIED)
                requireProof(JSON.readTree(entry.payloadJson()), count, sourceSha);
        }
        for (int offset = 0; offset < rows.size(); offset += page.rows())
            if (!result.containsKey(offset)) throw new IllegalStateException("完整来源中存在未发送页");
        if (attempts.getFirst().state() == SyncRunState.VERIFIED)
            requireProof(JSON.readTree(attempts.getFirst().payloadJson()), rows.size(), sourceSha);
        if (roots.getFirst().state() == SyncRunState.VERIFIED)
            requireProof(JSON.readTree(roots.getFirst().payloadJson()), rows.size(), sourceSha);
        return List.copyOf(result.values());
    }

    private static SyncRunLedger.Event event(
            List<SyncRunLedger.Event> events, int revision, SyncRunState state) {
        return events.stream()
                .filter(e -> e.revision() == revision && e.state() == state)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("原分页来源事件缺失"));
    }

    private static void requireProof(JsonNode document, int rows, String sourceSha) {
        var p = document.path("verification");
        if (!p.path("passed").asBoolean()
                || p.path("expectedRows").asInt() != rows
                || p.path("actualRows").asInt() != rows
                || p.path("matchedRows").asInt() != rows
                || p.path("missingKeys").asInt(-1) != 0
                || p.path("duplicateKeys").asInt(-1) != 0
                || p.path("mismatchedRows").asInt(-1) != 0
                || !sourceSha.equals(p.path("sourceFingerprint").asText())
                || p.path("readbackEvidence").asText().isBlank())
            throw new IllegalStateException("原成功分页的完整回读证明不符");
    }
}
