package com.zoutrankil.questdbwithdata.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.zoutrankil.questdbwithdata.domain.*;
import com.zoutrankil.questdbwithdata.domain.temporal.TemporalValues;
import com.zoutrankil.questdbwithdata.mapper.DcIndexMapper;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.function.BooleanSupplier;

/** One bounded Python-compatible `dc_index(trade_date=YYYYMMDD)` source request per date. */
public final class DcIndexSource {
    public static final int SOURCE_ROW_CAP = 5_000; // Python model/source contract; official current doc drift is documented.
    public static final int MAX_EVIDENCE_BYTES = 16 * 1024 * 1024;
    public static final List<String> FIELDS = List.of("ts_code","trade_date","name","leading","leading_code",
            "pct_change","leading_pct","total_mv","turnover_rate","up_num","down_num");
    public static final PageContract CONTRACT = new PageContract("dc_index", FIELDS, List.of("ts_code","trade_date"),
            Set.of("trade_date"), PageContract.Paging.NONE, PageContract.Completion.SHORT_PAGE,
            null, null, SOURCE_ROW_CAP, SOURCE_ROW_CAP, 1, SOURCE_ROW_CAP,
            "Python connector calls dc_index(trade_date=YYYYMMDD), unpaged, one request per trading date; Python model documents 5000 rows. Current official DC行情 page documents dc_daily/2000/OHLC schema instead; endpoint/field/cap require live source confirmation before acceptance. Runtime uses shared rate budget and does not raise it to Python's 200/min decoration.");
    private static final DateTimeFormatter BASIC = DateTimeFormatter.BASIC_ISO_DATE;
    private final TusharePageService pages;
    private final DcIndexMapper mapper = new DcIndexMapper();
    private final Path evidenceRoot;
    public DcIndexSource(TusharePageService pages, Path evidenceRoot) {
        this.pages=Objects.requireNonNull(pages);this.evidenceRoot=Objects.requireNonNull(evidenceRoot).toAbsolutePath().normalize();
    }

    public SyncJobRunner.Page<DcIndex> fetch(LocalDate date, BooleanSupplier cancelled) throws Exception {
        Objects.requireNonNull(date);Objects.requireNonNull(cancelled);
        String basic=date.format(BASIC);var params=Map.<String,Object>of("trade_date",basic);
        var raw=new ArrayList<Map<String,JsonNode>>();PageExecutor.Completed completed;
        try {
            completed=pages.execute(CONTRACT,params,(page,receipt)->raw.addAll(page.rows()),
                    row->validateScope(row,basic),cancelled);
        } catch(Exception failure) {
            persistUnverified(date,params,raw,failure);throw failure;
        }
        raw.sort(Comparator.comparing((Map<String,JsonNode> r)->value(r,"ts_code"))
                .thenComparing(r->value(r,"trade_date")));
        if(completed.pages()!=1||completed.rows()!=raw.size()||raw.size()>=SOURCE_ROW_CAP) {
            var failure=new IllegalStateException("dc_index response is incomplete or reaches the declared per-date cap");
            persistUnverified(date,params,raw,failure);throw failure;
        }
        var rows=new ArrayList<DcIndex>(raw.size());var keys=new HashSet<DcIndexKey>();
        for(var item:raw) {
            var row=mapper.fromSource(mapper.dto(item));
            if(!row.tradeDate().equals(date)||!keys.add(row.key())) {
                var failure=new IllegalArgumentException("dc_index returned a duplicate business key or row outside requested date");
                persistUnverified(date,params,raw,failure);throw failure;
            }
            rows.add(row);
        }
        byte[] body=canonical(date,params,raw,true,null);
        if(body.length>MAX_EVIDENCE_BYTES)throw new IllegalArgumentException("dc_index receipt exceeds 16 MiB evidence limit");
        String fingerprint=sha(body);Files.createDirectories(evidenceRoot);
        Path receipt=evidenceRoot.resolve("dc-index-"+basic+"-"+fingerprint+".json");persist(receipt,body);
        return new SyncJobRunner.Page<>(rows,fingerprint,receipt.toString(),basic);
    }

