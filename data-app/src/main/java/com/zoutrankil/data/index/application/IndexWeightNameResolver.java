package com.zoutrankil.data.index.application;

import com.zoutrankil.data.repository.FileEvidenceStore;
import com.zoutrankil.data.stock.port.StockDetailNameReadPort;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Service;

/** Replays Python's L/D/P stock_basic name enrichment from the already synchronized D002 snapshot. */
@Service
public final class IndexWeightNameResolver implements IndexWeightSource.NameLookup {
    private final StockDetailNameReadPort reference;

    public IndexWeightNameResolver(StockDetailNameReadPort reference) {
        this.reference = Objects.requireNonNull(reference);
    }

    public String targetId() { return reference.targetId(); }

    @Override public IndexWeightSource.NameEnrichment resolve(String expectedTargetId, Set<String> constituentCodes)
            throws Exception {
        if (expectedTargetId == null || !expectedTargetId.matches("static-v2-[0-9a-f]{64}"))
            throw new IllegalArgumentException("Frozen D002 stock_detail_info target identity required");
        if (constituentCodes == null || constituentCodes.size() > IndexWeightSource.TUSHARE_ROW_CAP)
            throw new IllegalArgumentException("D021 name reference exceeds the bounded 4000-code response");
        var storage = reference.openSession();
        var before = storage.preflight();
        String actualTarget = reference.identify(before);
        if (!expectedTargetId.equals(actualTarget))
            throw new IllegalStateException("D021 name enrichment D002 target differs from frozen identity");
        var codes = constituentCodes.stream().sorted().toList();
        var rows = new ArrayList<com.zoutrankil.data.domain.table.StockDetailInfoRow>(codes.size());
        for (int offset = 0; offset < codes.size(); offset += 250) {
            if (!expectedTargetId.equals(targetId()))
                throw new IllegalStateException("D002 target changed between bounded name-reference reads");
            rows.addAll(storage.readKeys(codes.subList(offset, Math.min(codes.size(), offset + 250))));
        }
        var after = storage.preflight();
        if (!before.equals(after) || !expectedTargetId.equals(
                reference.identify(after)))
            throw new IllegalStateException("D002 stock_detail_info changed during D021 name enrichment");

        var names = new LinkedHashMap<String, String>();
        var references = new ArrayList<Map<String, Object>>(rows.size());
        for (var row : rows.stream().sorted(Comparator.comparing(value -> value.tsCode())).toList()) {
            if (!constituentCodes.contains(row.tsCode()) || names.containsKey(row.tsCode()))
                throw new IllegalStateException("D021 name reference returned an unexpected or duplicate stock key");
            names.put(row.tsCode(), row.name());
            var reference = new LinkedHashMap<String, Object>();
            reference.put("ts_code", row.tsCode()); reference.put("name", row.name());
            reference.put("list_status", row.listStatus());
            references.add(java.util.Collections.unmodifiableMap(reference));
        }
        byte[] canonical = com.zoutrankil.data.domain.JobDefinitionJson.canonicalMapper()
                .writeValueAsBytes(references);
        String fingerprint = FileEvidenceStore.sha256(canonical);
        return new IndexWeightSource.NameEnrichment(actualTarget, fingerprint, names, references);
    }
}
