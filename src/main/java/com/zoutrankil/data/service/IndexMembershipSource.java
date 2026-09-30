package com.zoutrankil.data.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.zoutrankil.data.client.dto.TushareIndexMembershipDto;
import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.mapper.IndexMembershipMapper;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.function.BooleanSupplier;

/** One frozen L2 slice, never an unbounded all-industry in-memory download. */
public final class IndexMembershipSource {
    public enum Selection { CURRENT, HISTORICAL, BOTH }
    public record Scope(String l2Code,String industryName,Selection selection) {
        public Scope {
            if(l2Code==null || !l2Code.matches("[0-9]{6}\\.SI") || industryName==null || industryName.isBlank()
                    || industryName.length()>256 || selection==null) throw new IllegalArgumentException("Frozen L2 identity/name and explicit member selection required");
        }
    }
    public static final List<String> FIELDS=List.of("l1_code","l1_name","l2_code","l2_name","l3_code","l3_name","ts_code","name","in_date","out_date","is_new");
    public static final PageContract CONTRACT=new PageContract("index_member_all",FIELDS,List.of("l2_code","ts_code","in_date"),
            Set.of("l2_code","is_new"),PageContract.Paging.NONE,PageContract.Completion.SHORT_PAGE,null,null,
            2000,2000,1,2000,"https://tushare.pro/document/2?doc_id=335; explicit Y/N; cap hit is incomplete; no documented paging");
    private final TusharePageService pages;private final Path evidence;
    public IndexMembershipSource(TusharePageService pages,Path evidence) { this.pages=Objects.requireNonNull(pages);this.evidence=Objects.requireNonNull(evidence); }
    public SyncJobRunner.Page<IndexMembership> fetch(Scope scope,Instant observedAt,BooleanSupplier cancelled) throws Exception {
        Objects.requireNonNull(scope);Objects.requireNonNull(observedAt);
        if(observedAt.getNano()%1000!=0) throw new IllegalArgumentException("Microsecond observation required");
        check(cancelled);var mapper=new IndexMembershipMapper();var rows=new ArrayList<IndexMembership>();
        var keys=new HashSet<IndexMembership.Key>();var requests=new ArrayList<Map<String,Object>>();
        var modes=switch(scope.selection()) { case CURRENT->List.of("Y");case HISTORICAL->List.of("N");case BOTH->List.of("Y","N"); };
        for(String mode:modes) {
            check(cancelled);var params=Map.<String,Object>of("l2_code",scope.l2Code(),"is_new",mode);
            var raw=new ArrayList<Map<String,JsonNode>>();
            var completed=pages.execute(CONTRACT,params,(page,receipt)->raw.addAll(page.rows()),r->{
                var dto=decode(r);mapper.fromSource(dto,scope.l2Code(),scope.industryName(),observedAt);
                if(!mode.equals(dto.isNew())) throw new IllegalArgumentException("Membership response outside explicit Y/N selection");
            },cancelled);
            check(cancelled);
            if(completed.pages()!=1 || completed.rows()!=raw.size() || raw.size()>=2000)
                throw new IllegalStateException("Incomplete membership response");
            for(var r:raw) {
                var row=mapper.fromSource(decode(r),scope.l2Code(),scope.industryName(),observedAt);
                if(!keys.add(row.key())) throw new IllegalStateException("Conflicting member period across Y/N observations");
                rows.add(row);
            }
            var proof=Map.<String,Object>of("endpoint","index_member_all","parameters",params,
                    "capturedAt",Instant.now(),"completion",completed,"rows",raw);
            byte[] bytes=JobDefinitionJson.mapper().writeValueAsBytes(proof);
            if(bytes.length>4*1024*1024) throw new IllegalArgumentException("Membership response exceeds evidence budget");
            Files.createDirectories(evidence);Path part=evidence.resolve("membership-"+UUID.randomUUID()+"-"+mode+".json");
            Files.write(part,bytes,StandardOpenOption.CREATE_NEW);
            requests.add(Map.of("receipt",part.toString(),"sha256",hash(bytes),"response",proof));
        }
        check(cancelled);byte[] bytes=JobDefinitionJson.mapper().writeValueAsBytes(Map.of(
                "scope",scope,"observedAt",observedAt,"requests",requests,"rows",rows,"complete",true));
        if(bytes.length>8*1024*1024) throw new IllegalArgumentException("Membership slice exceeds evidence budget");
        Path receipt=evidence.resolve("membership-complete-"+UUID.randomUUID()+".json");
        Files.write(receipt,bytes,StandardOpenOption.CREATE_NEW);
        return new SyncJobRunner.Page<>(rows,hash(bytes),receipt.toString(),null);
    }
    private static String hash(byte[] bytes) throws Exception { return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes)); }
    private static void check(BooleanSupplier cancelled) {
        if(cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException("Membership source cancelled");
    }
    static TushareIndexMembershipDto decode(Map<String,JsonNode> r) {
        return new TushareIndexMembershipDto(text(r,"l1_code"),text(r,"l1_name"),text(r,"l2_code"),text(r,"l2_name"),
                text(r,"l3_code"),text(r,"l3_name"),text(r,"ts_code"),text(r,"name"),text(r,"in_date"),text(r,"out_date"),text(r,"is_new"));
    }
    private static String text(Map<String,JsonNode> row,String field) {
        var n=row.get(field);if(n==null || n.isNull()) return null;
        if(!n.isTextual()) throw new IllegalArgumentException("Membership field must be text: "+field);return n.textValue();
    }
}
