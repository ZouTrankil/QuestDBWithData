package com.zoutrankil.data.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.zoutrankil.data.domain.JobDefinitionJson;
import com.zoutrankil.data.domain.MarginZrz;
import com.zoutrankil.data.domain.MarginZrzKey;
import com.zoutrankil.data.domain.PageContract;
import com.zoutrankil.data.mapper.MarginZrzMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.function.BooleanSupplier;

/** One bounded Python-compatible slb_len(start_date,end_date) request and immutable response receipt. */
public final class MarginZrzSource {
    public static final int API_ROW_CAP=5000,MAX_RANGE_DAYS=366,MAX_EVIDENCE_BYTES=4*1024*1024;
    public static final List<String> FIELDS=List.of("trade_date","ob","auc_amount","repo_amount","repay_amount","cb");
    public static final PageContract CONTRACT=new PageContract("slb_len",FIELDS,List.of("trade_date"),
            Set.of("start_date","end_date"),PageContract.Paging.NONE,PageContract.Completion.SHORT_PAGE,
            null,null,API_ROW_CAP,API_ROW_CAP,1,API_ROW_CAP,
            "Python calls pro.slb_len(start_date,end_date); endpoint reference supports range filters, maximum 5000 rows and no offset. One bounded <=366-day query; a cap hit is rejected. Exact source permission/current status remains unverified; Python marks this owner retired and disabled.");
    private static final DateTimeFormatter BASIC=DateTimeFormatter.BASIC_ISO_DATE;
    private final TusharePageService pages;private final Path evidenceRoot;private final MarginZrzMapper mapper=new MarginZrzMapper();
    public MarginZrzSource(TusharePageService pages,Path evidenceRoot){this.pages=Objects.requireNonNull(pages);this.evidenceRoot=Objects.requireNonNull(evidenceRoot).toAbsolutePath().normalize();}

    public SyncJobRunner.Page<MarginZrz> fetch(LocalDate from,LocalDate to,BooleanSupplier cancelled)throws Exception {
        requireWindow(from,to);Objects.requireNonNull(cancelled);String first=from.format(BASIC),last=to.format(BASIC);
        Map<String,Object> params=Map.of("start_date",first,"end_date",last);var raw=new ArrayList<Map<String,JsonNode>>();PageExecutor.Completed completed;
        try {
            var fetcher=pages.fetcher(CONTRACT,cancelled);
            completed=new PageExecutor().execute(CONTRACT,params,request->{PageExecutor.Page response=fetcher.fetch(request);raw.addAll(response.rows());return response;},
                    (page,receipt)->{},row->validateRow(row,from,to),cancelled);
        } catch(Exception failure) { persistIncomplete(from,to,params,raw,null,failure);throw failure; }
        raw.sort(Comparator.comparing(row->value(row,"trade_date")));
        if(completed.pages()!=1||completed.rows()!=raw.size()||raw.size()>=API_ROW_CAP){
            var failure=new IllegalStateException("D031 slb_len response is incomplete or reaches the 5000-row cap");persistIncomplete(from,to,params,raw,completed.sourceVersion(),failure);throw failure;}
        var typed=new ArrayList<MarginZrz>(raw.size());var keys=new HashSet<MarginZrzKey>();
        try {
            String prior=null;
            for(var item:raw){validateExactFields(item);String date=value(item,"trade_date");if(prior!=null&&prior.compareTo(date)>=0)throw new IllegalArgumentException("D031 duplicate or noncanonical trade_date");prior=date;
                var row=mapper.fromSource(mapper.dto(item));if(row.tradeDate().isBefore(from)||row.tradeDate().isAfter(to)||!keys.add(row.key()))throw new IllegalArgumentException("D031 duplicate/out-of-window natural key");typed.add(row);}
            byte[] bytes=body(from,to,params,raw,completed.sourceVersion(),true,null);if(bytes.length>MAX_EVIDENCE_BYTES)throw new IllegalStateException("D031 source receipt exceeds 4 MiB");
            String fingerprint=sha(bytes);Files.createDirectories(evidenceRoot);Path receipt=evidenceRoot.resolve("margin-zrz-"+first+"-"+last+"-"+fingerprint+".json");persist(receipt,bytes);
            return new SyncJobRunner.Page<>(List.copyOf(typed),fingerprint,receipt.toString(),first+".."+last);
        } catch(Exception failure){persistIncomplete(from,to,params,raw,completed.sourceVersion(),failure);throw failure;}
    }

