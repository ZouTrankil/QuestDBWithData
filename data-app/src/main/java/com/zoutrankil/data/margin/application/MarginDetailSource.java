package com.zoutrankil.data.margin.application;

import com.zoutrankil.data.margin.domain.MarginDetailLimits;

import com.zoutrankil.data.service.*;

import com.zoutrankil.data.repository.FileEvidenceStore;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.zoutrankil.data.domain.JobDefinitionJson;
import com.zoutrankil.data.domain.MarginDetail;
import com.zoutrankil.data.domain.MarginDetailKey;
import com.zoutrankil.data.domain.PageContract;
import com.zoutrankil.data.margin.mapper.MarginDetailMapper;
import java.math.BigDecimal;
import java.nio.file.*;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.function.BooleanSupplier;

/** One full-market, unpaged Tushare margin_detail(trade_date=YYYYMMDD) request per SSE session. */
public final class MarginDetailSource {
    public static final int API_ROW_CAP=MarginDetailLimits.API_ROW_CAP,MAX_EVIDENCE_BYTES=MarginDetailLimits.MAX_EVIDENCE_BYTES;
    public static final List<String> FIELDS=List.of("trade_date","ts_code","name","rzye","rzmre","rzche","rqye","rqyl","rqchl","rqmcl","rzrqye");
    public static final PageContract CONTRACT=new PageContract("margin_detail",FIELDS,List.of("ts_code","trade_date"),Set.of("trade_date"),
            PageContract.Paging.NONE,PageContract.Completion.SHORT_PAGE,null,null,API_ROW_CAP,API_ROW_CAP,1,API_ROW_CAP,
            "Python fetch_margin_detail_tushare calls pro.margin_detail(trade_date=YYYYMMDD). Official Tushare doc 59 states a 6000-row single-request maximum; no offset/cursor contract is used. Responses at the cap fail closed.");
    private static final DateTimeFormatter BASIC=DateTimeFormatter.BASIC_ISO_DATE;
    private static final List<String> NUMERIC=List.of("rzye","rzmre","rzche","rqye","rqyl","rqchl","rqmcl","rzrqye");
    private final TusharePageService pages;private final Path evidenceRoot;private final MarginDetailMapper mapper=new MarginDetailMapper();
    public MarginDetailSource(TusharePageService pages,Path evidenceRoot){this.pages=Objects.requireNonNull(pages);this.evidenceRoot=Objects.requireNonNull(evidenceRoot).toAbsolutePath().normalize();}

    public SyncJobRunner.Page<MarginDetail> fetch(LocalDate date,BooleanSupplier cancelled)throws Exception{
        Objects.requireNonNull(date);Objects.requireNonNull(cancelled);String basic=date.format(BASIC);var params=Map.<String,Object>of("trade_date",basic);
        var raw=new ArrayList<Map<String,JsonNode>>();PageExecutor.Completed completed;
        var fetcher=pages.fetcher(CONTRACT,cancelled);
        try{completed=new PageExecutor().execute(CONTRACT,params,request->{var response=fetcher.fetch(request);raw.addAll(response.rows());return response;},
                    (page,receipt)->{},row->validateRow(row,basic),cancelled);}
        catch(Exception failure){persistUnverified(date,params,raw,null,failure);throw failure;}
        raw.sort(Comparator.comparing((Map<String,JsonNode> row)->value(row,"ts_code")).thenComparing(row->value(row,"trade_date")));
        if(completed.pages()!=1||completed.rows()!=raw.size()||raw.size()>=API_ROW_CAP){var failure=new IllegalStateException("D029 margin_detail response is incomplete or reaches its 6000-row cap");persistUnverified(date,params,raw,completed.sourceVersion(),failure);throw failure;}
        var rows=new ArrayList<MarginDetail>();var seen=new HashSet<MarginDetailKey>();int footers=0;
        try{
            for(var item:raw){if(isFooter(item,basic)){footers++;continue;}var row=mapper.fromSource(mapper.dto(item));
                if(!row.tradeDate().equals(date)||!seen.add(row.key()))throw new IllegalArgumentException("D029 duplicate natural key or source row outside frozen trade_date");rows.add(row);}
            // Python records an empty/fully-filtered partition as skipped and unverified; it is not a checkpoint.
            if(rows.isEmpty())throw new IllegalStateException("D029 empty margin_detail session is unverified under the inspected Python sync contract");
            requireFullMarketCoverage(exchangeCounts(raw,basic));
            byte[] bytes=body(date,params,raw,completed.sourceVersion(),rows.size(),footers,true,null);if(bytes.length>MAX_EVIDENCE_BYTES)throw new IllegalStateException("D029 raw receipt exceeds 32 MiB bound");
            String fingerprint=sha(bytes);Files.createDirectories(evidenceRoot);Path receipt=evidenceRoot.resolve("margin-detail-"+basic+"-"+fingerprint+".json");persist(receipt,bytes);
            return new SyncJobRunner.Page<>(List.copyOf(rows),fingerprint,receipt.toString(),basic);
        }catch(Exception failure){persistUnverified(date,params,raw,completed.sourceVersion(),failure);throw failure;}
    }

