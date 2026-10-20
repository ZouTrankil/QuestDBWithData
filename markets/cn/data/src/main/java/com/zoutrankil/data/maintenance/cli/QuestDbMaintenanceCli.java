package com.zoutrankil.data.maintenance.cli;

import com.zoutrankil.data.config.QuestDbProperties;
import com.zoutrankil.data.domain.JobDefinitionJson;
import com.zoutrankil.data.maintenance.application.CnReferenceDateReadInspection;
import com.zoutrankil.data.maintenance.application.CnTimeColumnRehearsal;
import com.zoutrankil.data.maintenance.application.EtfOverviewMaintenance;
import com.zoutrankil.data.maintenance.application.LegacySyncMetadataRetirement;
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
        if ((args.length != 1 && !withReport)
                || !Set.of(
                                "status",
                                "refresh-etf-overview",
                                "retire-sync-metadata",
                                "rehearse-cn-time-columns",
                                "inspect-cn-reference-dates")
                        .contains(args[0])) {
            throw new IllegalArgumentException(
                    "维护命令：status|refresh-etf-overview|retire-sync-metadata|rehearse-cn-time-columns|inspect-cn-reference-dates [--output 新文件]");
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
                                    Duration.ofMinutes(2)));
            Object result =
                    switch (args[0]) {
                        case "status" ->
                                Map.of(
                                        "materializedViews",
                                        jdbc.queryForList("SELECT * FROM materialized_views()"),
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
                        case "refresh-etf-overview" -> maintenance.refresh();
                        case "retire-sync-metadata" -> {
                            var refresh = maintenance.refresh();
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
