package com.zoutrankil.data.service;

import com.zoutrankil.data.repository.FileEvidenceStore;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.mapper.MoneyflowMapper;
import java.nio.file.*;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.function.BooleanSupplier;

/** One bounded, non-paged Tushare moneyflow(trade_date=YYYYMMDD) request per open date. */
public final class MoneyflowSource {
    public static final int API_ROW_CAP=6_000,MAX_EVIDENCE_BYTES=16*1024*1024;
    public static final List<String> FIELDS=List.of("ts_code","trade_date","buy_sm_vol","buy_sm_amount","sell_sm_vol","sell_sm_amount",
            "buy_md_vol","buy_md_amount","sell_md_vol","sell_md_amount","buy_lg_vol","buy_lg_amount","sell_lg_vol","sell_lg_amount",
            "buy_elg_vol","buy_elg_amount","sell_elg_vol","sell_elg_amount","net_mf_vol","net_mf_amount");
    public static final PageContract CONTRACT=new PageContract("moneyflow",FIELDS,List.of("ts_code","trade_date"),Set.of("trade_date"),
            PageContract.Paging.NONE,PageContract.Completion.SHORT_PAGE,null,null,API_ROW_CAP,API_ROW_CAP,1,API_ROW_CAP,
            "Official Tushare moneyflow supports one trade_date full-market request, maximum 6000 rows; it has no documented offset cursor. A response at the cap is treated as possibly truncated.");
    private static final DateTimeFormatter BASIC=DateTimeFormatter.BASIC_ISO_DATE;
    private final TusharePageService pages;private final MoneyflowMapper mapper=new MoneyflowMapper();private final Path evidenceRoot;
    public MoneyflowSource(TusharePageService pages,Path evidenceRoot){this.pages=Objects.requireNonNull(pages);this.evidenceRoot=Objects.requireNonNull(evidenceRoot).toAbsolutePath().normalize();}

    public SyncJobRunner.Page<Moneyflow> fetch(LocalDate date,BooleanSupplier cancelled)throws Exception{
        Objects.requireNonNull(date);Objects.requireNonNull(cancelled);String basic=date.format(BASIC);var params=Map.<String,Object>of("trade_date",basic);
        var raw=new ArrayList<Map<String,JsonNode>>();var fetcher=pages.fetcher(CONTRACT,cancelled);PageExecutor.Completed complete;
        try{complete=new PageExecutor().execute(CONTRACT,params,request->{
                    PageExecutor.Page response=fetcher.fetch(request);raw.addAll(response.rows());return response;},
                (page,receipt)->{},row->validateRow(row,basic),cancelled);
        }catch(Exception failure){persistUnverified(date,params,raw,null,failure);throw failure;}
        raw.sort(Comparator.comparing((Map<String,JsonNode> row)->value(row,"ts_code")).thenComparing(row->value(row,"trade_date")));
        if(complete.pages()!=1||complete.rows()!=raw.size()||raw.size()>=API_ROW_CAP){var failure=new IllegalStateException("moneyflow response is incomplete or reaches its 6000-row cap");persistUnverified(date,params,raw,complete.sourceVersion(),failure);throw failure;}
        var typed=new ArrayList<Moneyflow>(raw.size());var keys=new HashSet<MoneyflowKey>();
        for(var item:raw){Moneyflow row=mapper.fromSource(mapper.dto(item));if(!row.tradeDate().equals(date)||!keys.add(row.key())){var failure=new IllegalArgumentException("moneyflow duplicate key or row outside its frozen date");persistUnverified(date,params,raw,complete.sourceVersion(),failure);throw failure;}typed.add(row);}
        byte[] bytes=body(date,params,raw,complete.sourceVersion(),true,null);if(bytes.length>MAX_EVIDENCE_BYTES)throw new IllegalStateException("moneyflow raw source receipt exceeds 16 MiB evidence limit");
        String fingerprint=sha(bytes);Files.createDirectories(evidenceRoot);Path receipt=evidenceRoot.resolve("moneyflow-"+basic+"-"+fingerprint+".json");persist(receipt,bytes);
        return new SyncJobRunner.Page<>(typed,fingerprint,receipt.toString(),basic);
    }

