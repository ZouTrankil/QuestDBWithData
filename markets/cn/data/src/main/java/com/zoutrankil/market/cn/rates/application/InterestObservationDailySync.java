package com.zoutrankil.market.cn.rates.application;

import com.zoutrankil.data.domain.SyncJobDefinition.Mode;
import com.zoutrankil.data.domain.SyncRunState;
import com.zoutrankil.market.cn.time.CnMarketTime;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication.Type;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.stream.Collectors;

/** 利率独立重查自然日窗口，实际报价日由来源响应决定；同一未决写入仍阻止重发。 */
@Configuration
@EnableScheduling
@Profile("!main-strategy")
@ConditionalOnWebApplication(type = Type.REACTIVE)
@ConditionalOnProperty(name = "app.sync.interest-observations.enabled", havingValue = "true")
public class InterestObservationDailySync {
    private final List<InterestObservationSyncOwner> owners;
    private final int days;

    public InterestObservationDailySync(
            List<InterestObservationSyncOwner> owners,
            @Value("${app.sync.interest-observations.revision-days:31}") int days) {
        if (days < 1
                || days > 31
                || owners.size() != InterestObservationSyncOwner.DATASETS.size()
                || !owners.stream()
                        .map(InterestObservationSyncOwner::datasetId)
                        .collect(Collectors.toSet())
                        .equals(InterestObservationSyncOwner.DATASETS))
            throw new IllegalArgumentException("利率日批需要三条真实来源和 1..31 个自然日窗口");
        this.owners =
                owners.stream()
                        .sorted(Comparator.comparing(InterestObservationSyncOwner::datasetId))
                        .toList();
        this.days = days;
    }

    @Scheduled(
            cron = "${app.sync.interest-observations.cron:0 40 18 * * *}",
            zone = "Asia/Shanghai")
    public void scheduled() throws Exception {
        runAt(CnMarketTime.system().today());
    }

    public void runAt(LocalDate day) throws Exception {
        var plans =
                owners.stream()
                        .map(
                                owner ->
                                        owner.plan(
                                                day.minusDays(days - 1L), day, day, Mode.RECONCILE))
                        .toList();
        var failure = new IllegalStateException("部分利率来源尚未验收，查看原同步账本；不标记整批完成");
        boolean failed = false;
        for (int i = 0; i < owners.size(); i++) {
            try {
                var run = owners.get(i).run(plans.get(i));
                if (!Set.of(SyncRunState.VERIFIED, SyncRunState.VERIFIED_EMPTY)
                        .contains(run.state()))
                    throw new IllegalStateException(
                            owners.get(i).datasetId() + " 原运行：" + run.runId());
            } catch (Exception error) {
                if (Thread.currentThread().isInterrupted()
                        || error instanceof CancellationException) throw error;
                failed = true;
                failure.addSuppressed(error);
            }
        }
        if (failed) throw failure;
    }
}