    public static SyncJobRunner.Page<MarginZrz> reopen(Path receipt,String fingerprint,LocalDate expectedFrom,LocalDate expectedTo)throws Exception {
        requireWindow(expectedFrom,expectedTo);Path file=receipt.toAbsolutePath().normalize();
        if(fingerprint==null||!fingerprint.matches("[0-9a-f]{64}")||!Files.isRegularFile(file)||Files.isSymbolicLink(file)||Files.size(file)<1||Files.size(file)>MAX_EVIDENCE_BYTES)
            throw new IllegalArgumentException("D031 bounded raw receipt and SHA-256 required");
        byte[] bytes=Files.readAllBytes(file);if(!sha(bytes).equals(fingerprint))throw new IllegalStateException("D031 receipt SHA-256 mismatch");
        var json=JobDefinitionJson.mapper();JsonNode proof=json.readTree(bytes);String first=expectedFrom.format(BASIC),last=expectedTo.format(BASIC);
        if(!"tushare".equals(proof.path("sourceKind").asText())||!"slb_len".equals(proof.path("endpoint").asText())
                ||!proof.path("sourceComplete").asBoolean(false)||!expectedFrom.toString().equals(proof.path("fromInclusive").asText())
                ||!expectedTo.toString().equals(proof.path("toInclusive").asText())||proof.path("apiMaximumRows").asInt(-1)!=API_ROW_CAP
                ||!json.valueToTree(FIELDS).equals(proof.path("fields"))||!first.equals(proof.path("parameters").path("start_date").asText())
                ||!last.equals(proof.path("parameters").path("end_date").asText())||!proof.path("rawRows").isArray()
                ||proof.path("returnedRows").asInt(-1)!=proof.path("rawRows").size()||proof.path("rawRows").size()>=API_ROW_CAP)
            throw new IllegalStateException("D031 receipt differs from frozen slb_len range/field/cap contract");
        List<Map<String,JsonNode>> raw=json.convertValue(proof.path("rawRows"),new TypeReference<>(){});
        var rows=new ArrayList<MarginZrz>();var keys=new HashSet<MarginZrzKey>();String prior=null;
        for(var item:raw){validateRow(item,expectedFrom,expectedTo);String date=value(item,"trade_date");if(prior!=null&&prior.compareTo(date)>=0)throw new IllegalStateException("D031 receipt rows are not strictly date ordered");prior=date;
            var row=new MarginZrzMapper().fromSource(new MarginZrzMapper().dto(item));if(!keys.add(row.key()))throw new IllegalStateException("D031 duplicate natural date in receipt");rows.add(row);}
        return new SyncJobRunner.Page<>(List.copyOf(rows),fingerprint,file.toString(),first+".."+last);
    }
    private static void validateRow(Map<String,JsonNode> row,LocalDate from,LocalDate to){
        validateExactFields(row);var typed=new MarginZrzMapper().fromSource(new MarginZrzMapper().dto(row));
        if(typed.tradeDate().isBefore(from)||typed.tradeDate().isAfter(to))throw new IllegalArgumentException("D031 provider date outside frozen range");}
    private static void validateExactFields(Map<String,JsonNode> row){if(row==null||!row.keySet().equals(new HashSet<>(FIELDS)))throw new IllegalArgumentException("D031 provider columns differ from six-field slb_len contract");}
    private static byte[] body(LocalDate from,LocalDate to,Map<String,Object> params,List<Map<String,JsonNode>> rows,String version,boolean complete,Exception failure)throws Exception{
        var json=JobDefinitionJson.mapper().configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS,true);var body=new LinkedHashMap<String,Object>();
        body.put("sourceKind","tushare");body.put("endpoint","slb_len");body.put("sourceContractVersion",1);body.put("parameters",params);body.put("fields",FIELDS);
        body.put("fromInclusive",from);body.put("toInclusive",to);body.put("apiMaximumRows",API_ROW_CAP);body.put("returnedRows",rows.size());body.put("rawRows",rows);body.put("sourceComplete",complete);
        if(version!=null)body.put("sourceVersion",version);if(failure!=null)body.put("failureType",failure.getClass().getSimpleName());return json.writeValueAsBytes(body);}
    private void persistIncomplete(LocalDate from,LocalDate to,Map<String,Object> params,List<Map<String,JsonNode>> rows,String version,Exception failure)throws Exception{
        byte[] bytes=body(from,to,params,rows,version,false,failure);if(bytes.length>MAX_EVIDENCE_BYTES)throw new IllegalStateException("D031 incomplete source receipt exceeds 4 MiB",failure);
        Files.createDirectories(evidenceRoot);persist(evidenceRoot.resolve("incomplete-"+from.format(BASIC)+"-"+to.format(BASIC)+"-"+sha(bytes)+".json"),bytes);}
    private static void persist(Path path,byte[] bytes)throws Exception{try{Files.write(path,bytes,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE);}
        catch(java.nio.file.FileAlreadyExistsException exists){if(!Arrays.equals(Files.readAllBytes(path),bytes))throw new IllegalStateException("Conflicting immutable D031 receipt",exists);}}
    private static String value(Map<String,JsonNode> row,String field){JsonNode node=row.get(field);return node==null||node.isNull()?"":node.asText();}
    private static String sha(byte[] bytes)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
    private static void requireWindow(LocalDate from,LocalDate to){if(from==null||to==null||from.isAfter(to)||ChronoUnit.DAYS.between(from,to)+1>MAX_RANGE_DAYS)throw new IllegalArgumentException("D031 inclusive source range must be ordered and <=366 days");}
}