    public static SyncJobRunner.Page<Moneyflow> reopen(Path receipt,String fingerprint,LocalDate expectedDate)throws Exception{
        Path path=receipt.toAbsolutePath().normalize();if(fingerprint==null||!fingerprint.matches("[0-9a-f]{64}")||!Files.isRegularFile(path)||Files.size(path)>MAX_EVIDENCE_BYTES)throw new IllegalArgumentException("Bounded moneyflow receipt and SHA-256 required");
        byte[] bytes=FileEvidenceStore.readBounded(path, MAX_EVIDENCE_BYTES,
                () -> new IllegalArgumentException("Bounded moneyflow receipt and SHA-256 required"));if(!sha(bytes).equals(fingerprint))throw new IllegalStateException("moneyflow source receipt SHA mismatch");var json=JobDefinitionJson.mapper();JsonNode proof=json.readTree(bytes);
        if(!"moneyflow".equals(proof.path("endpoint").asText())||!proof.path("sourceComplete").asBoolean(false)
                ||!expectedDate.toString().equals(proof.path("tradeDate").asText())||proof.path("apiMaximumRows").asInt(-1)!=API_ROW_CAP
                ||!proof.path("rawRows").isArray()||proof.path("returnedRows").asInt(-1)!=proof.path("rawRows").size()
                ||proof.path("rawRows").size()>=API_ROW_CAP||!json.valueToTree(FIELDS).equals(proof.path("fields"))
                ||!expectedDate.format(BASIC).equals(proof.path("parameters").path("trade_date").asText()))throw new IllegalStateException("moneyflow receipt differs from frozen endpoint/date/cap/field contract");
        List<Map<String,JsonNode>> raw=json.convertValue(proof.path("rawRows"),new TypeReference<>(){});var rows=new ArrayList<Moneyflow>();var keys=new HashSet<MoneyflowKey>();
        for(var item:raw){validateRow(item,expectedDate.format(BASIC));var row=new MoneyflowMapper().fromSource(new MoneyflowMapper().dto(item));if(!keys.add(row.key()))throw new IllegalStateException("moneyflow receipt contains a duplicate business key");rows.add(row);}
        var sorted=new ArrayList<>(raw);sorted.sort(Comparator.comparing((Map<String,JsonNode> row)->value(row,"ts_code")).thenComparing(row->value(row,"trade_date")));
        if(!json.valueToTree(raw).equals(json.valueToTree(sorted)))throw new IllegalStateException("moneyflow receipt rows are not in canonical key order");
        return new SyncJobRunner.Page<>(rows,fingerprint,path.toString(),expectedDate.format(BASIC));
    }
    private static void validateRow(Map<String,JsonNode> row,String date){
        if(!row.keySet().equals(new HashSet<>(FIELDS)))throw new IllegalArgumentException("moneyflow response fields differ from the frozen 20-column contract");
        String code=value(row,"ts_code");if(!code.matches("[0-9]{6}\\.(SZ|SH|BJ)"))throw new IllegalArgumentException("moneyflow returned a non-SZ/SH/BJ A-share code");
        String dateValue=value(row,"trade_date");if(!date.equals(dateValue))throw new IllegalArgumentException("moneyflow row outside requested trade_date");
        for(String field:FIELDS)if(!field.equals("ts_code")&&!field.equals("trade_date")){JsonNode v=row.get(field);if(v!=null&&!v.isNull()&&!v.isNumber()&&!v.isTextual())throw new IllegalArgumentException("moneyflow numeric scalar required: "+field);}
    }
    private static byte[] body(LocalDate date,Map<String,Object> params,List<Map<String,JsonNode>> rows,String version,boolean complete,Exception failure)throws Exception{
        var json=JobDefinitionJson.canonicalMapper();var body=new LinkedHashMap<String,Object>();body.put("sourceKind","tushare");body.put("endpoint","moneyflow");body.put("parameters",params);body.put("fields",FIELDS);body.put("tradeDate",date.toString());body.put("apiMaximumRows",API_ROW_CAP);body.put("returnedRows",rows.size());body.put("rawRows",rows);body.put("sourceComplete",complete);if(version!=null)body.put("sourceVersion",version);if(failure!=null)body.put("failureType",failure.getClass().getSimpleName());return json.writeValueAsBytes(body);
    }
    private void persistUnverified(LocalDate date,Map<String,Object> params,List<Map<String,JsonNode>> rows,String version,Exception failure)throws Exception{
        byte[] bytes=body(date,params,rows,version,false,failure);if(bytes.length>MAX_EVIDENCE_BYTES)throw new IllegalStateException("Unverified moneyflow raw response exceeds evidence bound",failure);Files.createDirectories(evidenceRoot);Path file=evidenceRoot.resolve("unverified-"+date.format(BASIC)+"-"+sha(bytes)+".json");persist(file,bytes);
    }
    private static void persist(Path path,byte[] bytes)throws Exception{try{FileEvidenceStore.writeNew(path,bytes);}catch(FileAlreadyExistsException exists){if(!Arrays.equals(FileEvidenceStore.readBounded(path, Math.max(1, bytes.length),
                    () -> new IllegalStateException("Conflicting deterministic moneyflow receipt", exists)),bytes))throw new IllegalStateException("Conflicting deterministic moneyflow receipt",exists);}}
    private static String value(Map<String,JsonNode> row,String name){JsonNode v=row.get(name);return v==null||v.isNull()?"":v.asText();}
    private static String sha(byte[] bytes)throws Exception{return FileEvidenceStore.sha256(bytes);}
}
