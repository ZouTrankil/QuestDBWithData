package com.zoutrankil.data.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zoutrankil.data.domain.JobDefinitionJson;
import com.zoutrankil.data.domain.PageContract;
import com.zoutrankil.data.domain.StockFactor;
import com.zoutrankil.data.mapper.StockFactorMapper;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.function.BooleanSupplier;

/** One bounded legacy stk_factor request; no pro fallback or field mixing is performed. */
public final class StockFactorSource {
    public static final String SOURCE_ENDPOINT="stk_factor";
    public static final int SOURCE_CONTRACT_VERSION=2;
    public static final int SOURCE_ROW_CAP=10_000;
    public static final int MAX_EVIDENCE_BYTES=32*1024*1024;
    public record Query(LocalDate from,LocalDate to,String tsCode) {
        public Query {
            Objects.requireNonNull(from);Objects.requireNonNull(to);
            if(to.isBefore(from) || java.time.temporal.ChronoUnit.DAYS.between(from,to)>=5)
                throw new IllegalArgumentException("stk_factor source query must cover at most five calendar days");
            if(tsCode!=null && !tsCode.matches("[0-9]{6}\\.(?:SZ|SH|BJ)"))
                throw new IllegalArgumentException("Exact Tushare A-share code required");
            if(tsCode==null && !from.equals(to))
                throw new IllegalArgumentException("Full-market source path requires one trade_date per request");
        }
        Map<String,Object> parameters() {
            var basic=DateTimeFormatter.BASIC_ISO_DATE;
            if(tsCode==null) return Map.of("trade_date",from.format(basic));
            return Map.of("ts_code",tsCode,"start_date",from.format(basic),"end_date",to.format(basic));
        }
        boolean contains(String code,LocalDate date) {
            return (tsCode==null || tsCode.equals(code)) && !date.isBefore(from) && !date.isAfter(to);
        }
    }
    public record Result(List<StockFactor> rows,String fingerprint,String receipt,int pages) {
        public Result { rows=List.copyOf(rows); }
    }
    public static final PageContract CONTRACT=new PageContract(SOURCE_ENDPOINT,StockFactorMapper.SOURCE_FIELDS,
            List.of("ts_code","trade_date"),Set.of("ts_code","trade_date","start_date","end_date"),
            PageContract.Paging.NONE,PageContract.Completion.SHORT_PAGE,null,null,
            SOURCE_ROW_CAP,SOURCE_ROW_CAP,1,SOURCE_ROW_CAP,
            "Observed legacy stk_factor field contract in D009 exact-code probes; local hard cap 10000 is conservative and fail-closed; no offset/cursor is used");
    private final TusharePageService pages;
    private final Path evidenceRoot;
    private final StockFactorMapper mapper=new StockFactorMapper();
    private final ObjectMapper json=JobDefinitionJson.mapper()
            .configure(com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS,true);
    public StockFactorSource(TusharePageService pages,Path evidenceRoot) {
        this.pages=Objects.requireNonNull(pages);this.evidenceRoot=evidenceRoot.toAbsolutePath().normalize();
    }
    public Result fetch(Query query,BooleanSupplier cancelled) throws Exception {
        Objects.requireNonNull(query);Objects.requireNonNull(cancelled);
        var raw=new ArrayList<Map<String,JsonNode>>();
        var captured=new PageExecutor.Page[1];
        PageExecutor.Completed completed;
        try {
            var fetcher=pages.fetcher(CONTRACT,cancelled);
            completed=new PageExecutor().execute(CONTRACT,query.parameters(),parameters->{
                var response=fetcher.fetch(parameters);
                captured[0]=response; // retain the untouched response before cap/row validation can reject it
                return response;
            },(page,receipt)->raw.addAll(page.rows()),row->{
                var typed=mapper.fromSource(row);
                var domain=mapper.fromSource(typed);
                if(!query.contains(domain.tsCode(),domain.tradeDate()))
                    throw new IllegalArgumentException("stk_factor row lies outside frozen code/date scope");
            },cancelled);
        } catch(Exception failure) {
            if(captured[0]!=null && failure instanceof PageExecutor.Incomplete) {
                try { persistIncompleteEvidence(query,captured[0],failure); }
                catch(Exception evidenceFailure) { failure.addSuppressed(evidenceFailure); }
            }
            throw failure;
        }
        if(completed.rows()!=raw.size() || completed.pages()!=1)
            throw new IllegalStateException("stk_factor completion count differs from captured response");
        // Tushare may reorder identical responses; canonicalize keys without changing raw values.
        raw.sort(Comparator.comparing((Map<String,JsonNode> row)->row.get("ts_code").asText())
                .thenComparing(row->row.get("trade_date").asText()));
        var typed=raw.stream().map(mapper::fromSource).map(mapper::fromSource).toList();
        for(var row:typed) if(!query.contains(row.tsCode(),row.tradeDate()))
            throw new IllegalStateException("Source scope changed during mapping");
        var body=json.writeValueAsBytes(Map.of("sourceKind","tushare","endpoint",SOURCE_ENDPOINT,
                "sourceContractVersion",SOURCE_CONTRACT_VERSION,
                "parameters",query.parameters(),"fields",StockFactorMapper.SOURCE_FIELDS,
                "query",query,"returnedRows",typed.size(),"rows",raw,"completion",completed));
        if(body.length>MAX_EVIDENCE_BYTES) throw new IllegalArgumentException("stk_factor source evidence exceeds byte budget");
        String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
        Files.createDirectories(evidenceRoot);
        Path receipt=evidenceRoot.resolve((typed.isEmpty()?"empty-":"source-")+UUID.randomUUID()+".json");
        Files.write(receipt,body,StandardOpenOption.CREATE_NEW);
        return new Result(typed,hash,receipt.toString(),completed.pages());
    }

    private void persistIncompleteEvidence(Query query,PageExecutor.Page response,Exception failure) throws Exception {
        var body=new LinkedHashMap<String,Object>();
        body.put("sourceKind","tushare");body.put("endpoint",SOURCE_ENDPOINT);
        body.put("sourceContractVersion",SOURCE_CONTRACT_VERSION);
        body.put("parameters",query.parameters());body.put("fields",StockFactorMapper.SOURCE_FIELDS);
        body.put("query",query);body.put("responseRows",response.rows().size());body.put("rows",response.rows());
        body.put("sourceComplete",false);body.put("evidenceStatus","unverified_raw_response");
        body.put("failureCategory",failure.getClass().getSimpleName());
        body.put("failure",failure.getMessage());body.put("sourceRowCap",SOURCE_ROW_CAP);
        body.put("explicitEnd",response.explicitEnd());
        if(response.sourceVersion()!=null) body.put("sourceVersion",response.sourceVersion());
        byte[] bytes=json.writeValueAsBytes(body);
        if(bytes.length>MAX_EVIDENCE_BYTES)
            throw new IllegalArgumentException("stk_factor incomplete response evidence exceeds byte budget");
        Files.createDirectories(evidenceRoot);
        Path evidence=evidenceRoot.resolve("incomplete-"+UUID.randomUUID()+".json");
        Files.write(evidence,bytes,StandardOpenOption.CREATE_NEW);
    }
}
