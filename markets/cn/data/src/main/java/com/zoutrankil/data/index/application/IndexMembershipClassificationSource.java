package com.zoutrankil.data.index.application;



import com.zoutrankil.data.service.*;

import com.zoutrankil.data.repository.FileEvidenceStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.zoutrankil.data.domain.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.function.BooleanSupplier;

/** Bounded SW2021 L2 discovery; publication eligibility is not membership eligibility. */
public final class IndexMembershipClassificationSource {
    /** Official SW2021 taxonomy declares 134 L2 industries; a smaller unpaged result is incomplete. */
    public static final int SW2021_L2_COUNT=134;
    public record Industry(String indexCode,String industryName,String industryCode,String parentCode,String level,String source,String published) {
        public Industry {
            if(indexCode==null || !indexCode.matches("[0-9]{6}\\.SI") || industryName==null || industryName.isBlank()
                    || industryName.length()>256 || industryCode==null || !industryCode.matches("[0-9]{6}")
                    || parentCode==null || !parentCode.matches("[0-9]{6}") || !"L2".equals(level) || !"SW2021".equals(source)
                    || !("0".equals(published) || "1".equals(published)))
                throw new IllegalArgumentException("Complete SW2021 L2 classification required");
        }
    }
    public record Catalog(List<Industry> industries,String fingerprint,String receipt) {
        public Catalog { industries=List.copyOf(industries); }
        public List<IndexMembershipSource.Scope> select(List<String> codes,IndexMembershipSource.Selection selection) {
            if(codes==null || codes.isEmpty() || codes.size()>32 || new HashSet<>(codes).size()!=codes.size())
                throw new IllegalArgumentException("Explicit unique bounded industry list required (1..32)");
            var byCode=new HashMap<String,Industry>();
            for(var industry:industries) if(byCode.putIfAbsent(industry.indexCode(),industry)!=null)
                throw new IllegalStateException("Duplicate classification identity");
            var result=new ArrayList<IndexMembershipSource.Scope>();
            for(String code:codes) {
                var industry=byCode.get(code);
                if(industry==null) throw new IllegalArgumentException("Requested industry absent from frozen SW2021 L2 catalog");
                result.add(new IndexMembershipSource.Scope(code,industry.industryName(),selection));
            }
            return List.copyOf(result);
        }
    }
    public static final List<String> FIELDS=List.of("index_code","industry_name","level","industry_code","is_pub","parent_code","src");
    public static final PageContract CONTRACT=new PageContract("index_classify",FIELDS,List.of("index_code"),Set.of("level","src"),
            PageContract.Paging.NONE,PageContract.Completion.SHORT_PAGE,null,null,2000,2000,1,2000,
            "Explicit SW2021 L2 catalog; local 2000-row ceiling, no inferred offset/date support");
    private final TusharePageService pages;private final Path evidence;
    public IndexMembershipClassificationSource(TusharePageService pages,Path evidence) { this.pages=Objects.requireNonNull(pages);this.evidence=Objects.requireNonNull(evidence); }
    public Catalog fetch(BooleanSupplier cancelled) throws Exception {
        var params=Map.<String,Object>of("level","L2","src","SW2021");var raw=new ArrayList<Map<String,JsonNode>>();
        var completed=pages.execute(CONTRACT,params,(page,receipt)->raw.addAll(page.rows()),IndexMembershipClassificationSource::decode,cancelled);
        if(cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException();
        if(completed.pages()!=1 || raw.size()!=SW2021_L2_COUNT || completed.rows()!=raw.size())
            throw new IllegalStateException("SW2021 L2 classification differs from the declared 134-code taxonomy; rebaseline required");
        var typed=raw.stream().map(IndexMembershipClassificationSource::decode).sorted(Comparator.comparing(Industry::indexCode)).toList();
        byte[] bytes=JobDefinitionJson.mapper().writeValueAsBytes(Map.of("endpoint","index_classify","parameters",params,
                "observedAt",Instant.now(),"completion",completed,"rows",raw));
        if(bytes.length>1024*1024) throw new IllegalArgumentException("Classification evidence exceeds 1 MiB");
        Files.createDirectories(evidence);Path receipt=evidence.resolve("classification-"+UUID.randomUUID()+".json");
        FileEvidenceStore.writeNew(receipt,bytes);
        return new Catalog(typed,FileEvidenceStore.sha256(bytes),receipt.toString());
    }
    private static Industry decode(Map<String,JsonNode> row) {
        return new Industry(text(row,"index_code"),text(row,"industry_name"),text(row,"industry_code"),text(row,"parent_code"),
                text(row,"level"),text(row,"src"),text(row,"is_pub"));
    }
    /** Rebuild scope identities from the frozen bytes, never from caller-supplied typed rows. */
    public static Catalog reopen(Path receipt,String fingerprint) throws Exception {
        if(fingerprint==null || !fingerprint.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("Classification SHA-256 required");
        byte[] bytes = FileEvidenceStore.readBounded(receipt, 1024*1024,
                () -> new IllegalArgumentException("Frozen classification receipt changed or exceeds bound"));
        if(!FileEvidenceStore.sha256(bytes).equals(fingerprint))
            throw new IllegalArgumentException("Frozen classification receipt changed or exceeds bound");
        var json=JobDefinitionJson.mapper();var proof=json.readTree(bytes);var rows=proof.path("rows");
        if(!proof.path("endpoint").asText().equals("index_classify")
                || !proof.path("parameters").equals(json.valueToTree(Map.of("level","L2","src","SW2021")))
                || !rows.isArray() || rows.size()!=SW2021_L2_COUNT
                || proof.path("completion").path("pages").asInt()!=1
                || proof.path("completion").path("rows").asInt()!=rows.size())
            throw new IllegalArgumentException("Complete SW2021 L2 classification receipt required");
        Instant.parse(proof.path("observedAt").asText());
        var industries=new ArrayList<Industry>();var keys=new HashSet<String>();
        for(var raw:rows) {
            Map<String,JsonNode> fields=json.convertValue(raw,new com.fasterxml.jackson.core.type.TypeReference<>() {});
            var industry=decode(fields);
            if(!keys.add(industry.indexCode())) throw new IllegalArgumentException("Duplicate frozen industry code");
            industries.add(industry);
        }
        industries.sort(Comparator.comparing(Industry::indexCode));
        return new Catalog(industries,fingerprint,receipt.toAbsolutePath().normalize().toString());
    }
    private static String text(Map<String,JsonNode> row,String field) {
        var node=row.get(field);if(node==null || !node.isTextual()) throw new IllegalArgumentException("Classification text required: "+field);
        return node.textValue();
    }
}
