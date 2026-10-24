package com.zoutrankil.data.margin.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.zoutrankil.data.domain.JobDefinitionJson;
import com.zoutrankil.data.domain.SyncRequestIdentity;
import com.zoutrankil.data.domain.SyncRunState;
import com.zoutrankil.data.margin.port.MarginAllTables;
import com.zoutrankil.data.repository.FileEvidenceStore;
import com.zoutrankil.data.repository.SyncRunLedger;
import com.zoutrankil.data.service.DatasetIntervalLock;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;

/** 未发布stage的显式放弃；调用期间必须一直持有原发布操作锁。 */
final class MarginAllStageRecovery {
    private MarginAllStageRecovery() {}

    static void discard(MarginAllTables tables, Path ledgerPath, String table, String runId)
            throws Exception {
        var ledger = SyncRunLedger.openExisting(ledgerPath);
        var run = ledger.getRun(runId);
        MarginAllRunRecovery.request(run);
        var frozen = JobDefinitionJson.mapper().readTree(run.frozenJson());
        var scope =
                new DatasetIntervalLock.Scope(
                        "margin_all",
                        LocalDate.parse(frozen.path("from").asText()),
                        LocalDate.parse(frozen.path("to").asText()));
        var locks = new DatasetIntervalLock(ledgerPath);
        var lease = locks.findOwned(runId, scope);
        Path root = ledgerPath.toAbsolutePath().getParent().resolve("sync-evidence").resolve(runId);
        if (Files.isSymbolicLink(root) || !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS))
            throw new IllegalStateException("原stage证据目录无效");
        Path intent = root.resolve("ledger-discard-intent.json");
        boolean existing = Files.exists(intent, LinkOption.NOFOLLOW_LINKS);
        if (lease == null && (!existing || ledger.get(runId).state() != SyncRunState.CANCELLED))
            throw new IllegalStateException("丢弃前缺少原运行租约");
        if (!existing && ledger.get(runId).state() != SyncRunState.IN_DOUBT)
            throw new IllegalStateException("stage显式协调仅处理原未决运行");
        // 先保留租约；DROP或账本落盘任一步失败都不能让新任务进入。
        if (lease != null && !lease.inDoubt()) {
            locks.retainInDoubt(lease);
            lease = locks.findOwned(runId, scope);
        }
        MarginAllStaging.discardUnpublished(tables, table, runId, root, ledgerPath, true);
        List<Path> markers;
        try (var files = Files.newDirectoryStream(root.resolve("stage"), "*-intent.json")) {
            markers = new ArrayList<>();
            for (var file : files) markers.add(file);
        }
        if (markers.size() != 1) throw new IllegalStateException("原stage意图不唯一");
        Path marker =
                FileEvidenceStore.owned(
                        markers.getFirst(),
                        root,
                        FileEvidenceStore.Ownership.DESCENDANT,
                        FileEvidenceStore.Symlinks.REJECT_LEAF,
                        () -> new IllegalStateException("原stage意图越界"));
        String markerHash =
                FileEvidenceStore.sha256(
                        FileEvidenceStore.readBounded(
                                marker,
                                1024 * 1024,
                                () -> new IllegalStateException("原stage意图超预算")));
        if (!existing) {
            var entries = ledger.entries(runId, null, 1000);
            if (entries.size() >= 1000) throw new IllegalStateException("原运行清单超预算");
            var proof = new LinkedHashMap<String, Object>();
            proof.put("schema", "sync.discarded-stage-reconciliation.v1");
            proof.put("runId", runId);
            proof.put("targetId", run.targetId());
            proof.put(
                    "requestFingerprint",
                    SyncRequestIdentity.fingerprint(run.frozenJson(), run.targetId()));
            proof.put("writerStopped", true);
            proof.put("targetUnchanged", true);
            proof.put("stageAbsent", true);
            proof.put("originalLeaseId", lease.id());
            proof.put("stageIntent", marker.toString());
            proof.put("stageIntentSha256", markerHash);
            proof.put(
                    "entries",
                    entries.stream()
                            .map(
                                    e -> {
                                        var item = new LinkedHashMap<String, Object>();
                                        item.put("id", e.id());
                                        item.put("kind", e.kind().name());
                                        item.put("parentId", e.parentId());
                                        item.put("revision", e.revision());
                                        item.put("state", e.state().name());
                                        return item;
                                    })
                            .toList());
            FileEvidenceStore.writeNewDurable(
                    intent, JobDefinitionJson.canonicalMapper().writeValueAsBytes(proof));
        }
        FileEvidenceStore.owned(
                intent,
                root,
                FileEvidenceStore.Ownership.DIRECT_CHILD,
                FileEvidenceStore.Symlinks.REJECT_LEAF,
                () -> new IllegalStateException("丢弃意图越界"));
        byte[] bytes =
                FileEvidenceStore.readBounded(
                        intent, 1024 * 1024, () -> new IllegalStateException("丢弃意图超预算"));
        var proof = JobDefinitionJson.mapper().readTree(bytes);
        if (!marker.toString().equals(proof.path("stageIntent").asText())
                || !markerHash.equals(proof.path("stageIntentSha256").asText()))
            throw new IllegalStateException("原stage丢弃意图变化");
        if (lease != null && !lease.id().equals(proof.path("originalLeaseId").asText()))
            throw new IllegalStateException("丢弃期间原租约归属变化");
        ledger.cancelDiscardedStageRun(runId, intent, FileEvidenceStore.sha256(bytes));
        // 原表和stage再次检查通过后才保存最终凭证、解除同一租约。
        MarginAllStaging.discardUnpublished(tables, table, runId, root, ledgerPath, true);
        Path verified = root.resolve("ledger-discard-verified.json");
        if (Files.exists(verified, LinkOption.NOFOLLOW_LINKS)) {
            FileEvidenceStore.owned(
                    verified,
                    root,
                    FileEvidenceStore.Ownership.DIRECT_CHILD,
                    FileEvidenceStore.Symlinks.REJECT_LEAF,
                    () -> new IllegalStateException("丢弃凭证越界"));
            if (!Arrays.equals(bytes, Files.readAllBytes(verified)))
                throw new IllegalStateException("原丢弃核验凭证不符");
        } else FileEvidenceStore.writeNewDurable(verified, bytes);
        if (lease != null) locks.releaseAfterReconciliation(lease, true, true);
    }

    /** 已丢弃的stage检查点全部作废，resume必须重新抓完整冻结范围。 */
    static boolean requiresRefetch(Path ledgerPath, String runId) throws Exception {
        var ledger = SyncRunLedger.openReadOnly(ledgerPath);
        if (ledger.get(runId).state() != SyncRunState.CANCELLED) return false;
        var payload = JobDefinitionJson.mapper().readTree(ledger.get(runId).payloadJson());
        if (!payload.path("discardedStage").asBoolean()) return false;
        Path root = ledgerPath.toAbsolutePath().getParent().resolve("sync-evidence").resolve(runId);
        Path verified = root.resolve("ledger-discard-verified.json");
        FileEvidenceStore.owned(
                verified,
                root,
                FileEvidenceStore.Ownership.DIRECT_CHILD,
                FileEvidenceStore.Symlinks.REJECT_LEAF,
                () -> new IllegalStateException("丢弃凭证越界"));
        byte[] bytes =
                FileEvidenceStore.readBounded(
                        verified, 1024 * 1024, () -> new IllegalStateException("丢弃凭证超预算"));
        if (!FileEvidenceStore.sha256(bytes).equals(payload.path("proofSha256").asText()))
            throw new IllegalStateException("丢弃凭证摘要不符");
        var run = ledger.getRun(runId);
        var proof = JobDefinitionJson.mapper().readTree(bytes);
        if (!runId.equals(proof.path("runId").asText())
                || !run.targetId().equals(proof.path("targetId").asText())
                || !SyncRequestIdentity.fingerprint(run.frozenJson(), run.targetId())
                        .equals(proof.path("requestFingerprint").asText()))
            throw new IllegalStateException("重新采集凭证不属于原请求");
        var entries = ledger.entries(runId, null, 1000);
        if (entries.size() != proof.path("entries").size() || entries.size() >= 1000)
            throw new IllegalStateException("原丢弃运行清单变化");
        var expected = new HashMap<String, JsonNode>();
        for (var item : proof.path("entries"))
            if (expected.put(item.path("id").asText(), item) != null)
                throw new IllegalStateException("原丢弃运行清单重复");
        for (var entry : entries) {
            var item = expected.get(entry.id());
            var entryProof = JobDefinitionJson.mapper().readTree(entry.payloadJson());
            if (item == null
                    || entry.state() != SyncRunState.CANCELLED
                    || entry.revision() != item.path("revision").asLong(-2) + 1
                    || !entry.kind().name().equals(item.path("kind").asText())
                    || !Objects.equals(
                            entry.parentId(),
                            item.path("parentId").isNull() ? null : item.path("parentId").asText())
                    || !entryProof.equals(payload))
                throw new IllegalStateException("原stage子项未完整取消或归属变化");
        }
        return true;
    }
}
