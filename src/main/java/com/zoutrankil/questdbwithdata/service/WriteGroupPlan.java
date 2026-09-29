package com.zoutrankil.questdbwithdata.service;

import com.zoutrankil.questdbwithdata.domain.*;
import com.zoutrankil.questdbwithdata.repository.DatasetWritePreparation;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.*;

/** Pure validation and frozen evidence. This step never calls a writer or claims database completion. */
public final class WriteGroupPlan {
    public record Member(String memberId, String batchId, String targetId, DatasetDefinition definition,
                         DatasetWritePreparation.Batch batch) {}
    private final String batchId;
    private final LocalDate logicalDate;
    private final List<Member> members;
    private final String fingerprint;
    private WriteGroupPlan(String batchId, LocalDate logicalDate, List<Member> members, String fingerprint) {
        this.batchId = batchId; this.logicalDate = logicalDate;
        this.members = List.copyOf(members); this.fingerprint = fingerprint;
    }
    /** Targets must be resolved by registered owners, not arbitrary user SQL or object names. */
    public static WriteGroupPlan prepare(WriteGroupRequest request, DatasetRegistry datasets,
                                        Map<String,String> ownerTargets) {
        Objects.requireNonNull(request); Objects.requireNonNull(datasets); Objects.requireNonNull(ownerTargets);
        var expected = new HashSet<String>(); request.members().forEach(m -> expected.add(m.datasetId()));
        if (!expected.equals(ownerTargets.keySet())) throw new IllegalArgumentException("Exact owner target bindings required");
        var members = new ArrayList<Member>();
        long bytes = 0;
        for (var member : request.members()) {
            var definition = datasets.require(member.datasetId()).definition();
            if (definition.schemaVersion() != member.definitionVersion())
                throw new IllegalArgumentException("Write dataset version differs from registered owner");
            String target = ownerTargets.get(member.datasetId()); SyncJobDefinition.name(target);
            var prepared = DatasetWritePreparation.prepare(definition, member.rows(), java.util.function.Function.identity(),
                    new DatasetWritePreparation.Limits(10_000, 16 * 1024 * 1024));
            bytes += prepared.normalizedBytes();
            if (bytes > 64 * 1024 * 1024) throw new IllegalArgumentException("Combined write data exceeds 64 MiB");
            members.add(new Member(member.memberId(), member.batchId(), target, definition, prepared));
        }
        try {
            var json = JobDefinitionJson.mapper().configure(
                    com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
            var identities = members.stream().map(m -> {
                com.fasterxml.jackson.databind.node.ObjectNode definition = json.valueToTree(m.definition());
                var capabilities = definition.putArray("capabilities");
                m.definition().capabilities().stream().map(Enum::name).sorted().forEach(capabilities::add);
                return Map.of("memberId", m.memberId(), "batchId", m.batchId(),
                        "targetId", m.targetId(), "definition", definition, "sourceFingerprint", m.batch().fingerprint());
            }).toList();
            byte[] frozen = json.writeValueAsString(Map.of("batchId", request.batchId(),
                    "logicalDate", request.logicalDate(), "members", identities)).getBytes(StandardCharsets.UTF_8);
            String fingerprint = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(frozen));
            return new WriteGroupPlan(request.batchId(), request.logicalDate(), members, fingerprint);
        } catch (Exception failure) { throw new IllegalArgumentException("Cannot freeze write group identity", failure); }
    }
    public String batchId() { return batchId; }
    public LocalDate logicalDate() { return logicalDate; }
    public List<Member> members() { return members; }
    public String fingerprint() { return fingerprint; }
    public boolean atomicAcrossTables() { return false; }
}
