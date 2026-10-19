package com.zoutrankil.data.config;

import com.zoutrankil.data.domain.shared.SyncJobOwner;
import com.zoutrankil.data.domain.l2.L2T0TrainingLabels;
import com.zoutrankil.data.domain.l2.L2IntradayBarFeatures;
import com.zoutrankil.data.domain.l2.L2DatasetManifest;
import com.zoutrankil.data.domain.l2.L2DailyFeatures;
import com.zoutrankil.data.QuestDataApplication;
import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.l2.application.*;
import com.zoutrankil.data.l2.port.*;
import com.zoutrankil.data.service.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.*;
import org.springframework.core.env.MapPropertySource;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class L2ApplicationWiringTest {
    @TempDir Path temporary;

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void fiveFamiliesRetainConfigurationContractsAndNoIoAssembly(boolean override) throws Exception {
        var dataSource = mock(DataSource.class);
        Path ledger = temporary.resolve("uncreated.sqlite3");
        var properties = new LinkedHashMap<String, Object>();
        properties.put("app.sync.ledger-path", ledger.toString());
        properties.put("spring.main.banner-mode", "off");
        String[] settings = {"l2-manifest", "l2-daily-features", "l2-intraday-bar-features",
                "l2-event-response-features", "l2-t0-training-labels"};
        String[] tables = {"java_d085_manifest_wiring", "java_d086_daily_wiring", "java_d087_intraday_wiring",
                "java_d088_event_wiring", "java_d089_labels_wiring"};
        if (override) for (int i = 0; i < settings.length; i++) {
            String prefix = "app.sync." + settings[i] + ".";
            properties.put(prefix + "target-table", tables[i]);
            properties.put(prefix + "dataset-root", temporary.resolve("source-" + i).toString());
        }
        var app = new SpringApplication(QuestDataApplication.class);
        app.setWebApplicationType(WebApplicationType.NONE);
        app.addInitializers(context -> {
            context.getBeanFactory().registerSingleton("dataSource", dataSource);
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("l2-wiring", properties));
        });
        try (var context = app.run("list-sync-jobs")) {
            Class<?>[] targets = {L2DatasetManifestTarget.class, L2DailyFeaturesTarget.class,
                    L2IntradayBarFeaturesTarget.class, L2EventResponseFeaturesTarget.class, L2T0TrainingLabelsTarget.class};
            Class<?>[] sources = {L2DatasetManifestParquetSource.class, L2DailyFeaturesParquetSource.class,
                    L2IntradayBarFeaturesParquetSource.class, L2EventResponseFeaturesParquetSource.class, L2T0TrainingLabelsParquetSource.class};
            Class<?>[] owners = {L2DatasetManifestJobService.class, L2DailyFeaturesJobService.class,
                    L2IntradayBarFeaturesJobService.class, L2EventResponseFeaturesJobService.class, L2T0TrainingLabelsJobService.class};
            for (int i = 0; i < targets.length; i++) {
                assertEquals(1, context.getBeansOfType(targets[i]).size());
                assertEquals(1, context.getBeansOfType(sources[i]).size());
                Object target = context.getBean(targets[i]);
                assertEquals(override ? tables[i] : "", targets[i].getMethod("tableName").invoke(target));
                Object source = context.getBean(sources[i]);
                Path root = override ? temporary.resolve("source-" + i)
                        : Path.of(context.getEnvironment().getRequiredProperty("app.paths.l2-dataset-root"));
                assertEquals(root.toAbsolutePath().normalize(), sources[i].getMethod("datasetRoot").invoke(source));
                assertThrows(NoSuchFieldException.class, () -> source.getClass().getDeclaredField("helper"));
                assertThrows(NoSuchFieldException.class, () -> source.getClass().getDeclaredField("pythonExecutable"));
                var owner = (SyncJobOwner) context.getBean(owners[i]);
                var definition = owner.syncJobDefinitions().getFirst();
                assertEquals(definition, context.getBean(SyncJobRegistry.class).require(definition.jobId(), 1));
                assertEquals(Set.of(SyncJobDefinition.Mode.INCREMENTAL, SyncJobDefinition.Mode.BACKFILL,
                        SyncJobDefinition.Mode.RECONCILE, SyncJobDefinition.Mode.INGEST), owner.supportedSyncModes());
                assertEquals(new SyncJobDefinition.Budget(31, i < 2 ? 25_000 : 10_000, 25_000, 300_000, 1_048_576), definition.budget());
                assertEquals(Duration.ofHours(2), definition.timeout());
                assertEquals(3, definition.revisionDays());
                var dependencies = new ArrayList<SyncJobDefinition.JobRef>();
                dependencies.add(new SyncJobDefinition.JobRef(i == 0 ? "data.exchange_calendar" : "data.l2_dataset_manifest", 1));
                if (i >= 3) dependencies.add(new SyncJobDefinition.JobRef("data.l2_intraday_bar_features", 1));
                assertEquals(dependencies, definition.dependencies());
            }
            var datasets = context.getBean(DatasetRegistry.class);
            var readers = context.getBean(ReadBindingCatalog.class);
            assertEquals(52, datasets.definitions().stream().filter(value -> value.capabilities().contains(DatasetDefinition.Capability.READ)).count());
            assertEquals(52, readers.bind(datasets).size());
            var representations = new HashMap<String, Class<?>>();
            readers.registrations().forEach(value -> representations.put(value.datasetId(), value.rowType()));
            assertEquals(L2DatasetManifest.class, representations.get("l2_dataset_manifest"));
            assertEquals(L2DailyFeatures.class, representations.get("l2_daily_features"));
            assertEquals(L2IntradayBarFeatures.class, representations.get("l2_intraday_bar_features"));
            assertEquals(L2T0TrainingLabels.class, representations.get("l2_t0_training_labels"));
            assertEquals(DatasetValues.class, representations.get("l2_event_response_features"));
            assertEquals(1, context.getBeansOfType(FormalL2DailyFeaturesIngestion.class).size());
            verifyNoInteractions(dataSource);
            assertFalse(Files.exists(ledger));
        }
    }

}
