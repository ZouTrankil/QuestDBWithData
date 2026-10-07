package com.zoutrankil.data.service;

import com.zoutrankil.data.repository.FileEvidenceStore;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.mapper.MoneyflowDcMapper;
import java.nio.file.*;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.function.BooleanSupplier;

/** Bounded offset pages for a full-market Tushare moneyflow_dc(trade_date=YYYYMMDD) request per open date. */
public final class MoneyflowDcSource {
    public static final int API_ROW_CAP = 6_000, PAGE_SIZE=2000, MAX_DAILY_ROWS=10000, MAX_PAGES=6, MAX_EVIDENCE_BYTES = 16 * 1024 * 1024;
    public static final LocalDate EARLIEST_SOURCE_DATE = LocalDate.of(2023, 9, 11);
    public static final List<String> FIELDS = List.of("ts_code","trade_date","name","pct_change","close","net_amount","net_amount_rate",
            "buy_elg_amount","buy_elg_amount_rate","buy_lg_amount","buy_lg_amount_rate","buy_md_amount","buy_md_amount_rate","buy_sm_amount","buy_sm_amount_rate");
    public static final PageContract CONTRACT = new PageContract("moneyflow_dc", FIELDS, List.of("ts_code","trade_date"), Set.of("trade_date","limit","offset"),
            PageContract.Paging.OFFSET, PageContract.Completion.SHORT_PAGE, "limit", "offset", PAGE_SIZE, API_ROW_CAP, MAX_PAGES, MAX_DAILY_ROWS,
            "Actual 2026-09-30 provider proof shows limit/offset pages 2000/2000/2000/18 equal all 6018 unpaged rows. Each daily slice is bounded to 10000 rows and six pages; only a short terminal page proves completion. Duplicate/repeated pages fail closed.");
    private static final DateTimeFormatter BASIC = DateTimeFormatter.BASIC_ISO_DATE;
    private final TusharePageService pages; private final MoneyflowDcMapper mapper = new MoneyflowDcMapper(); private final Path evidenceRoot;
    public MoneyflowDcSource(TusharePageService pages, Path evidenceRoot) { this.pages=Objects.requireNonNull(pages); this.evidenceRoot=Objects.requireNonNull(evidenceRoot).toAbsolutePath().normalize(); }

    public SyncJobRunner.Page<MoneyflowDc> fetch(LocalDate date, BooleanSupplier cancelled) throws Exception {
        Objects.requireNonNull(date); Objects.requireNonNull(cancelled); if(date.isBefore(EARLIEST_SOURCE_DATE))throw new IllegalArgumentException("moneyflow_dc source coverage begins 2023-09-11");String basic=date.format(BASIC); var params=Map.<String,Object>of("trade_date",basic);
        var raw=new ArrayList<Map<String,JsonNode>>();var pageProofs=new ArrayList<Map<String,Object>>(); var fetcher=pages.fetcher(CONTRACT,cancelled); PageExecutor.Completed complete;
        try { complete=new PageExecutor().execute(CONTRACT,params,request->{PageExecutor.Page response=fetcher.fetch(request);raw.addAll(response.rows());pageProofs.add(Map.of("offset",request.get("offset"),"limit",request.get("limit"),"returnedRows",response.rows().size(),"keys",response.rows().stream().map(row->value(row,"ts_code")+"/"+value(row,"trade_date")).toList()));return response;},(p,r)->{},row->validateRow(row,basic),cancelled); }
        catch(Exception failure) { persistUnverified(date,params,raw,null,failure); throw failure; }
        raw.sort(Comparator.comparing((Map<String,JsonNode> row)->value(row,"ts_code")).thenComparing(row->value(row,"trade_date")));
        if(complete.pages()!=pageProofs.size()||complete.pages()>MAX_PAGES||complete.rows()!=raw.size()||raw.size()>MAX_DAILY_ROWS) { var failure=new IllegalStateException("moneyflow_dc pagination exceeds its bounded completeness contract");persistUnverified(date,params,raw,complete.sourceVersion(),failure);throw failure; }
        var typed=new ArrayList<MoneyflowDc>(raw.size()); var keys=new HashSet<MoneyflowDcKey>();
        for(var item:raw) { MoneyflowDc row=mapper.fromSource(mapper.dto(item)); if(!row.tradeDate().equals(date)||!keys.add(row.key())) { var failure=new IllegalArgumentException("moneyflow_dc duplicate key or row outside its frozen date");persistUnverified(date,params,raw,complete.sourceVersion(),failure);throw failure; } typed.add(row); }
        byte[] bytes=body(date,params,raw,complete.sourceVersion(),true,null,pageProofs); if(bytes.length>MAX_EVIDENCE_BYTES)throw new IllegalStateException("moneyflow_dc raw receipt exceeds evidence size bound");
        String fingerprint=sha(bytes); Files.createDirectories(evidenceRoot); Path receipt=evidenceRoot.resolve("moneyflow-dc-"+basic+"-"+fingerprint+".json"); persist(receipt,bytes);
        return new SyncJobRunner.Page<>(typed,fingerprint,receipt.toString(),basic);
    }

