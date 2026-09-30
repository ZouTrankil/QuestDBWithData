package com.zoutrankil.questdbwithdata.service;

import com.zoutrankil.questdbwithdata.client.TushareClient;
import com.zoutrankil.questdbwithdata.client.dto.TushareRequest;
import com.zoutrankil.questdbwithdata.domain.PageContract;
import org.springframework.stereotype.Service;
import java.util.Map;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;

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
        if (contract.endpoint().equals("namechange")) return params -> fetchNamechange(contract, params, cancelled);
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
    private PageExecutor.Page fetchNamechange(PageContract contract, Map<String,Object> params, BooleanSupplier cancelled) throws Exception {
        String tsCode=(String)params.get("ts_code");
        int year=2010, offset=0;
        Object token=params.get("cursor");
        if(token instanceof String value) {
            String[] pieces=value.split(":",-1);
            if(pieces.length!=2) throw new IllegalArgumentException("Invalid namechange cursor");
            year=Integer.parseInt(pieces[0]); offset=Integer.parseInt(pieces[1]);
        }
        int endYear=LocalDate.parse((String)params.get("end_date"),DateTimeFormatter.BASIC_ISO_DATE).getYear();
        if(year>endYear) return new PageExecutor.Page(List.of(),null,true,null);
        {
            if(cancelled.getAsBoolean()) throw new CancellationException("Source request cancelled");
            var query=new LinkedHashMap<String,Object>();
            query.put("start_date",String.format(Locale.ROOT,"%04d0101",year));
            query.put("end_date",year==endYear?params.get("end_date"):String.format(Locale.ROOT,"%04d1231",year));
            if(tsCode!=null) query.put("ts_code",tsCode);
            query.put("limit",contract.pageSize()); query.put("offset",offset);
            var future=client.requestAsync(new TushareRequest(contract.endpoint(),query,contract.fields(),contract.pageSize()));
            com.zoutrankil.questdbwithdata.client.dto.TusharePage fetched;
            try {
                while(true) {
                    if(cancelled.getAsBoolean()) throw new CancellationException("Source request cancelled");
                    try { fetched=future.get(100,TimeUnit.MILLISECONDS); break; }
                    catch(TimeoutException waiting) { }
                }
            } finally { if(!future.isDone()) future.cancel(true); }
            int count=fetched.rows().size();
            if(count>=contract.pageSize()) return new PageExecutor.Page(fetched.rows(),year+":"+Math.addExact(offset,count),false,null);
            if(year==endYear) return new PageExecutor.Page(fetched.rows(),null,true,null);
            return new PageExecutor.Page(fetched.rows(),(year+1)+":0",false,null);
        }
    }
}
