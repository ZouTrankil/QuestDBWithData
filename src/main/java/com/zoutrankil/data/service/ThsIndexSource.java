package com.zoutrankil.data.service;

import com.zoutrankil.data.repository.FileEvidenceStore;
import com.fasterxml.jackson.databind.*;
import com.zoutrankil.data.client.dto.TushareThsIndexDto;
import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.mapper.ThsIndexMapper;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.function.BooleanSupplier;

/** One explicit bounded provider scope per observation; this endpoint has no documented paging. */
public final class ThsIndexSource {
    public record Scope(String code,String exchange,String type) {
        public Scope {
            if(code!=null) {
                if(!ThsIndex.validCode(code) || exchange!=null || type!=null)
                    throw new IllegalArgumentException("Choose one exact THS code or one market/type scope");
            } else if((exchange!=null || type!=null)
                    && (exchange==null || type==null || !ThsIndex.EXCHANGES.contains(exchange) || !ThsIndex.TYPES.contains(type)))
                throw new IllegalArgumentException("Explicit THS market and index type required");
        }
        public static Scope all() { return new Scope(null,null,null); }
        public Map<String,Object> parameters() {
            return code!=null?Map.of("ts_code",code):exchange!=null?Map.of("exchange",exchange,"type",type):Map.of();
        }
        public void requireContains(ThsIndex row) {
            if(code!=null?!code.equals(row.tsCode()):exchange!=null
                    && (!exchange.equals(row.exchange()) || !type.equals(row.indexType())))
                throw new IllegalArgumentException("THS response outside frozen source scope");
        }
    }
    public static final List<String> FIELDS=List.of("ts_code","name","count","exchange","list_date","type");
    public static final PageContract CONTRACT=new PageContract("ths_index",FIELDS,List.of("ts_code"),Set.of("ts_code","exchange","type"),
            PageContract.Paging.NONE,PageContract.Completion.SHORT_PAGE,null,null,5000,5000,1,5000,
            "https://tushare.pro/document/2?doc_id=259: one explicit scope, no documented offset/limit; cap hit is incomplete");
    private final TusharePageService pages;private final Path evidence;
    public ThsIndexSource(TusharePageService pages,Path evidence) { this.pages=Objects.requireNonNull(pages);this.evidence=evidence; }
    public SyncJobRunner.Page<ThsIndex> fetch(Scope scope,Instant observedAt,BooleanSupplier cancelled) throws Exception {
        Objects.requireNonNull(scope);Objects.requireNonNull(observedAt);
        if(observedAt.getNano()%1000!=0) throw new IllegalArgumentException("Microsecond THS observation required");
        var mapper=new ThsIndexMapper();var raw=new ArrayList<Map<String,JsonNode>>();
        var completion=pages.execute(CONTRACT,scope.parameters(),(page,receipt)->raw.addAll(page.rows()),
                row->scope.requireContains(mapper.fromSource(decode(row),observedAt)),cancelled);
        if(completion.pages()!=1 || completion.rows()!=raw.size() || raw.size()>=5000 || scope.code()!=null && raw.size()>1)
            throw new IllegalStateException("THS response may be truncated or identity is ambiguous");
        var typed=raw.stream().map(row->mapper.fromSource(decode(row),observedAt)).toList();
        byte[] bytes=JobDefinitionJson.mapper().writeValueAsBytes(Map.of("endpoint","ths_index","parameters",scope.parameters(),
                "observedAt",observedAt,"rows",raw,"completion",completion));
        if(bytes.length>8*1024*1024) throw new IllegalArgumentException("THS source evidence exceeds byte bound");
        Files.createDirectories(evidence);Path receipt=evidence.resolve("source-"+UUID.randomUUID()+".json");
        FileEvidenceStore.writeNew(receipt,bytes);
        return new SyncJobRunner.Page<>(typed,FileEvidenceStore.sha256(bytes),receipt.toString(),null);
    }
    private static TushareThsIndexDto decode(Map<String,JsonNode> row) {
        var count=row.get("count");Integer number=null;
        if(count!=null && !count.isNull()) {
            if(!count.isIntegralNumber() || !count.canConvertToInt()) throw new IllegalArgumentException("Integral THS member count required");
            number=count.intValue();
        }
        return new TushareThsIndexDto(text(row,"ts_code"),text(row,"name"),number,text(row,"exchange"),text(row,"list_date"),text(row,"type"));
    }
    private static String text(Map<String,JsonNode> row,String field) {
        var value=row.get(field);if(value==null || value.isNull()) return null;
        if(!value.isTextual()) throw new IllegalArgumentException("Text THS field required: "+field);return value.textValue();
    }
}
