package com.zoutrankil.data.service;

import com.zoutrankil.data.domain.StockDetailInfoDataset;
import com.zoutrankil.data.repository.StockDetailInfoStorage;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Replays Python's L/D/P stock_basic name enrichment from the already synchronized D002 snapshot. */
@Service
public final class IndexWeightNameResolver implements IndexWeightSource.NameLookup {
    private final JdbcTemplate jdbc;
    private final String table;

    public IndexWeightNameResolver(JdbcTemplate jdbc,
            @Value("${app.sync.stock-detail-table:stock_detail_info}") String table) {
        this.jdbc = Objects.requireNonNull(jdbc); com.zoutrankil.data.domain.DatasetDefinition.identifier(table);
        this.table = table;
    }

    public String targetId() {
        var identity = new StockDetailInfoStorage(jdbc, table).preflight();
        return StaticTargetIdentity.identify(jdbc, table, identity.id(), identity.directory());
    }

    @Override public IndexWeightSource.NameEnrichment resolve(String expectedTargetId, Set<String> constituentCodes)
            throws Exception {
        if (expectedTargetId == null || !expectedTargetId.matches("static-v2-[0-9a-f]{64}"))
            throw new IllegalArgumentException("Frozen D002 stock_detail_info target identity required");
        if (constituentCodes == null || constituentCodes.size() > IndexWeightSource.TUSHARE_ROW_CAP)
            throw new IllegalArgumentException("D021 name reference exceeds the bounded 4000-code response");
        var storage = new StockDetailInfoStorage(jdbc, table);
        var before = storage.preflight();
        String actualTarget = StaticTargetIdentity.identify(jdbc, table, before.id(), before.directory());
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
                StaticTargetIdentity.identify(jdbc, table, after.id(), after.directory())))
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
        byte[] canonical = com.zoutrankil.data.domain.JobDefinitionJson.mapper()
                .configure(com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true)
                .writeValueAsBytes(references);
        String fingerprint = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical));
        return new IndexWeightSource.NameEnrichment(actualTarget, fingerprint, names, references);
    }
}
