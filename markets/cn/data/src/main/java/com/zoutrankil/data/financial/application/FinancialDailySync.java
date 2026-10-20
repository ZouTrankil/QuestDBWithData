package com.zoutrankil.data.financial.application;

import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.repository.FileEvidenceStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.*;
import org.springframework.context.annotation.Configuration;
import java.nio.file.Path;
import java.time.*;
import java.util.*;

/** 单独启用才调度；证券范围由摘要固定的清单提供，不从当前目标行数猜全市场。 */
@Configuration @EnableScheduling
@org.springframework.context.annotation.Profile("!main-strategy")
@org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication(type=org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication.Type.REACTIVE)
@ConditionalOnProperty(name="app.sync.financial.enabled",havingValue="true")
public class FinancialDailySync {
    private final List<FinancialSyncOwner> owners;private final Path scope;private final String sha;private final LocalDate earliest;
    private final com.zoutrankil.data.config.TushareProperties quota;private final long maxAttempts;private final Duration window;
    public FinancialDailySync(List<FinancialSyncOwner> owners,@Value("${app.sync.financial.scope-file}")String scope,
            @Value("${app.sync.financial.scope-sha256}")String sha,@Value("${app.sync.financial.earliest-period:2018-03-31}")String earliest,
            com.zoutrankil.data.config.TushareProperties quota,
            @Value("${app.sync.financial.max-request-attempts:1000}")long maxAttempts,
            @Value("${app.sync.financial.execution-window:PT4H}")Duration window) {
        if(scope.isBlank()||!sha.matches("[a-f0-9]{64}")||owners.size()!=5||!owners.stream().map(FinancialSyncOwner::datasetId).collect(java.util.stream.Collectors.toSet()).equals(com.zoutrankil.data.financial.domain.FinancialContract.DATASETS))throw new IllegalArgumentException("启用财务日批需五条真实执行器、范围文件及批准 SHA-256");
        this.owners=List.copyOf(owners);this.scope=Path.of(scope);this.sha=sha;this.earliest=LocalDate.parse(earliest);
        this.quota=quota;this.maxAttempts=maxAttempts;this.window=window;
    }
    @Scheduled(cron="${app.sync.financial.cron:0 30 19 * * *}",zone="Asia/Shanghai")
    public void scheduled()throws Exception { runAt(com.zoutrankil.market.cn.time.CnMarketTime.system().today()); }
    public void runAt(LocalDate logicalDate)throws Exception {
        var bytes=FileEvidenceStore.readBounded(scope,1024*1024,()->new IllegalStateException("财务范围清单过大"));
        if(!sha.matches("[a-f0-9]{64}")||!FileEvidenceStore.sha256(bytes).equals(sha))throw new IllegalStateException("财务范围清单摘要不符");
        var doc=JobDefinitionJson.mapper().readTree(bytes);
        if(!"java.financial.scope.v1".equals(doc.path("schema").asText())||!doc.path("codes").isArray()
                ||logicalDate.isBefore(LocalDate.parse(doc.required("validFrom").asText()))||logicalDate.isAfter(LocalDate.parse(doc.required("validThrough").asText())))throw new IllegalStateException("财务证券范围未批准或已过期");
        var codes=new ArrayList<String>();for(var item:doc.path("codes")){if(!item.isTextual())throw new IllegalArgumentException("财务证券代码必须为文本");codes.add(item.textValue());}
        var periods=FinancialSyncOwner.dailyPeriods(logicalDate,earliest);
        var ordered=owners.stream().sorted(Comparator.comparing(FinancialSyncOwner::datasetId)).toList();
        // 先验证所有请求和总预算，再运行任何来源，防止第五条请求非法时前四条已经写入。
        var plans=ordered.stream().map(owner->owner.plan(codes,periods,logicalDate,SyncJobDefinition.Mode.INCREMENTAL)).toList();
        FinancialDailyBudget.requireFits(codes.size(),periods.size(),ordered.stream().map(FinancialSyncOwner::datasetId).toList(),quota,maxAttempts,window);
        long deadline=System.nanoTime()+window.toNanos();
        for(int i=0;i<ordered.size();i++) {
            if(System.nanoTime()-deadline>=0)throw new IllegalStateException("财务日批执行窗口已用完，剩余来源未执行，不标记整轮完成");
            var result=ordered.get(i).runWithin(plans.get(i),Duration.ofNanos(deadline-System.nanoTime()));
            if(!Set.of(SyncRunState.VERIFIED,SyncRunState.VERIFIED_EMPTY).contains(result.state()))throw new IllegalStateException("财务日批未完成，保留原运行："+result.runId());
        }
    }
}
