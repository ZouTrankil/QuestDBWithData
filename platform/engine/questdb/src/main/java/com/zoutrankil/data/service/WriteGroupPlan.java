package com.zoutrankil.data.service;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zoutrankil.data.domain.DatasetDefinition;
import com.zoutrankil.data.domain.JobDefinitionJson;
import com.zoutrankil.data.domain.SyncJobDefinition;
import com.zoutrankil.data.domain.WriteGroupRequest;
import com.zoutrankil.data.repository.DatasetWritePreparation;
import com.zoutrankil.data.repository.FileEvidenceStore;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/** Pure validation and frozen evidence. This step never calls a writer or claims database completion. */
public final class WriteGroupPlan {
    public record Member(
            String memberId,
            String batchId,
            String targetId,
            DatasetDefinition definition,
            DatasetWritePreparation.Batch batch) {}

    private final String batchId;
    private final LocalDate logicalDate;
    private final List<Member> members;
    private final String fingerprint;

    private WriteGroupPlan(
            String batchId, LocalDate logicalDate, List<Member> members, String fingerprint) {
        this.batchId = batchId;
        this.logicalDate = logicalDate;
        this.members = List.copyOf(members);
        this.fingerprint = fingerprint;
    }

    /** Targets must be resolved by registered owners, not arbitrary user SQL or object names. */
    public static WriteGroupPlan prepare(
            WriteGroupRequest request, DatasetRegistry datasets, Map<String, String> ownerTargets) {
        Objects.requireNonNull(request);
        Objects.requireNonNull(datasets);
        Objects.requireNonNull(ownerTargets);
        var expected = new HashSet<String>();
        request.members().forEach(m -> expected.add(m.datasetId()));
        if (!expected.equals(ownerTargets.keySet()))
            throw new IllegalArgumentException("Exact owner target bindings required");
        var members = new ArrayList<Member>();
        long bytes = 0;
        for (var member : request.members()) {
            // 组请求固定原版本；当前默认变化不能改写旧组的定义和指纹。
            var definition =
                    datasets.requireDefinition(member.datasetId(), member.definitionVersion());
            String target = ownerTargets.get(member.datasetId());
            SyncJobDefinition.name(target);
            var limits = new DatasetWritePreparation.Limits(10_000, 16 * 1024 * 1024);
            var prepared =
                    definition.capabilities().contains(DatasetDefinition.Capability.STATIC_REPLACE)
                            ? DatasetWritePreparation.prepareStatic(
                                    definition, member.rows(), Function.identity(), limits)
                            : definition
                                            .capabilities()
                                            .contains(DatasetDefinition.Capability.WAL_REPLACE)
                                    ? DatasetWritePreparation.prepareWalReplace(
                                            definition, member.rows(), Function.identity(), limits)
                                    : DatasetWritePreparation.prepare(
                                            definition, member.rows(), Function.identity(), limits);
            bytes += prepared.normalizedBytes();
            if (bytes > 64 * 1024 * 1024)
                throw new IllegalArgumentException("Combined write data exceeds 64 MiB");
            members.add(
                    new Member(member.memberId(), member.batchId(), target, definition, prepared));
        }
        try {
            var json = JobDefinitionJson.canonicalMapper();
            var identities =
                    members.stream()
                            .map(
                                    m -> {
                                        ObjectNode definition = json.valueToTree(m.definition());
                                        var capabilities = definition.putArray("capabilities");
                                        m.definition().capabilities().stream()
                                                .map(Enum::name)
                                                .sorted()
                                                .forEach(capabilities::add);
                                        return Map.of(
                                                "memberId",
                                                m.memberId(),
                                                "batchId",
                                                m.batchId(),
                                                "targetId",
                                                m.targetId(),
                                                "definition",
                                                definition,
                                                "sourceFingerprint",
                                                m.batch().fingerprint());
                                    })
                            .toList();
            byte[] frozen =
                    json.writeValueAsString(
                                    Map.of(
                                            "batchId",
                                            request.batchId(),
                                            "logicalDate",
                                            request.logicalDate(),
                                            "members",
                                            identities))
                            .getBytes(StandardCharsets.UTF_8);
            String fingerprint = FileEvidenceStore.sha256(frozen);
            return new WriteGroupPlan(
                    request.batchId(), request.logicalDate(), members, fingerprint);
        } catch (Exception failure) {
            throw new IllegalArgumentException("Cannot freeze write group identity", failure);
        }
    }

    public String batchId() {
        return batchId;
    }

    public LocalDate logicalDate() {
        return logicalDate;
    }

    public List<Member> members() {
        return members;
    }

    public String fingerprint() {
        return fingerprint;
    }

    public boolean atomicAcrossTables() {
        return false;
    }
}
