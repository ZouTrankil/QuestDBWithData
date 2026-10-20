package com.zoutrankil.data.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.zoutrankil.data.QuestDataApplication;
import com.zoutrankil.data.derived.application.RetailSentimentDailyJobService;
import com.zoutrankil.data.derived.port.RetailSentimentDailyTarget;
import com.zoutrankil.data.derived.storage.RetailSentimentDailyReadRepository;
import com.zoutrankil.data.domain.DatasetImplementation;
import com.zoutrankil.data.domain.DatasetReadPage;
import com.zoutrankil.data.domain.DatasetReadQuery;
import com.zoutrankil.data.domain.DatasetValues;
import com.zoutrankil.data.domain.JobDefinitionJson;
import com.zoutrankil.data.domain.ReadGroupRequest;
import com.zoutrankil.data.domain.SyncJobDefinition.Mode;
import com.zoutrankil.data.domain.l2.L2DailyFeatureField;
import com.zoutrankil.data.domain.l2.L2DailyFeatures;
import com.zoutrankil.data.domain.l2.L2DatasetManifestDataset;
import com.zoutrankil.data.domain.stock.basic.ExchangeCalendarDataset;
import com.zoutrankil.data.l2.application.FormalL2DailyFeaturesIngestion;
import com.zoutrankil.data.l2.application.L2DailyFeaturesJobService;
import com.zoutrankil.data.l2.application.L2DailyFeaturesParquetSource;
import com.zoutrankil.data.l2.mapper.L2DailyFeaturesMapper;
import com.zoutrankil.data.l2.port.L2DailyFeaturesTarget;
import com.zoutrankil.data.l2.port.L2DailyFeaturesWriteSession;
import com.zoutrankil.data.l2.storage.L2DailyFeaturesReadRepository;
import com.zoutrankil.data.repository.QuestDbBoundedReader;
import com.zoutrankil.data.service.DatasetRegistry;
import com.zoutrankil.data.service.ReadBindingCatalog;
import com.zoutrankil.data.service.ReadGroupReader;
import com.zoutrankil.data.service.SyncJobRegistry;
import com.zoutrankil.market.cn.model.CnTradeDateLayout;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.MapPropertySource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import javax.sql.DataSource;

class L2DailyFeaturesConfiguredLayoutTest {
    private static final String TABLE = "java_d086_l2_daily_features_configured";
    private static final LocalDate DAY = LocalDate.of(2026, 9, 28);
    @TempDir Path root;

