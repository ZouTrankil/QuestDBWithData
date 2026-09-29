package com.zoutrankil.questdbwithdata.service;

import com.zoutrankil.questdbwithdata.client.TushareClient;
import com.zoutrankil.questdbwithdata.client.dto.TushareRequest;
import com.zoutrankil.questdbwithdata.domain.PageContract;
import org.springframework.stereotype.Service;
import java.util.Map;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;

/** Existing shared HTTP/rate-budget path, serial page consumption and cancellable source calls. */
@Service
public class TusharePageService {
    private final TushareClient client;
    public TusharePageService(TushareClient client) { this.client = client; }
    public PageExecutor.Completed execute(PageContract contract, Map<String, Object> baseParams,
                                           PageExecutor.Consumer consumer, PageExecutor.Validator validator,
                                           BooleanSupplier cancelled) throws Exception {
        return new PageExecutor().execute(contract, baseParams, fetcher(contract, cancelled), consumer, validator, cancelled);
    }
    public PageExecutor.Fetcher fetcher(PageContract contract, BooleanSupplier cancelled) {
        if (contract.paging() == PageContract.Paging.CURSOR || contract.completion() == PageContract.Completion.EXPLICIT_END) {
            throw new IllegalArgumentException("Tushare fields/items wire response has no cursor/end marker; a source-specific adapter is required");
        }
        return params -> {
            if (cancelled.getAsBoolean()) throw new CancellationException("Source request cancelled");
            var future = client.requestAsync(new TushareRequest(contract.endpoint(), params, contract.fields(),
                    contract.paging() == PageContract.Paging.NONE ? contract.sourceRowCap() : contract.pageSize()));
            try {
                while (true) {
                    if (cancelled.getAsBoolean()) throw new CancellationException("Source request cancelled");
                    try {
                        var page = future.get(100, TimeUnit.MILLISECONDS);
                        return new PageExecutor.Page(page.rows(), null, false, null);
                    } catch (TimeoutException waiting) { /* F005 bounds total wait; periodically observe cancellation. */ }
                }
            } finally { if (!future.isDone()) future.cancel(true); }
        };
    }
}
