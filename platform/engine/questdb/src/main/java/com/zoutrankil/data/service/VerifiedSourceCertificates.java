package com.zoutrankil.data.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.zoutrankil.data.domain.DatasetDefinition;
import com.zoutrankil.data.domain.JobDefinitionJson;
import com.zoutrankil.data.domain.SyncRunState;
import com.zoutrankil.data.repository.FileEvidenceStore;
import com.zoutrankil.data.repository.SyncRunLedger;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;

/** 把已核验运行导出为有范围的来源凭证；PARTIAL、未决和失败运行不能出证。 */
public final class VerifiedSourceCertificates {
    private VerifiedSourceCertificates() {}

    public static Path publish(Path ledgerPath, String runId, Path output) throws Exception {
        var ledger = SyncRunLedger.openReadOnly(ledgerPath);
        var entry = ledger.get(runId);
        var run = ledger.getRun(runId);
        requireVerified(entry);
        var json = JobDefinitionJson.mapper();
        var frozen = json.readTree(run.frozenJson());
        String dataset = frozen.required("definition").required("datasetId").asText();
        DatasetDefinition.identifier(dataset);
        var proof = new LinkedHashMap<String, Object>();
        proof.put("schema", "java.source.coverage.v1");
        proof.put("dataset", dataset);
        proof.put("sourceComplete", true);
        proof.put("logicalDate", run.logicalDate());
        proof.put("targetId", run.targetId());
        proof.put("scope", frozen);
        proof.put("ledger", ledgerPath.toAbsolutePath().normalize().toString());
        proof.put("runId", runId);
        proof.put("runSha256", identity(run, entry));
        proof.put("limitation", "仅证明该冻结请求的范围；不能替代独立证券范围、历史时点或关联视图核验");
        byte[] bytes = json.writeValueAsBytes(proof);
        Path target = output.toAbsolutePath().normalize();
        Files.createDirectories(target.getParent());
        FileEvidenceStore.writeNewDurable(target, bytes);
        return target;
    }

    public static JsonNode validate(byte[] bytes) throws Exception {
        var document = JobDefinitionJson.mapper().readTree(bytes);
        if (!"java.source.coverage.v1".equals(document.path("schema").asText())
                || !document.path("sourceComplete").isBoolean()
                || !document.path("sourceComplete").booleanValue())
            throw new IllegalStateException("来源凭证格式或完整状态错误");
        var ledger = SyncRunLedger.openReadOnly(Path.of(document.required("ledger").asText()));
        String id = document.required("runId").asText();
        var entry = ledger.get(id);
        var run = ledger.getRun(id);
        requireVerified(entry);
        var frozen = JobDefinitionJson.mapper().readTree(run.frozenJson());
        if (!identity(run, entry).equals(document.path("runSha256").asText())
                || !frozen.equals(document.path("scope"))
                || !run.targetId().equals(document.path("targetId").asText())
                || !frozen.required("definition")
                        .required("datasetId")
                        .asText()
                        .equals(document.path("dataset").asText())
                || !run.logicalDate().equals(document.path("logicalDate").asText()))
            throw new IllegalStateException("来源凭证与原账本不同");
        return document;
    }

    private static String identity(SyncRunLedger.Run run, SyncRunLedger.Entry entry)
            throws Exception {
        return FileEvidenceStore.sha256(
                JobDefinitionJson.canonicalMapper().writeValueAsBytes(List.of(run, entry)));
    }

    private static void requireVerified(SyncRunLedger.Entry entry) {
        if (entry.kind() != SyncRunLedger.Kind.RUN
                || !Set.of(SyncRunState.VERIFIED, SyncRunState.VERIFIED_EMPTY)
                        .contains(entry.state())) throw new IllegalStateException("来源运行尚未核验完成");
    }
}