    @ParameterizedTest
    @ValueSource(ints = {1, 2})
    void selectedJobTargetTypedReadAndPublicBindingUseOneLayoutWithoutIo(int version)
            throws Exception {
        var source = mock(DataSource.class);
        var ledger = root.resolve("absent.sqlite3");
        try (var context =
                start(
                        source,
                        ledger,
                        Map.of(
                                "app.sync.l2-daily-features-schema-version",
                                version,
                                "app.sync.l2-daily-features.target-table",
                                TABLE))) {
            var target = context.getBean(L2DailyFeaturesTarget.class);
            assertEquals(version, L2DailyFeaturesTarget.versionOf(target));
            assertEquals(TABLE, target.tableName());
            assertEquals(
                    version,
                    ((L2DailyFeaturesWriteSession.Versioned) target.newWriter()).datasetVersion());
            var jobs = context.getBean(SyncJobRegistry.class);
            assertEquals(
                    L2DailyFeaturesJobService.definition(version),
                    jobs.require("data.l2_daily_features", version));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> jobs.require("data.l2_daily_features", 3 - version));
            assertEquals(
                    RetailSentimentDailyJobService.definition(version),
                    jobs.require("data.mv_retail_sentiment_daily", version));
            var mvLayout =
                    RetailSentimentDailyTarget.layoutOf(
                            context.getBean(RetailSentimentDailyTarget.class));
            assertEquals(version, mvLayout.version());
            assertEquals(version == 1 ? "l2_daily_features" : TABLE, mvLayout.sourceTable());
            assertEquals(
                    version == 1
                            ? "mv_retail_sentiment_daily"
                            : "java_d098_mv_retail_sentiment_daily_v2",
                    mvLayout.outputTable());
            var datasets = context.getBean(DatasetRegistry.class);
            var selected = datasets.require("l2_daily_features").definition();
            assertEquals(version, selected.schemaVersion());
            assertEquals(TABLE, selected.objectName());
            assertEquals(version == 1 ? "ts" : "trade_date", selected.designatedTimestamp());
            assertEquals(110, selected.columns().size());
            var mvSelected = datasets.require("mv_retail_sentiment_daily").definition();
            assertEquals(version, mvSelected.schemaVersion());
            assertEquals(mvLayout.outputTable(), mvSelected.objectName());
            assertEquals(13, mvSelected.columns().size());
            assertEquals(
                    mvSelected,
                    context.getBean(RetailSentimentDailyReadRepository.class).definition());
            assertEquals(
                    selected, context.getBean(L2DailyFeaturesReadRepository.class).definition());
            var binding =
                    context.getBean(ReadBindingCatalog.class).bind(datasets).stream()
                            .filter(
                                    value ->
                                            value.definition()
                                                    .datasetId()
                                                    .equals("l2_daily_features"))
                            .findFirst()
                            .orElseThrow();
            assertEquals(selected, binding.definition());
            assertEquals(L2DailyFeatures.class, binding.rowType());
            var layout = CnTradeDateLayout.fromVersion(version);
            var row = row();
            assertEquals(
                    row, binding.mapper().apply(new L2DailyFeaturesMapper().values(row, layout)));
            var parameters = new LinkedHashMap<String, Object>();
            parameters.put("source_root_id", "c".repeat(64));
            if (version == 2)
                parameters.put(
                        "source_inspection",
                        JobDefinitionJson.mapper()
                                .writeValueAsString(
                                        new L2DailyFeaturesParquetSource.Inspection(
                                                DAY,
                                                DAY,
                                                List.of(DAY),
                                                1,
                                                1,
                                                1,
                                                1,
                                                12,
                                                "a".repeat(64),
                                                "b".repeat(64),
                                                "l2-daily-features-parquet-v1",
                                                "c".repeat(64),
                                                true)));
            assertEquals(
                    version,
                    jobs.prepare(
                                    "data.l2_daily_features",
                                    version,
                                    Mode.BACKFILL,
                                    parameters,
                                    DAY,
                                    DAY,
                                    DAY)
                            .definition()
                            .datasetVersion());
            var formal = context.getBean(FormalL2DailyFeaturesIngestion.class);
            var field = FormalL2DailyFeaturesIngestion.class.getDeclaredField("writer");
            field.setAccessible(true);
            var formalWriter = (L2DailyFeaturesWriteSession.Versioned) field.get(formal);
            assertEquals(1, formalWriter.datasetVersion());
            assertEquals("l2_daily_features", formalWriter.tableName());
            assertFalse(context.getBeanFactory().containsSingleton("questDbClient"));
            verifyNoInteractions(source);
            assertFalse(Files.exists(ledger));
        }
    }

    @Test
    void originalDefaultsKeepFormalReadAndRequireExplicitWriteTarget() throws Exception {
        var source = mock(DataSource.class);
        var ledger = root.resolve("default.sqlite3");
        try (var context = start(source, ledger, Map.of())) {
            var target = context.getBean(L2DailyFeaturesTarget.class);
            assertEquals(1, L2DailyFeaturesTarget.versionOf(target));
            assertEquals("", target.tableName());
            assertEquals(
                    "l2_daily_features",
                    context.getBean(L2DailyFeaturesReadRepository.class).definition().objectName());
            assertEquals(
                    1,
                    context.getBean(L2DailyFeaturesReadRepository.class)
                            .definition()
                            .schemaVersion());
            verifyNoInteractions(source);
            assertFalse(Files.exists(ledger));
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2})
    void readGroupUsesTheSelectedTypedMapperAndRejectsOtherVersionsBeforeIo(int version) {
        var reader = mock(QuestDbBoundedReader.class);
        var layout = CnTradeDateLayout.fromVersion(version);
        var repo = new L2DailyFeaturesReadRepository(reader, TABLE, layout);
        var datasets = registry(repo);
        var binding =
                new ReadBindingCatalog(DefaultReadBindings.registrations(1, 1, 1, 1, 1, version));
        var group = new ReadGroupReader(datasets, reader, binding.bind(datasets));
        when(reader.read(any(), any(), isNull(), any()))
                .thenAnswer(
                        call -> {
                            Function<DatasetValues, Object> mapper = call.getArgument(3);
                            return new DatasetReadPage<>(
                                    "l2_daily_features",
                                    version,
                                    null,
                                    Instant.EPOCH,
                                    List.of(
                                            mapper.apply(
                                                    new L2DailyFeaturesMapper()
                                                            .values(row(), layout))),
                                    null);
                        });
        var query =
                new DatasetReadQuery(
                        L2DailyFeaturesMapper.columns(layout),
                        Map.of(layout.physicalDateColumn("l2_daily_features"), DAY),
                        null,
                        null,
                        null,
                        2,
                        null);
        var request =
                new ReadGroupRequest(
                        List.of(
                                new ReadGroupRequest.Member(
                                        "daily", "l2_daily_features", version, query)),
                        Duration.ofSeconds(5));
        var result = group.read(request, () -> false);
        assertTrue(result.complete());
        assertEquals(
                List.of(row()), result.require("daily").typedPage(L2DailyFeatures.class).rows());
        clearInvocations(reader);
        var wrong =
                new ReadGroupRequest(
                        List.of(
                                new ReadGroupRequest.Member(
                                        "daily", "l2_daily_features", 3 - version, query)),
                        Duration.ofSeconds(5));
        assertFalse(group.read(wrong, () -> false).complete());
        verifyNoInteractions(reader);
        var onlyNew =
                registry(
                        new L2DailyFeaturesReadRepository(
                                reader, TABLE, CnTradeDateLayout.TRADE_DATE_V2));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ReadBindingCatalog(DefaultReadBindings.registrations()).bind(onlyNew));
    }

    private static DatasetRegistry registry(L2DailyFeaturesReadRepository repo) {
        return new DatasetRegistry(
                List.<DatasetImplementation>of(
                        repo,
                        () -> L2DatasetManifestDataset.DEFINITION,
                        () -> ExchangeCalendarDataset.DEFINITION));
    }

    private static L2DailyFeatures row() {
        var fields = new EnumMap<L2DailyFeatureField, Object>(L2DailyFeatureField.class);
        for (var field : L2DailyFeatureField.values())
            if (!field.identity()) fields.put(field, null);
        return new L2DailyFeatures(DAY, "000004.SZ", fields);
    }

    private ConfigurableApplicationContext start(
            DataSource source, Path ledger, Map<String, Object> values) {
        var app = new SpringApplication(QuestDataApplication.class);
        app.setWebApplicationType(WebApplicationType.NONE);
        app.addInitializers(
                context -> {
                    context.getBeanFactory().registerSingleton("dataSource", source);
                    var properties = new LinkedHashMap<String, Object>(values);
                    properties.put("app.sync.ledger-path", ledger.toString());
                    properties.put("spring.main.banner-mode", "off");
                    context.getEnvironment()
                            .getPropertySources()
                            .addFirst(new MapPropertySource("layout", properties));
                });
        return app.run("list-sync-jobs");
    }
}
