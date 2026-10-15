package com.zoutrankil.data.stock.application;

import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.repository.FileEvidenceStore;
import com.zoutrankil.data.stock.mapper.StockFactorMapper;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.*;
import java.util.*;

/** Restore only a bounded, hash-bound completed response; never fetch a replacement during recovery. */
public final class StockFactorFrozenEvidence {
    private StockFactorFrozenEvidence() {}
    public static StockFactorSource.Result read(Path receipt, String expectedSha,
            StockFactorSource.Query query) throws Exception {
        if(expectedSha==null || !expectedSha.matches("[a-f0-9]{64}")
                || Files.isSymbolicLink(receipt) || !Files.isRegularFile(receipt,LinkOption.NOFOLLOW_LINKS))
            throw new IllegalArgumentException("Regular frozen response and SHA-256 required");
        byte[] bytes=FileEvidenceStore.readBounded(receipt,StockFactorSource.MAX_EVIDENCE_BYTES,
                ()->new IllegalArgumentException("Frozen stock factor response exceeds budget"));
        if(!expectedSha.equals(FileEvidenceStore.sha256(bytes)))
            throw new IllegalStateException("Frozen stock factor response changed");
        var json=JobDefinitionJson.mapper();var root=json.readTree(bytes);
        if(!"tushare".equals(root.path("sourceKind").asText())
                || !StockFactorSource.SOURCE_ENDPOINT.equals(root.path("endpoint").asText())
                || root.path("sourceContractVersion").asInt(-1)!=StockFactorSource.SOURCE_CONTRACT_VERSION
                || !root.path("fields").equals(json.valueToTree(StockFactorMapper.SOURCE_FIELDS))
                || !root.path("query").equals(json.valueToTree(query))
                || !root.path("parameters").equals(json.valueToTree(query.parameters())))
            throw new IllegalArgumentException("Frozen response contract or scope mismatch");
        var raw=root.path("rows");int n=root.path("returnedRows").asInt(-1);
        if(!raw.isArray() || n<1 || n>=StockFactorSource.SOURCE_ROW_CAP || raw.size()!=n
                || root.path("completion").path("pages").asInt(-1)!=1
                || root.path("completion").path("rows").asInt(-1)!=n)
            throw new IllegalArgumentException("Complete bounded nonempty source page required");
        var mapper=new StockFactorMapper();var rows=new ArrayList<StockFactor>(n);
        var keys=new HashSet<StockFactorKey>();String previous=null;
        for(JsonNode node:raw) {
            if(!node.isObject())throw new IllegalArgumentException("Typed source row required");
            var fields=new LinkedHashMap<String,JsonNode>();node.fields().forEachRemaining(e->fields.put(e.getKey(),e.getValue()));
            var row=mapper.fromSource(mapper.fromSource(fields));
            String order=row.tsCode()+"|"+row.tradeDate();
            if(!query.contains(row.tsCode(),row.tradeDate()) || !keys.add(row.key())
                    || previous!=null && previous.compareTo(order)>=0)
                throw new IllegalArgumentException("Frozen source must contain unique sorted in-scope keys");
            previous=order;rows.add(row);
        }
        return new StockFactorSource.Result(rows,expectedSha,receipt.toAbsolutePath().normalize().toString(),1);
    }
}
