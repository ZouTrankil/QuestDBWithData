package com.zoutrankil.data.service;

import com.zoutrankil.data.client.TushareClient;
import com.zoutrankil.data.client.dto.TushareRequest;
import com.zoutrankil.data.domain.PageContract;
import org.springframework.stereotype.Service;
import java.util.Map;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;

/** 复用共享 HTTP 和配额通道，串行消费分页，并支持取消来源请求。 */
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
        // 特定来源的游标和历史锚点由所属市场提供，传输层只处理通用分页。
        var adapters=java.util.ServiceLoader.load(TushareCursorAdapter.class).stream()
                .map(java.util.ServiceLoader.Provider::get).filter(adapter->adapter.supports(contract)).toList();
        if (adapters.size()>1) throw new IllegalStateException("Duplicate source cursor adapter");
        if (!adapters.isEmpty()) return adapters.getFirst().fetcher(client,contract,cancelled);
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
                    } catch (TimeoutException waiting) { /* F005 限制总等待时间；等待期间定期检查取消状态。 */ }
                }
            } finally { if (!future.isDone()) future.cancel(true); }
        };
    }
}
