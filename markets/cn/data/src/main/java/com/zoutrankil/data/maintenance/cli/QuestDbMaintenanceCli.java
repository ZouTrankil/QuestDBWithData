package com.zoutrankil.data.maintenance.cli;

import com.zoutrankil.data.config.BacktestDailyLayoutConfiguration;
import com.zoutrankil.data.config.EtfOverviewLayoutConfiguration;
import com.zoutrankil.data.config.QuestDbProperties;
import com.zoutrankil.data.domain.JobDefinitionJson;
import com.zoutrankil.data.maintenance.application.BacktestMvMaintenance;
import com.zoutrankil.data.maintenance.application.CnReferenceDateReadInspection;
import com.zoutrankil.data.maintenance.application.CnTimeColumnRehearsal;
import com.zoutrankil.data.maintenance.application.EtfOverviewMaintenance;
import com.zoutrankil.data.maintenance.application.LegacySyncMetadataRetirement;
import com.zoutrankil.data.maintenance.storage.BacktestRefreshJournal;
import com.zoutrankil.data.maintenance.storage.EtfOverviewRefreshJournal;
import com.zoutrankil.market.cn.time.CnMarketTime;
import com.zoutrankil.runtime.ProjectConfiguration;
import com.zoutrankil.runtime.ProjectLogging;

import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;

/** 有限维护入口：加载部署配置，不启动来源同步或常驻作业。 */
public final class QuestDbMaintenanceCli {
    private QuestDbMaintenanceCli() {}

