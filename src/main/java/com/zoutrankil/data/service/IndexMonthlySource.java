package com.zoutrankil.data.service;

import com.zoutrankil.data.repository.FileEvidenceStore;
import com.zoutrankil.data.domain.policy.IndexMonthlyUniverse;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.mapper.IndexMonthlyMapper;
import java.nio.file.*;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.function.BooleanSupplier;

/** One historical monthly provider code x bounded date interval; there is no offset or page parameter. */
public final class IndexMonthlySource {
    public static final int MAX_WINDOW_DAYS=3660;
    /** Conservative local truncation guard; this is not claimed as a published Tushare quota. */
    public static final int CLIENT_ROW_CAP=1000;
    public static final int MAX_EVIDENCE_BYTES=16*1024*1024;
    public static final List<String> FIELDS=IndexMonthlyMapper.FIELDS;
    public static final PageContract CONTRACT=new PageContract("index_monthly",FIELDS,List.of("ts_code","trade_date"),
            Set.of("ts_code","start_date","end_date"),PageContract.Paging.NONE,PageContract.Completion.SHORT_PAGE,
            null,null,CLIENT_ROW_CAP,CLIENT_ROW_CAP,1,CLIENT_ROW_CAP,
            "D022 makes one no-offset request per frozen provider code and at most 3660 calendar days (ten years), admitting no more than 120 monthly observations. A separate 1000-row client guard fails closed at cap; it is not represented as an official API quota.");
    private final TusharePageService pages;private final IndexMonthlyMapper mapper;private final Path evidenceRoot;
    public IndexMonthlySource(TusharePageService pages,IndexMonthlyMapper mapper,Path evidenceRoot){this.pages=Objects.requireNonNull(pages);this.mapper=Objects.requireNonNull(mapper);this.evidenceRoot=Objects.requireNonNull(evidenceRoot).toAbsolutePath().normalize();}
    public SyncJobRunner.Page<IndexMonthly> fetch(String code,LocalDate from,LocalDate to,Instant observedAt,BooleanSupplier cancelled)throws Exception {
        var index=requireIndex(code);requireWindow(from,to);Objects.requireNonNull(observedAt);com.zoutrankil.data.domain.temporal.TemporalValues.requirePrecision(observedAt,com.zoutrankil.data.domain.temporal.TemporalValues.Precision.MICROS);
        String start=from.format(DateTimeFormatter.BASIC_ISO_DATE),end=to.format(DateTimeFormatter.BASIC_ISO_DATE);
        var params=new LinkedHashMap<String,Object>();params.put("ts_code",index.providerCode());params.put("start_date",start);params.put("end_date",end);
        var raw=new ArrayList<Map<String,JsonNode>>();var captured=new PageExecutor.Page[1];PageExecutor.Completed complete;
        try {
            var fetcher=pages.fetcher(CONTRACT,cancelled);
            complete=new PageExecutor().execute(CONTRACT,params,request->{var response=fetcher.fetch(request);captured[0]=response;return response;},
                    (page,receipt)->raw.addAll(page.rows()),row->validate(row,index,from,to,observedAt),cancelled);
        } catch(Exception failure){if(captured[0]!=null)try{persistIncomplete(index,from,to,observedAt,params,captured[0],failure);}catch(Exception evidenceFailure){failure.addSuppressed(evidenceFailure);}throw failure;}
        if(complete.pages()!=1||complete.rows()!=raw.size()||captured[0]==null)throw new IllegalStateException("D022 requires exactly one completed response per code/window");
        raw.sort(Comparator.comparing((Map<String,JsonNode> row)->row.get("ts_code").asText()).thenComparing(row->row.get("trade_date").asText()));
        var rows=raw.stream().map(mapper::dto).map(dto->mapper.fromSource(dto,index,observedAt)).toList();
        var keys=new HashSet<IndexMonthlyKey>();
        for(var row:rows)if(!row.tsCode().equals(index.providerCode())||row.tradeDate().isBefore(from)||row.tradeDate().isAfter(to)||!keys.add(row.key()))
            throw new IllegalStateException("D022 source contains duplicate/out-of-range complete key");
        var body=new LinkedHashMap<String,Object>();body.put("sourceKind","tushare");body.put("endpoint","index_monthly");body.put("schemaVersion",1);
        body.put("providerCode",index.providerCode());body.put("canonicalCode",index.canonicalCode());body.put("layer",index.layer());body.put("bucket",index.bucket());
        body.put("from",from);body.put("to",to);body.put("observedAt",observedAt);body.put("parameters",params);body.put("fields",FIELDS);
        body.put("rawRows",raw);body.put("returnedRows",rows.size());body.put("sourceComplete",true);body.put("clientRowCap",CLIENT_ROW_CAP);
        body.put("sourceVersion",complete.sourceVersion());
        byte[] bytes=JobDefinitionJson.canonicalMapper().writeValueAsBytes(body);requireEvidence(bytes);
        Files.createDirectories(evidenceRoot);Path receipt=evidenceRoot.resolve("index-monthly-"+index.providerCode().replace('.','-')+"-"+start+"-"+end+"-"+UUID.randomUUID()+".json");
        FileEvidenceStore.writeNew(receipt,bytes);return new SyncJobRunner.Page<>(rows,sha256(bytes),receipt.toString(),index.providerCode());
    }
    public static SyncJobRunner.Page<IndexMonthly> reopen(Path receipt,String fingerprint,String expectedCode,LocalDate from,LocalDate to,Instant observedAt)throws Exception {
        var index=requireIndex(expectedCode);requireWindow(from,to);Objects.requireNonNull(observedAt);
        if(fingerprint==null||!fingerprint.matches("[0-9a-f]{64}")||!Files.isRegularFile(receipt)||Files.size(receipt)>MAX_EVIDENCE_BYTES)throw new IllegalArgumentException("Bounded D022 receipt/SHA-256 required");
        byte[] bytes=FileEvidenceStore.readBounded(receipt, MAX_EVIDENCE_BYTES,
                () -> new IllegalArgumentException("Bounded D022 receipt/SHA-256 required"));if(!sha256(bytes).equals(fingerprint))throw new IllegalStateException("D022 immutable source receipt fingerprint changed");
        var json=JobDefinitionJson.mapper();JsonNode proof=json.readTree(bytes);
        if(!"tushare".equals(proof.path("sourceKind").asText())||!"index_monthly".equals(proof.path("endpoint").asText())||proof.path("schemaVersion").asInt(-1)!=1
                ||!index.providerCode().equals(proof.path("providerCode").asText())||!index.canonicalCode().equals(proof.path("canonicalCode").asText())
                ||!index.layer().equals(proof.path("layer").asText())||!index.bucket().equals(proof.path("bucket").asText())
                ||!from.toString().equals(proof.path("from").asText())||!to.toString().equals(proof.path("to").asText())
                ||!observedAt.toString().equals(proof.path("observedAt").asText())||!json.valueToTree(FIELDS).equals(proof.path("fields"))
                ||!proof.path("sourceComplete").asBoolean(false)||proof.path("returnedRows").asInt(-1)!=proof.path("rawRows").size()
                ||proof.path("clientRowCap").asInt(-1)!=CLIENT_ROW_CAP)throw new IllegalStateException("D022 receipt source scope/completion differs");
        String start=from.format(DateTimeFormatter.BASIC_ISO_DATE),end=to.format(DateTimeFormatter.BASIC_ISO_DATE);JsonNode params=proof.path("parameters");
        if(!index.providerCode().equals(params.path("ts_code").asText())||!start.equals(params.path("start_date").asText())||!end.equals(params.path("end_date").asText()))
            throw new IllegalStateException("D022 frozen request parameters differ from receipt");
        var raw=json.convertValue(proof.path("rawRows"),new TypeReference<List<Map<String,JsonNode>>>(){});var rows=new ArrayList<IndexMonthly>();var keys=new HashSet<IndexMonthlyKey>();
        var mapper=new IndexMonthlyMapper();for(var row:raw){validate(row,index,from,to,observedAt);var mapped=mapper.fromSource(mapper.dto(row),index,observedAt);if(!keys.add(mapped.key()))throw new IllegalStateException("Duplicate D022 receipt business key");rows.add(mapped);}
        return new SyncJobRunner.Page<>(rows,fingerprint,receipt.toAbsolutePath().normalize().toString(),index.providerCode());
    }
    private static void validate(Map<String,JsonNode> row,IndexMonthlyUniverse.Index index,LocalDate from,LocalDate to,Instant observedAt){
        var mapped=new IndexMonthlyMapper().fromSource(new IndexMonthlyMapper().dto(row),index,observedAt);
        if(mapped.tradeDate().isBefore(from)||mapped.tradeDate().isAfter(to))throw new IllegalArgumentException("D022 source date outside frozen window");
    }
    private void persistIncomplete(IndexMonthlyUniverse.Index index,LocalDate from,LocalDate to,Instant observedAt,Map<String,Object> params,PageExecutor.Page page,Exception failure)throws Exception {
        var body=new LinkedHashMap<String,Object>();body.put("sourceKind","tushare");body.put("endpoint","index_monthly");body.put("providerCode",index.providerCode());body.put("canonicalCode",index.canonicalCode());
        body.put("layer",index.layer());body.put("bucket",index.bucket());body.put("from",from);body.put("to",to);body.put("observedAt",observedAt);body.put("parameters",params);body.put("fields",FIELDS);
        body.put("responseRows",page.rows().size());body.put("rawRows",page.rows());body.put("sourceComplete",false);body.put("clientRowCap",CLIENT_ROW_CAP);
        body.put("evidenceStatus","unverified_raw_response");body.put("explicitEnd",page.explicitEnd());body.put("failureCategory",failure.getClass().getSimpleName());body.put("failure",failure.getMessage());
        if(page.sourceVersion()!=null)body.put("sourceVersion",page.sourceVersion());byte[] bytes=JobDefinitionJson.canonicalMapper().writeValueAsBytes(body);requireEvidence(bytes);
        Files.createDirectories(evidenceRoot);FileEvidenceStore.writeNew(evidenceRoot.resolve("incomplete-"+UUID.randomUUID()+".json"),bytes);
    }
    public static IndexMonthlyUniverse.Index requireIndex(String providerCode){var index=IndexMonthlyUniverse.resolveProvider(providerCode);if(index==null)throw new IllegalArgumentException("D022 code must be one of the frozen 55 monthly-enabled Python indices");return index;}
    public static void requireWindow(LocalDate from,LocalDate to){Objects.requireNonNull(from);Objects.requireNonNull(to);long days=ChronoUnit.DAYS.between(from,to)+1;if(days<1||days>MAX_WINDOW_DAYS)throw new IllegalArgumentException("D022 range must be 1..3660 calendar days");}
    private static void requireEvidence(byte[] bytes){if(bytes.length>MAX_EVIDENCE_BYTES)throw new IllegalArgumentException("D022 source evidence exceeds 16 MiB");}
    private static String sha256(byte[] bytes)throws Exception{return FileEvidenceStore.sha256(bytes);}
}
