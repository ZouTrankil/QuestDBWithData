package com.zoutrankil.data.stock.application;

import com.zoutrankil.data.service.*;

import com.zoutrankil.data.repository.FileEvidenceStore;
import com.fasterxml.jackson.databind.*;
import com.zoutrankil.data.client.dto.TushareStockDetailDto;
import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.stock.mapper.StockDetailInfoMapper;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.function.BooleanSupplier;

/** Three finite status requests for one explicit stock identity; never fetches a market-wide snapshot. */
public final class StockDetailInfoSource {
    public static final List<String> FIELDS=List.of("ts_code","symbol","name","area","industry","fullname","enname",
            "cnspell","market","exchange","curr_type","list_status","list_date","delist_date","is_hs","act_name","act_ent_type");
    private final TusharePageService pages;
    private final Path evidence;
    public StockDetailInfoSource(TusharePageService pages,Path evidence) {
        this.pages=Objects.requireNonNull(pages);this.evidence=evidence.toAbsolutePath().normalize();
    }
    public SyncJobRunner.Page<StockDetailInfo> fetch(String code,Instant observedAt,BooleanSupplier cancelled) throws Exception {
        if(!StockDetailInfo.validCode(code)) throw new IllegalArgumentException("Explicit stock code required");
        Objects.requireNonNull(observedAt);
        if(observedAt.getNano()%1000!=0) throw new IllegalArgumentException("Microsecond observation required");
        var contract=new PageContract("stock_basic",FIELDS,List.of("ts_code"),Set.of("ts_code","list_status"),
                PageContract.Paging.NONE,PageContract.Completion.SHORT_PAGE,null,null,2,2,1,2,
                "One exact ts_code and listing status; require zero or one matching row; all L/D/P states queried");
        var mapper=new StockDetailInfoMapper();var typed=new ArrayList<StockDetailInfo>();
        var responses=new ArrayList<Map<String,Object>>();
        for(String status:List.of("L","D","P")) {
            var params=Map.<String,Object>of("ts_code",code,"list_status",status);
            var raw=new ArrayList<Map<String,JsonNode>>();
            var completed=pages.execute(contract,params,(page,receipt)->raw.addAll(page.rows()),row->{
                if(!code.equals(text(row,"ts_code")) || !status.equals(text(row,"list_status")))
                    throw new IllegalArgumentException("Source row outside requested identity/status");
            },cancelled);
            if(completed.pages()!=1 || completed.rows()!=raw.size() || raw.size()>1)
                throw new IllegalStateException("Stock identity response incomplete or ambiguous");
            responses.add(Map.of("parameters",params,"rows",List.copyOf(raw),"completion",completed));
            for(var r:raw) typed.add(mapper.fromSource(new TushareStockDetailDto(text(r,"ts_code"),text(r,"symbol"),
                    text(r,"name"),text(r,"area"),text(r,"industry"),text(r,"fullname"),text(r,"enname"),text(r,"cnspell"),
                    text(r,"market"),text(r,"exchange"),text(r,"curr_type"),text(r,"list_status"),text(r,"list_date"),
                    text(r,"delist_date"),text(r,"is_hs"),text(r,"act_name"),text(r,"act_ent_type")),observedAt));
        }
        if(typed.size()>1) throw new IllegalStateException("Stock appeared in multiple source listing states; retry from a fresh observation");
        byte[] bytes=new ObjectMapper().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).writeValueAsBytes(
                Map.of("endpoint","stock_basic","code",code,"responses",responses));
        Files.createDirectories(evidence);Path file=evidence.resolve("source-"+UUID.randomUUID()+".json");
        FileEvidenceStore.writeNew(file,bytes);
        return new SyncJobRunner.Page<>(typed,FileEvidenceStore.sha256(bytes),file.toString(),null);
    }
    private static String text(Map<String,JsonNode> row,String field) {
        var value=row.get(field);
        if(value==null || value.isNull()) return null;
        if(!value.isTextual()) throw new IllegalArgumentException("Text stock field required: "+field);
        return value.textValue();
    }
}
