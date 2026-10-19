package com.zoutrankil.data.financial.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.financial.domain.FinancialContract;
import com.zoutrankil.data.financial.domain.FinancialContract.*;
import com.zoutrankil.data.repository.FileEvidenceStore;
import com.zoutrankil.data.service.*;
import java.nio.file.*;
import java.time.LocalDate;
import java.util.*;
import java.util.function.BooleanSupplier;

/** 每次只取一只证券的一个报告期；到达保守容量上限就停止，不能假定已取完。 */
public final class FinancialSource {
    private final TusharePageService pages;
    private final FinancialContract contract;
    private final Path evidence;
    public FinancialSource(TusharePageService pages,FinancialContract contract,Path evidence){this.pages=pages;this.contract=contract;this.evidence=evidence;}
    public SyncJobRunner.Page<Row> fetch(String code,LocalDate period,LocalDate logicalDate,BooleanSupplier cancelled)throws Exception {
        var fields=contract.fields().stream().filter(Field::collected).map(Field::source).toList();var parameters=contract.parameters(code,period);
        var policy=new PageContract(contract.dataset(),fields,List.of("ts_code","ann_date","end_date"),parameters.keySet(),
                PageContract.Paging.NONE,PageContract.Completion.SHORT_PAGE,null,null,100,100,1,100,
                "Tushare 单证券、单报告期；没有假定 offset 支持，满 100 行需拆分或人工核查");
        var raw=new ArrayList<Map<String,JsonNode>>();var paths=new ArrayList<String>();Files.createDirectories(evidence);
        var fetcher=pages.fetcher(policy,cancelled);
        var completion=new PageExecutor().execute(policy,parameters,request->{
            var response=fetcher.fetch(request);Path file=evidence.resolve("response-"+UUID.randomUUID()+".json");
            FileEvidenceStore.writeNewDurable(file,JobDefinitionJson.canonicalMapper().writeValueAsBytes(Map.of(
                    "endpoint",contract.dataset(),"parameters",parameters,"fields",fields,"rows",response.rows())));
            paths.add(file.toString());
            if(response.rows().size()>=100)throw new IllegalStateException("原始财务响应达到容量上限，不能因去重后较少就宣称完整");
            var selected=new com.zoutrankil.data.financial.mapper.FinancialMapper(contract).revisions(response.rows(),code,period,logicalDate);
            return new PageExecutor.Page(selected,response.nextCursor(),response.explicitEnd(),response.sourceVersion());
        },(page,receipt)->raw.addAll(page.rows()),row->new com.zoutrankil.data.financial.mapper.FinancialMapper(contract).normalize(row,code,period,logicalDate),cancelled);
        if(completion.pages()!=1||completion.rows()!=raw.size()||paths.size()!=1)throw new IllegalStateException("财务响应未完整冻结");
        var rows=raw.stream().map(row->new com.zoutrankil.data.financial.mapper.FinancialMapper(contract).normalize(row,code,period,logicalDate)).toList();
        byte[] bytes=JobDefinitionJson.canonicalMapper().writeValueAsBytes(Map.of("scope",parameters,"rows",rows,"rawEvidence",paths,"sourceComplete",true));
        Path file=evidence.resolve("source-"+UUID.randomUUID()+".json");FileEvidenceStore.writeNewDurable(file,bytes);
        byte[] identity=JobDefinitionJson.canonicalMapper().writeValueAsBytes(Map.of("scope",parameters,"rows",rows));
        return new SyncJobRunner.Page<>(rows,FileEvidenceStore.sha256(identity),file.toString(),null);
    }
}
