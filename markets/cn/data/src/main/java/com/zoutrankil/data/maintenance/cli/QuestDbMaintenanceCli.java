package com.zoutrankil.data.maintenance.cli;

import com.zoutrankil.data.config.QuestDbProperties;
import com.zoutrankil.data.domain.JobDefinitionJson;
import com.zoutrankil.data.maintenance.application.EtfOverviewMaintenance;
import com.zoutrankil.data.maintenance.application.LegacySyncMetadataRetirement;
import com.zoutrankil.runtime.ProjectConfiguration;
import com.zoutrankil.runtime.ProjectLogging;

import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;

/** Finite Java maintenance entry point; loads deployment configuration without starting unrelated jobs. */
public final class QuestDbMaintenanceCli {
    private QuestDbMaintenanceCli() {}

    public static void main(String[] args) throws Exception {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        if (args.length != 1
                || !Set.of("status", "refresh-etf-overview", "retire-sync-metadata")
                        .contains(args[0]))
            throw new IllegalArgumentException(
                    "Command required: status|refresh-etf-overview|retire-sync-metadata");
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
            System.out.println(
                    JobDefinitionJson.mapper()
                            .writerWithDefaultPrettyPrinter()
                            .writeValueAsString(result));
        }
    }
}
