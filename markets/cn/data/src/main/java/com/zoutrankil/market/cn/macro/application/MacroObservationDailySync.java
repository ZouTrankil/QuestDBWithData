package com.zoutrankil.market.cn.macro.application;

import com.zoutrankil.data.domain.SyncJobDefinition.Mode;
import com.zoutrankil.data.domain.SyncRunState;
import com.zoutrankil.market.cn.macro.mapper.MacroObservationDefinition;
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
import java.time.temporal.TemporalAdjusters;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.stream.Collectors;

/** 每日重查有限统计期窗口，等待未发布期次并接收修订；不按 SSE 开市日筛选宏观数据。 */
@Configuration
@EnableScheduling
@Profile("!main-strategy")
@ConditionalOnWebApplication(type = Type.REACTIVE)
@ConditionalOnProperty(name = "app.sync.macro-observations.enabled", havingValue = "true")
public class MacroObservationDailySync {
    private final List<MacroObservationSyncOwner> owners;
    private final int months;
    private final int quarters;

    public MacroObservationDailySync(
            List<MacroObservationSyncOwner> owners,
            @Value("${app.sync.macro-observations.revision-months:6}") int months,
            @Value("${app.sync.macro-observations.revision-quarters:4}") int quarters) {
        if (months < 1
                || months > 12
                || quarters < 1
                || quarters > 12
                || owners.size() != MacroObservationDefinition.DATASETS.size()
                || !owners.stream()
                        .map(MacroObservationSyncOwner::datasetId)
                        .collect(Collectors.toSet())
                        .equals(MacroObservationDefinition.DATASETS))
            throw new IllegalArgumentException("宏观日批需要六条真实来源和 1..12 个统计期窗口");
        this.owners =
                owners.stream()
                        .sorted(Comparator.comparing(MacroObservationSyncOwner::datasetId))
                        .toList();
        this.months = months;
        this.quarters = quarters;
    }

    @Scheduled(cron = "${app.sync.macro-observations.cron:0 10 19 * * *}", zone = "Asia/Shanghai")
    public void scheduled() throws Exception {
        runAt(CnMarketTime.system().today());
    }

    public void runAt(LocalDate day) throws Exception {
        var plans =
                owners.stream()
                        .map(
                                owner -> {
                                    LocalDate to, from;
                                    if (owner.datasetId().equals("cn_gdp")) {
                                        int quarterStart = (day.getMonthValue() - 1) / 3 * 3 + 1;
                                        to =
                                                day.withMonth(quarterStart)
                                                        .withDayOfMonth(1)
                                                        .minusDays(1);
                                        from =
                                                to.minusMonths(3L * (quarters - 1))
                                                        .with(TemporalAdjusters.lastDayOfMonth());
                                    } else {
                                        to = day.withDayOfMonth(1).minusMonths(1);
                                        from = to.minusMonths(months - 1L);
                                    }
                                    return owner.plan(from, to, day, Mode.RECONCILE);
                                })
                        .toList();
        var failure = new IllegalStateException("部分宏观来源尚未验收，查看原同步账本；不标记整批完成");
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
