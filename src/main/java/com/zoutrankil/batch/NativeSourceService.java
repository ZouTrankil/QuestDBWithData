package com.zoutrankil.batch;

import com.zoutrankil.data.service.TusharePageService;
import java.util.*;

/** Idempotent native source probes. A successful source response is not product Ready. */
public final class NativeSourceService {
    private final SqliteLedger ledger;private final SourceCollector collector;private final TusharePageService pages;private final ChinabondYieldSource chinabond;
    public NativeSourceService(SqliteLedger ledger,SourceCollector collector,TusharePageService pages) {
        this(ledger,collector,pages,null);
    }
    public NativeSourceService(SqliteLedger ledger,SourceCollector collector,TusharePageService pages,ChinabondYieldSource chinabond) {
        this.ledger=ledger;this.collector=collector;this.pages=pages;this.chinabond=chinabond;
    }
    public Map<String,Object> collect(String requestId,SourceCollector.Request request) throws Exception {
        if(requestId==null || requestId.isBlank() || requestId.length()>256) throw new IllegalArgumentException("Idempotency-Key required");
        String encoded=Json.write(request);String fingerprint=RunRequest.hash(encoded);
        int acquired=ledger.jdbc().update("INSERT INTO source_probe(request_id,request_fingerprint,request_json,state) VALUES(?,?,?,'RUNNING') ON CONFLICT(request_id) DO NOTHING",requestId,fingerprint,encoded);
        var row=ledger.jdbc().queryForMap("SELECT * FROM source_probe WHERE request_id=?",requestId);
        if(!fingerprint.equals(row.get("request_fingerprint"))) throw new IllegalArgumentException("Source probe idempotency key reused for different input");
        if(acquired==0) return row;
        try {
            var contract=SourceContract.load(request.dataset());
            SourceCollector.ContractFetcher fetcher=contract.dataset().equals("cn_bond_yield_curve")?
                    (provider,params) -> Objects.requireNonNull(chinabond,"ChinaBond source is not configured").fetcher().fetch(params):
                    (provider,params) -> pages.fetcher(provider,() -> Thread.currentThread().isInterrupted()).fetch(params);
            var result=collector.collect(request,fetcher);
            ledger.jdbc().update("UPDATE source_probe SET state=?,result_json=?,completed_at=current_timestamp WHERE request_id=?",result.state().name(),Json.write(result),requestId);
            ledger.audit(null,"source-collected",request.dataset()+":"+result.fingerprint());
        } catch(Exception error) {
            ledger.jdbc().update("UPDATE source_probe SET state='FAILED',error_type=?,completed_at=current_timestamp WHERE request_id=?",error.getClass().getSimpleName(),requestId);
            throw error;
        }
        return ledger.jdbc().queryForMap("SELECT * FROM source_probe WHERE request_id=?",requestId);
    }
}