    public static SyncJobRunner.Page<MoneyflowDc> reopen(Path receipt,String fingerprint,LocalDate expectedDate)throws Exception {
        Path path=receipt.toAbsolutePath().normalize(); if(fingerprint==null||!fingerprint.matches("[0-9a-f]{64}")||!Files.isRegularFile(path)||Files.size(path)>MAX_EVIDENCE_BYTES)throw new IllegalArgumentException("Bounded moneyflow_dc receipt and SHA-256 required");
        byte[] bytes=FileEvidenceStore.readBounded(path, MAX_EVIDENCE_BYTES,
                () -> new IllegalArgumentException("Bounded moneyflow_dc receipt and SHA-256 required")); if(!sha(bytes).equals(fingerprint))throw new IllegalStateException("moneyflow_dc receipt SHA mismatch"); var json=JobDefinitionJson.mapper(); JsonNode proof=json.readTree(bytes);
        if(!"moneyflow_dc".equals(proof.path("endpoint").asText())||!proof.path("sourceComplete").asBoolean(false)||!expectedDate.toString().equals(proof.path("tradeDate").asText())
                ||proof.path("apiMaximumRows").asInt(-1)!=API_ROW_CAP||!proof.path("rawRows").isArray()||proof.path("returnedRows").asInt(-1)!=proof.path("rawRows").size()
                ||proof.path("rawRows").size()>MAX_DAILY_ROWS||!json.valueToTree(FIELDS).equals(proof.path("fields"))||!expectedDate.format(BASIC).equals(proof.path("parameters").path("trade_date").asText()))
            throw new IllegalStateException("moneyflow_dc receipt differs from frozen endpoint/date/cap/field contract");
        verifyPageProofs(proof);
        List<Map<String,JsonNode>> raw=json.convertValue(proof.path("rawRows"),new TypeReference<>(){}); var rows=new ArrayList<MoneyflowDc>(); var keys=new HashSet<MoneyflowDcKey>();
        for(var item:raw) { validateRow(item,expectedDate.format(BASIC)); var row=new MoneyflowDcMapper().fromSource(new MoneyflowDcMapper().dto(item)); if(!keys.add(row.key()))throw new IllegalStateException("moneyflow_dc receipt contains duplicate business key"); rows.add(row); }
        var sorted=new ArrayList<>(raw); sorted.sort(Comparator.comparing((Map<String,JsonNode> row)->value(row,"ts_code")).thenComparing(row->value(row,"trade_date")));
        if(!json.valueToTree(raw).equals(json.valueToTree(sorted)))throw new IllegalStateException("moneyflow_dc receipt rows are not canonical-key ordered");
        return new SyncJobRunner.Page<>(rows,fingerprint,path.toString(),expectedDate.format(BASIC));
    }
    private static void verifyPageProofs(JsonNode proof){
        if(proof.path("receiptVersion").asInt()!=2||proof.path("dailyRowBound").asInt()!=MAX_DAILY_ROWS||proof.path("pageSize").asInt()!=PAGE_SIZE)
            throw new IllegalStateException("D026 receipt paging contract mismatch");
        var pages=proof.path("pageProofs");if(!pages.isArray()||pages.isEmpty()||pages.size()>MAX_PAGES)throw new IllegalStateException("D026 missing bounded page proofs");
        var observed=new HashSet<String>();int offset=0;
        for(int i=0;i<pages.size();i++){var page=pages.get(i);int count=page.path("returnedRows").asInt(-1);var keys=page.path("keys");
            if(page.path("offset").asInt(-1)!=offset||page.path("limit").asInt(-1)!=PAGE_SIZE||count<0||count>PAGE_SIZE
                    ||!keys.isArray()||keys.size()!=count||(i==pages.size()-1?count>=PAGE_SIZE:count!=PAGE_SIZE))
                throw new IllegalStateException("D026 offset/limit/terminal-page proof invalid");
            for(var key:keys)if(!key.isTextual()||!observed.add(key.asText()))throw new IllegalStateException("D026 page key repeated");offset=Math.addExact(offset,count);
        }
        var expected=new HashSet<String>();for(var row:proof.path("rawRows"))if(!expected.add(row.path("ts_code").asText()+"/"+row.path("trade_date").asText()))throw new IllegalStateException("D026 raw key repeated");
        if(offset>MAX_DAILY_ROWS||offset!=proof.path("returnedRows").asInt(-1)||!observed.equals(expected))throw new IllegalStateException("D026 page inventory differs from complete raw source");
    }
    private static void validateRow(Map<String,JsonNode> row,String date) {
        if(!row.keySet().equals(new HashSet<>(FIELDS)))throw new IllegalArgumentException("moneyflow_dc fields differ from frozen 15-column contract");
        String code=value(row,"ts_code"); if(!code.matches("[0-9]{6}\\.(SZ|SH|BJ)"))throw new IllegalArgumentException("moneyflow_dc returned a non-SZ/SH/BJ code");
        if(!date.equals(value(row,"trade_date")))throw new IllegalArgumentException("moneyflow_dc row outside requested trade_date");
        JsonNode name=row.get("name");if(name!=null&&!name.isNull()&&!name.isTextual())throw new IllegalArgumentException("moneyflow_dc name must be string or null");
        for(String field:FIELDS)if(!Set.of("ts_code","trade_date","name").contains(field)){JsonNode v=row.get(field);if(v!=null&&!v.isNull()&&!v.isNumber()&&!v.isTextual())throw new IllegalArgumentException("moneyflow_dc numeric scalar required: "+field);}
    }
    private static byte[] body(LocalDate date,Map<String,Object> params,List<Map<String,JsonNode>> rows,String version,boolean complete,Exception failure,List<Map<String,Object>> pageProofs)throws Exception {
        var json=JobDefinitionJson.canonicalMapper();var body=new LinkedHashMap<String,Object>();body.put("receiptVersion",2);body.put("dailyRowBound",MAX_DAILY_ROWS);body.put("pageSize",PAGE_SIZE);body.put("pageProofs",pageProofs);body.put("sourceKind","tushare");body.put("endpoint","moneyflow_dc");body.put("parameters",params);body.put("fields",FIELDS);body.put("tradeDate",date.toString());body.put("apiMaximumRows",API_ROW_CAP);body.put("returnedRows",rows.size());body.put("rawRows",rows);body.put("sourceComplete",complete);if(version!=null)body.put("sourceVersion",version);if(failure!=null)body.put("failureType",failure.getClass().getSimpleName());return json.writeValueAsBytes(body);
    }
    private void persistUnverified(LocalDate date,Map<String,Object> params,List<Map<String,JsonNode>> rows,String version,Exception failure)throws Exception { byte[] bytes=body(date,params,rows,version,false,failure,List.of());if(bytes.length>MAX_EVIDENCE_BYTES)throw new IllegalStateException("Unverified moneyflow_dc response exceeds evidence bound",failure);Files.createDirectories(evidenceRoot);persist(evidenceRoot.resolve("unverified-"+date.format(BASIC)+"-"+sha(bytes)+".json"),bytes); }
    private static void persist(Path path,byte[] bytes)throws Exception { try{FileEvidenceStore.writeNew(path,bytes);}catch(FileAlreadyExistsException exists){if(!Arrays.equals(FileEvidenceStore.readBounded(path, Math.max(1, bytes.length),
                    () -> new IllegalStateException("Conflicting deterministic moneyflow_dc receipt", exists)),bytes))throw new IllegalStateException("Conflicting deterministic moneyflow_dc receipt",exists);} }
    private static String value(Map<String,JsonNode> row,String name){JsonNode v=row.get(name);return v==null||v.isNull()?"":v.asText();}
    private static String sha(byte[] bytes)throws Exception{return FileEvidenceStore.sha256(bytes);}
}