    public static SyncJobRunner.Page<MarginDetail> reopen(Path receipt,String fingerprint,LocalDate expectedDate)throws Exception{
        Path path=receipt.toAbsolutePath().normalize();if(fingerprint==null||!fingerprint.matches("[0-9a-f]{64}")||!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS)
                ||Files.isSymbolicLink(path)||Files.size(path)<1||Files.size(path)>MAX_EVIDENCE_BYTES)throw new IllegalArgumentException("D029 bounded raw receipt and SHA-256 required");
        byte[] bytes=FileEvidenceStore.readBounded(path, MAX_EVIDENCE_BYTES,
                () -> new IllegalArgumentException("D029 bounded raw receipt and SHA-256 required"));if(!sha(bytes).equals(fingerprint))throw new IllegalStateException("D029 source receipt SHA-256 mismatch");var json=JobDefinitionJson.mapper();JsonNode proof=json.readTree(bytes);String basic=expectedDate.format(BASIC);
        if(!"tushare".equals(proof.path("sourceKind").asText())||!"margin_detail".equals(proof.path("endpoint").asText())
                ||proof.path("sourceContractVersion").asInt(-1)!=1||!proof.path("sourceComplete").asBoolean(false)
                ||!expectedDate.toString().equals(proof.path("tradeDate").asText())||proof.path("apiMaximumRows").asInt(-1)!=API_ROW_CAP
                ||!json.valueToTree(FIELDS).equals(proof.path("fields"))||!basic.equals(proof.path("parameters").path("trade_date").asText())
                ||!proof.path("rawRows").isArray()||proof.path("returnedRows").asInt(-1)!=proof.path("rawRows").size()
                ||proof.path("normalizedRows").asInt(-1)<1||proof.path("rawRows").size()>=API_ROW_CAP)
            throw new IllegalStateException("D029 source receipt differs from frozen endpoint/date/fields/cap contract");
        List<Map<String,JsonNode>> raw=json.convertValue(proof.path("rawRows"),new TypeReference<>(){});var rows=new ArrayList<MarginDetail>();var keys=new HashSet<MarginDetailKey>();int footer=0;
        for(var item:raw){validateRow(item,basic);if(isFooter(item,basic)){footer++;continue;}var row=new MarginDetailMapper().fromSource(new MarginDetailMapper().dto(item));if(!keys.add(row.key()))throw new IllegalStateException("D029 source receipt contains duplicate natural key");rows.add(row);}
        if(footer!=proof.path("excludedDateFooters").asInt(-1)||rows.size()!=proof.path("normalizedRows").asInt(-1)||rows.isEmpty())throw new IllegalStateException("D029 normalized receipt totals differ from raw source rows");
        var counts=exchangeCounts(raw,basic);requireFullMarketCoverage(counts);
        if(proof.has("exchangeCounts")&&!json.valueToTree(counts).equals(proof.path("exchangeCounts")))throw new IllegalStateException("D029 source receipt exchange counts differ from its raw response");
        if(proof.has("fullMarketCoverageVerified")&&!proof.path("fullMarketCoverageVerified").asBoolean(false))throw new IllegalStateException("D029 source receipt has unverified market coverage");
        var canonical=new ArrayList<>(raw);canonical.sort(Comparator.comparing((Map<String,JsonNode> row)->value(row,"ts_code")).thenComparing(row->value(row,"trade_date")));
        if(!json.valueToTree(canonical).equals(json.valueToTree(raw)))throw new IllegalStateException("D029 raw receipt rows are not deterministically ordered");
        return new SyncJobRunner.Page<>(List.copyOf(rows),fingerprint,path.toString(),basic);
    }

    private static void validateRow(Map<String,JsonNode> row,String date){
        if(row==null||!row.keySet().equals(new HashSet<>(FIELDS)))throw new IllegalArgumentException("D029 provider fields differ from the frozen 11-column contract");
        if(!date.equals(value(row,"trade_date")))throw new IllegalArgumentException("D029 source row is outside requested trade_date");
        String code=value(row,"ts_code");boolean footer=code.equals(footerLabel(date));if(!footer&&!code.matches("[0-9]{6}\\.(SH|SZ|BJ)"))throw new IllegalArgumentException("D029 invalid mainland security code");
        JsonNode name=row.get("name");if(name!=null&&!name.isNull()&&!name.isTextual())throw new IllegalArgumentException("D029 name must be text or null");
        for(String field:NUMERIC){JsonNode value=row.get(field);if(value==null||value.isNull()){if(footer)continue;if(Set.of("rzye","rzmre").contains(field))throw new IllegalArgumentException("D029 required financing balance is null: "+field);continue;}
            if(!value.isNumber()&&!value.isTextual())throw new IllegalArgumentException("D029 numeric scalar required: "+field);
            try{double number=new BigDecimal(value.asText().strip()).doubleValue();if(!Double.isFinite(number))throw new NumberFormatException();if(Set.of("rzye","rzmre","rqye","rqyl","rqmcl","rzrqye").contains(field)&&number<0)throw new NumberFormatException();}
            catch(RuntimeException invalid){throw new IllegalArgumentException("D029 invalid/nonnegative numeric value required for "+field,invalid);}}
        if(footer&&NUMERIC.stream().anyMatch(f->row.get(f)!=null&&!row.get(f).isNull()))throw new IllegalArgumentException("D029 date footer is not fully empty");
    }
    private static boolean isFooter(Map<String,JsonNode> row,String date){return footerLabel(date).equals(value(row,"ts_code"));}
    /** Presence is a minimum publication check, not a claim that a prior-date roster is today's complete universe. */
    private static Map<String,Integer> exchangeCounts(List<Map<String,JsonNode>> rows,String basic){
        var counts=new LinkedHashMap<String,Integer>();counts.put("SH",0);counts.put("SZ",0);counts.put("BJ",0);
        for(var row:rows){if(isFooter(row,basic))continue;String code=value(row,"ts_code"),exchange=code.substring(code.lastIndexOf('.')+1);
            if(counts.containsKey(exchange))counts.compute(exchange,(key,count)->Math.addExact(count,1));}
        return Collections.unmodifiableMap(counts);
    }
    private static void requireFullMarketCoverage(Map<String,Integer> counts){
        if(counts.getOrDefault("SH",0)<1||counts.getOrDefault("SZ",0)<1)
            throw new IllegalStateException("D029 full-market margin_detail publication is unverified: required SH/SZ coverage missing; exchangeCounts="+counts);
    }
    private static String footerLabel(String basic){LocalDate date=LocalDate.parse(basic,BASIC);return "日期："+date+".BJ";}
    private static String value(Map<String,JsonNode> row,String field){JsonNode value=row==null?null:row.get(field);return value==null||value.isNull()?"":value.asText();}
    private static byte[] body(LocalDate date,Map<String,Object> params,List<Map<String,JsonNode>> rows,String sourceVersion,int normalized,int footers,boolean complete,Exception failure)throws Exception{
        var json=JobDefinitionJson.canonicalMapper();var body=new LinkedHashMap<String,Object>();body.put("sourceKind","tushare");body.put("endpoint","margin_detail");body.put("sourceContractVersion",1);
        body.put("parameters",params);body.put("fields",FIELDS);body.put("tradeDate",date.toString());body.put("apiMaximumRows",API_ROW_CAP);body.put("returnedRows",rows.size());body.put("normalizedRows",normalized);body.put("excludedDateFooters",footers);body.put("rawRows",rows);body.put("sourceComplete",complete);
        var counts=exchangeCounts(rows,date.format(BASIC));body.put("exchangeCounts",counts);body.put("marketCoveragePolicy","required_sh_sz_presence_bj_observed");
        body.put("fullMarketCoverageVerified",complete&&counts.get("SH")>0&&counts.get("SZ")>0);
        if(sourceVersion!=null)body.put("sourceVersion",sourceVersion);if(failure!=null)body.put("failureType",failure.getClass().getSimpleName());return json.writeValueAsBytes(body);
    }
    private void persistUnverified(LocalDate date,Map<String,Object> params,List<Map<String,JsonNode>> rows,String sourceVersion,Exception failure)throws Exception{
        int footers=(int)rows.stream().filter(row->isFooter(row,date.format(BASIC))).count();byte[] bytes=body(date,params,rows,sourceVersion,Math.max(0,rows.size()-footers),footers,false,failure);
        if(bytes.length>MAX_EVIDENCE_BYTES)throw new IllegalStateException("D029 incomplete source evidence exceeds 32 MiB",failure);Files.createDirectories(evidenceRoot);persist(evidenceRoot.resolve("unverified-"+date.format(BASIC)+"-"+sha(bytes)+".json"),bytes);
    }
    private static void persist(Path path,byte[] bytes)throws Exception{try{FileEvidenceStore.writeNew(path,bytes);}catch(FileAlreadyExistsException exists){if(!Arrays.equals(FileEvidenceStore.readBounded(path, Math.max(1, bytes.length),
                    () -> new IllegalStateException("Conflicting immutable D029 source receipt", exists)),bytes))throw new IllegalStateException("Conflicting immutable D029 source receipt",exists);}}
    private static String sha(byte[] bytes)throws Exception{return FileEvidenceStore.sha256(bytes);}
}