    public static SyncJobRunner.Page<DcIndex> reopen(Path receipt,String expectedFingerprint,LocalDate expectedDate)throws Exception {
        var path=receipt.toAbsolutePath().normalize();
        if(!expectedFingerprint.matches("[0-9a-f]{64}")||!Files.isRegularFile(path)||Files.size(path)>MAX_EVIDENCE_BYTES)
            throw new IllegalArgumentException("Bounded dc_index receipt and SHA-256 required");
        byte[] bytes=Files.readAllBytes(path);if(!sha(bytes).equals(expectedFingerprint))throw new IllegalStateException("dc_index receipt SHA mismatch");
        var json=JobDefinitionJson.mapper();JsonNode proof=json.readTree(bytes);
        if(!"dc_index".equals(proof.path("endpoint").asText())||!proof.path("sourceComplete").asBoolean(false)
                ||!proof.path("tradeDate").asText().equals(expectedDate.toString())
                ||proof.path("sourceRowCap").asInt(-1)!=SOURCE_ROW_CAP||!proof.path("rawRows").isArray()
                ||proof.path("returnedRows").asInt(-1)!=proof.path("rawRows").size()
                ||proof.path("rawRows").size()>=SOURCE_ROW_CAP
                ||!json.valueToTree(FIELDS).equals(proof.path("fields"))
                ||!expectedDate.format(BASIC).equals(proof.path("parameters").path("trade_date").asText()))
            throw new IllegalStateException("dc_index receipt differs from frozen endpoint/date/field contract");
        List<Map<String,JsonNode>> raw=json.convertValue(proof.path("rawRows"),new TypeReference<>(){});
        var rows=new ArrayList<DcIndex>();var keys=new HashSet<DcIndexKey>();
        for(var item:raw){validateScope(item,expectedDate.format(BASIC));var typed=new DcIndexMapper().fromSource(new DcIndexMapper().dto(item));
            if(!keys.add(typed.key()))throw new IllegalStateException("dc_index receipt contains duplicate full key");rows.add(typed);}
        var sorted=new ArrayList<>(raw);sorted.sort(Comparator.comparing((Map<String,JsonNode> r)->value(r,"ts_code")).thenComparing(r->value(r,"trade_date")));
        if(!json.valueToTree(raw).equals(json.valueToTree(sorted)))throw new IllegalStateException("dc_index raw receipt row order is not canonical");
        return new SyncJobRunner.Page<>(rows,expectedFingerprint,path.toString(),expectedDate.format(BASIC));
    }
    private static void validateScope(Map<String,JsonNode> row,String date) {
        if(!FIELDS.stream().allMatch(row::containsKey))throw new IllegalArgumentException("dc_index row lacks required contract field");
        String raw=value(row,"trade_date");LocalDate parsed=TemporalValues.businessDate(raw,TemporalValues.DateFormat.BASIC);
        if(!raw.equals(date)||!parsed.format(BASIC).equals(date))throw new IllegalArgumentException("dc_index row outside frozen daily date");
    }
    private static byte[] canonical(LocalDate date,Map<String,Object> parameters,List<Map<String,JsonNode>> rows,
                                    boolean complete,Exception failure)throws Exception {
        var json=JobDefinitionJson.mapper().configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS,true);
        var body=new LinkedHashMap<String,Object>();body.put("sourceKind","tushare");body.put("endpoint","dc_index");
        body.put("tradeDate",date.toString());body.put("parameters",parameters);body.put("fields",FIELDS);
        body.put("sourceRowCap",SOURCE_ROW_CAP);body.put("returnedRows",rows.size());body.put("rawRows",rows);
        body.put("sourceComplete",complete);if(failure!=null)body.put("failureType",failure.getClass().getSimpleName());
        return json.writeValueAsBytes(body);
    }
    private void persistUnverified(LocalDate date,Map<String,Object> params,List<Map<String,JsonNode>> rows,Exception failure)throws Exception {
        byte[] bytes=canonical(date,params,rows,false,failure);if(bytes.length>MAX_EVIDENCE_BYTES)throw new IllegalStateException("Unverified dc_index evidence exceeds bound",failure);
        Files.createDirectories(evidenceRoot);Path target=evidenceRoot.resolve("unverified-"+date.format(BASIC)+"-"+sha(bytes)+".json");persist(target,bytes);
    }
    private static void persist(Path target,byte[] body)throws Exception {
        try{Files.write(target,body,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE);}
        catch(FileAlreadyExistsException exists){if(!Arrays.equals(Files.readAllBytes(target),body))throw new IllegalStateException("Conflicting deterministic dc_index evidence receipt",exists);}
    }
    public static String sha(byte[] bytes)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
    private static String value(Map<String,JsonNode> row,String field){JsonNode v=row.get(field);return v==null||v.isNull()?"":v.asText();}
}
