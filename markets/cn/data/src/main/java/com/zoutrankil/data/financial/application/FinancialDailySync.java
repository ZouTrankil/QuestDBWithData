package com.zoutrankil.data.financial.application;

import com.zoutrankil.data.config.TushareProperties;
import com.zoutrankil.data.domain.JobDefinitionJson;
import com.zoutrankil.data.domain.SyncJobDefinition;
import com.zoutrankil.data.domain.SyncJobDefinition.FrozenRequest;
import com.zoutrankil.data.financial.config.FinancialQueueFactory;
import com.zoutrankil.data.financial.domain.FinancialContract;
import com.zoutrankil.data.repository.FileEvidenceStore;
import com.zoutrankil.market.cn.time.CnMarketTime;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication.Type;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.TreeMap;
import java.util.stream.Collectors;

/** 单独启用才调度；证券范围由摘要固定的清单提供，不从当前目标行数猜全市场。 */
@Configuration
@EnableScheduling
@Profile("!main-strategy")
@ConditionalOnWebApplication(type = Type.REACTIVE)
@ConditionalOnProperty(name = "app.sync.financial.enabled", havingValue = "true")
public class FinancialDailySync {
    private final List<FinancialSyncOwner> owners;
    private final Path scope;
    private final String sha;
    private final LocalDate earliest;
    private final TushareProperties quota;
    private final long maxAttempts;
    private final Duration window;

    public FinancialDailySync(
            List<FinancialSyncOwner> owners,
            @Value("${app.sync.financial.scope-file}") String scope,
            @Value("${app.sync.financial.scope-sha256}") String sha,
            @Value("${app.sync.financial.earliest-period:2018-03-31}") String earliest,
            TushareProperties quota,
            @Value("${app.sync.financial.max-request-attempts:1000}") long maxAttempts,
            @Value("${app.sync.financial.execution-window:PT4H}") Duration window) {
        if (scope.isBlank()
                || !sha.matches("[a-f0-9]{64}")
                || owners.size() != 5
                || !owners.stream()
                        .map(FinancialSyncOwner::datasetId)
                        .collect(Collectors.toSet())
                        .equals(FinancialContract.DATASETS))
            throw new IllegalArgumentException("启用财务日批需五条真实执行器、范围文件及批准 SHA-256");
        this.owners = List.copyOf(owners);
        this.scope = Path.of(scope);
        this.sha = sha;
        this.earliest = LocalDate.parse(earliest);
        this.quota = quota;
        this.maxAttempts = maxAttempts;
        this.window = window;
    }

    @Scheduled(cron = "${app.sync.financial.cron:0 30 19 * * *}", zone = "Asia/Shanghai")
    public void scheduled() throws Exception {
        runAt(CnMarketTime.system().today());
    }

    public void runAt(LocalDate logicalDate) throws Exception {
        var bytes =
                FileEvidenceStore.readBounded(
                        scope, 1024 * 1024, () -> new IllegalStateException("财务范围清单过大"));
        if (!sha.matches("[a-f0-9]{64}") || !FileEvidenceStore.sha256(bytes).equals(sha))
            throw new IllegalStateException("财务范围清单摘要不符");
        var doc = JobDefinitionJson.mapper().readTree(bytes);
        if (!"java.financial.scope.v1".equals(doc.path("schema").asText())
                || !doc.path("codes").isArray()
                || logicalDate.isBefore(LocalDate.parse(doc.required("validFrom").asText()))
                || logicalDate.isAfter(LocalDate.parse(doc.required("validThrough").asText())))
            throw new IllegalStateException("财务证券范围未批准或已过期");
        var codes = new ArrayList<String>();
        for (var item : doc.path("codes")) {
            if (!item.isTextual()) throw new IllegalArgumentException("财务证券代码必须为文本");
            codes.add(item.textValue());
        }
        var periods = FinancialSyncOwner.dailyPeriods(logicalDate, earliest);
        var ordered =
                owners.stream()
                        .sorted(Comparator.comparing(FinancialSyncOwner::datasetId))
                        .toList();
        var ledgers = ordered.stream().map(FinancialSyncOwner::ledgerPath).distinct().toList();
        if (ledgers.size() != 1) throw new IllegalStateException("财务分片必须使用同一原账本");
        // 先验证全部范围，再切成单证券、单季度、单来源；五条来源轮流处理，防止后面的来源饿死。
        for (var owner : ordered) {
            owner.plan(codes, periods, logicalDate, SyncJobDefinition.Mode.INCREMENTAL);
            FinancialDailyBudget.requireFits(
                    1, 1, List.of(owner.datasetId()), quota, maxAttempts, window);
        }
        var byDataset =
                ordered.stream()
                        .collect(Collectors.toMap(FinancialSyncOwner::datasetId, owner -> owner));
        var targets = new TreeMap<String, String>();
        for (var owner : ordered) targets.put(owner.datasetId(), owner.publicationTargetId());
        var queue = FinancialQueueFactory.forLedger(ledgers.getFirst());
        queue.runPlanned(
                sha,
                logicalDate,
                previous -> {
                    LocalDate previousOld = null;
                    if (previous != null) {
                        var oldPeriods =
                                previous.slices().stream()
                                        .map(FinancialDailyQueue.Slice::period)
                                        .distinct()
                                        .sorted()
                                        .toList();
                        if (oldPeriods.size() > 4) previousOld = oldPeriods.getFirst();
                    }
                    var selected =
                            FinancialSyncOwner.queuedPeriods(logicalDate, earliest, previousOld);
                    var slices = new ArrayList<FinancialDailyQueue.Slice>();
                    for (var period : selected.stream().sorted(Comparator.reverseOrder()).toList())
                        for (var code : codes.stream().sorted().toList())
                            for (var owner : ordered)
                                slices.add(
                                        new FinancialDailyQueue.Slice(
                                                owner.datasetId(), code, period));
                    return slices;
                },
                maxAttempts,
                quota.getMaxAttempts(),
                window,
                new FinancialDailyQueue.Executor() {
                    public String targetFingerprint() throws Exception {
                        var current = new TreeMap<String, String>();
                        for (var owner : ordered)
                            current.put(owner.datasetId(), owner.publicationTargetId());
                        return FileEvidenceStore.sha256(
                                JobDefinitionJson.canonicalMapper().writeValueAsBytes(current));
                    }

                    private FrozenRequest request(FinancialDailyQueue.Slice slice, LocalDate date) {
                        return byDataset
                                .get(slice.dataset())
                                .plan(
                                        List.of(slice.code()),
                                        List.of(slice.period()),
                                        date,
                                        SyncJobDefinition.Mode.INCREMENTAL);
                    }

                    public void run(
                            FinancialDailyQueue.Slice slice,
                            LocalDate date,
                            String runId,
                            Duration remaining)
                            throws Exception {
                        byDataset
                                .get(slice.dataset())
                                .runQueued(
                                        request(slice, date),
                                        runId,
                                        remaining,
                                        targets.get(slice.dataset()));
                    }

                    public boolean verified(
                            FinancialDailyQueue.Slice slice, LocalDate date, String runId)
                            throws Exception {
                        return byDataset
                                .get(slice.dataset())
                                .queuedVerified(request(slice, date), runId);
                    }
                });
    }
}
