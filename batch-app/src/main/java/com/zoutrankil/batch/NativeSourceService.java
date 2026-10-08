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
        var claim=ledger.claimSourceProbe(requestId,fingerprint,encoded);
        if(!claim.acquired()) return claim.row();
        try {
            var contract=SourceContract.load(request.dataset());
            SourceCollector.ContractFetcher fetcher=contract.dataset().equals("cn_bond_yield_curve")?
                    (provider,params) -> Objects.requireNonNull(chinabond,"ChinaBond source is not configured").fetcher().fetch(params):
                    (provider,params) -> pages.fetcher(provider,() -> Thread.currentThread().isInterrupted()).fetch(params);
            var result=collector.collect(request,fetcher);
            ledger.completeSourceProbe(requestId,result.state().name(),Json.write(result));
            ledger.audit(null,"source-collected",request.dataset()+":"+result.fingerprint());
        } catch(Exception error) {
            ledger.failSourceProbe(requestId,error.getClass().getSimpleName());
            throw error;
        }
        return ledger.sourceProbe(requestId);
    }
}