    public static void main(String[] args) throws Exception {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        boolean withReport =
                args.length == 3
                        && Set.of("rehearse-cn-time-columns", "inspect-cn-reference-dates")
                                .contains(args[0])
                        && args[1].equals("--output");
        boolean reconcile =
                args.length == 4
                        && Set.of("reconcile-etf-overview", "reconcile-backtest").contains(args[0])
                        && args[1].equals("--run-id")
                        && args[3].equals("--original-sender-stopped")
                        && !args[2].isBlank();
        boolean backtestRefresh =
                args.length == 2
                        && args[0].equals("refresh-backtest")
                        && args[1].equals("--sources-stopped");
        if ((args.length != 1 && !withReport && !reconcile && !backtestRefresh)
                || (args.length > 0
                        && Set.of("reconcile-etf-overview", "reconcile-backtest").contains(args[0])
                        && !reconcile)
                || (args.length > 0 && args[0].equals("refresh-backtest") && !backtestRefresh)
                || !Set.of(
                                "status",
                                "refresh-etf-overview",
                                "reconcile-etf-overview",
                                "plan-backtest-refresh",
                                "refresh-backtest",
                                "reconcile-backtest",
                                "retire-sync-metadata",
                                "rehearse-cn-time-columns",
                                "inspect-cn-reference-dates")
                        .contains(args[0])) {
            throw new IllegalArgumentException(
                    "维护命令：status|plan-backtest-refresh|refresh-backtest --sources-stopped|reconcile-backtest --run-id 原意图ID --original-sender-stopped|refresh-etf-overview|reconcile-etf-overview --run-id 原意图ID --original-sender-stopped|retire-sync-metadata|rehearse-cn-time-columns|inspect-cn-reference-dates [--output 新文件]");
        }
        Path reportPath = withReport ? Path.of(args[2]).toAbsolutePath().normalize() : null;
        if (reportPath != null && Files.exists(reportPath))
            throw new IllegalArgumentException("演练报告已存在，不能覆盖原证据");
        var env = ProjectConfiguration.load("data");
        ProjectLogging.initialize(env);
        var db = Binder.get(env).bind("app.questdb", QuestDbProperties.class).get();
        var source =
                new DriverManagerDataSource(
                        env.getRequiredProperty("spring.datasource.url"),
                        db.getUsername(),
                        db.getPassword());
        try (var connection = source.getConnection()) {
            var jdbc = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
            jdbc.setQueryTimeout(120);
            String ledger = env.getRequiredProperty("app.sync.ledger-path");
            var maintenance =
                    new EtfOverviewMaintenance(
                            jdbc,
                            ledger,
                            env.getProperty(
                                    "app.materialized-views.etf-overview.refresh-timeout",
                                    Duration.class,
                                    Duration.ofMinutes(2)),
                            EtfOverviewLayoutConfiguration.resolve(env));
            var backtest =
                    new BacktestMvMaintenance(
                            jdbc,
                            Path.of(ledger),
                            env.getProperty(
                                    "app.materialized-views.backtest.refresh-timeout",
                                    Duration.class,
                                    Duration.ofMinutes(2)),
                            BacktestDailyLayoutConfiguration.resolve(env));
            Object result =
                    switch (args[0]) {
                        case "status" ->
                                Map.of(
                                        "materializedViews",
                                        jdbc.queryForList("SELECT * FROM materialized_views()"),
                                        "pendingEtfRefreshes",
                                        new EtfOverviewRefreshJournal(Path.of(ledger))
                                                .pendingOperations(),
                                        "pendingBacktestRefreshes",
                                        new BacktestRefreshJournal(Path.of(ledger))
                                                .pendingOperations(),
                                        "legacyTables",
                                        jdbc.queryForList(
                                                "SELECT table_name FROM tables() WHERE table_name IN ('"
                                                        + String.join(
                                                                "','",
                                                                LegacySyncMetadataRetirement.TABLES)
                                                        + "')"));
                        case "rehearse-cn-time-columns" -> new CnTimeColumnRehearsal(jdbc).run();
                        case "inspect-cn-reference-dates" -> {
                            var day = CnMarketTime.system().today();
                            yield new CnReferenceDateReadInspection(jdbc)
                                    .inspect(day.minusYears(1), day.plusDays(1));
                        }
                        case "plan-backtest-refresh" -> backtest.plan();
                        case "refresh-backtest" -> backtest.refresh(true);
                        case "reconcile-backtest" -> backtest.reconcile(args[2], true);
                        case "refresh-etf-overview" -> maintenance.refresh();
                        case "reconcile-etf-overview" -> maintenance.reconcile(args[2], true);
                        case "retire-sync-metadata" -> {
                            var refresh =
                                    new EtfOverviewMaintenance(
                                                    jdbc,
                                                    ledger,
                                                    env.getProperty(
                                                            "app.materialized-views.etf-overview.refresh-timeout",
                                                            Duration.class,
                                                            Duration.ofMinutes(2)))
                                            .refresh();
                            var retirement =
                                    new LegacySyncMetadataRetirement(
                                                    jdbc,
                                                    Path.of(ledger),
                                                    Path.of(
                                                            env.getRequiredProperty(
                                                                    "app.maintenance.legacy-metadata-archive")))
                                            .retire();
                            yield Map.of("refresh", refresh, "retirement", retirement);
                        }
                        default ->
                                throw new IllegalArgumentException("Unknown maintenance command");
                    };
            String json =
                    JobDefinitionJson.mapper()
                            .writerWithDefaultPrettyPrinter()
                            .writeValueAsString(result);
            if (reportPath != null) {
                Files.createDirectories(reportPath.getParent());
                Files.writeString(reportPath, json + "\n", StandardOpenOption.CREATE_NEW);
            }
            System.out.println(json);
            if (args[0].equals("inspect-cn-reference-dates")
                    && result instanceof Map<?, ?> report
                    && !Boolean.TRUE.equals(report.get("allLimitedReadsSucceeded")))
                throw new IllegalStateException("只读日期检查未全部取得有效样本，详见逐对象结果；未修改表");
            if (args[0].equals("rehearse-cn-time-columns")
                    && result instanceof Map<?, ?> report
                    && !Boolean.TRUE.equals(report.get("isolatedRehearsalVerified"))) {
                throw new IllegalStateException("隔离演练未全部通过，详见上方逐对象结果；正式表未改");
            }
        }
    }
}
