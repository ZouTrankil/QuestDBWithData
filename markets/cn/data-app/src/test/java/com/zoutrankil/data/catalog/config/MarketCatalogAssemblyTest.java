package com.zoutrankil.data.catalog.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.zoutrankil.data.catalog.domain.CatalogTag;
import com.zoutrankil.data.catalog.domain.DataCatalog.Column;
import com.zoutrankil.data.catalog.domain.DataCatalog.PhysicalObject;
import com.zoutrankil.data.catalog.domain.DataCatalog.Snapshot;
import com.zoutrankil.data.catalog.domain.DataObjectDescription;
import com.zoutrankil.data.catalog.port.DataCatalogMetadataPort;
import com.zoutrankil.data.catalog.port.DataCatalogProvider;
import com.zoutrankil.data.domain.DatasetDefinition.ObjectKind;
import com.zoutrankil.data.service.DatasetRegistry;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.ServiceLoader;

class MarketCatalogAssemblyTest {
    @Test
    void curatedDescriptionsHaveMultipleTagsAndExcludeRetiredObjects() {
        var all = new ArrayList<DataObjectDescription>();
        ServiceLoader.load(DataCatalogProvider.class)
                .forEach(provider -> all.addAll(provider.descriptions()));
        all.addAll(SharedDataDescriptions.all());
        assertTrue(all.stream().anyMatch(d -> d.objectName().equals("us_tycr")));
        assertFalse(all.isEmpty());
        assertEquals(
                all.size(), all.stream().map(DataObjectDescription::objectName).distinct().count());
        assertTrue(all.stream().allMatch(description -> description.tags().size() >= 2));
        assertTrue(
                all.stream()
                        .noneMatch(
                                description ->
                                        List.of("backtest_daily", "qmt_tick_data")
                                                .contains(description.objectName())));
        var backtest =
                all.stream()
                        .filter(description -> description.objectName().equals("mv_backtest_daily"))
                        .findFirst()
                        .orElseThrow();
        assertTrue(
                backtest.tags()
                        .containsAll(
                                List.of(CatalogTag.CN, CatalogTag.BACKTEST, CatalogTag.DERIVED)));
        assertTrue(backtest.notes().contains("关联表单独变化不触发"));
    }

    @Test
    void marketFieldDocumentationReachesTheSharedCatalogWithoutStartingClients() {
        var metadata = Mockito.mock(DataCatalogMetadataPort.class);
        var physical =
                new PhysicalObject(
                        "daily", ObjectKind.TABLE, "trade_date", null, null, null, null, null);
        Mockito.when(metadata.snapshot())
                .thenReturn(
                        new Snapshot(
                                "fixture",
                                Instant.parse("2026-10-19T00:00:00Z"),
                                List.of(physical)));
        Mockito.when(metadata.columns("daily"))
                .thenReturn(List.of(new Column("vol", "DOUBLE", true, "待补充", null)));
        var beans = new StaticListableBeanFactory();
        var service =
                new DataCatalogConfiguration()
                        .dataCatalogService(metadata, beans.getBeanProvider(DatasetRegistry.class));
        var column = service.detail("daily").columns().getFirst();
        assertEquals("手", column.sourceDocumentation().unit());
        assertEquals("vol", column.sourceDocumentation().sourceField());
        assertEquals("DOUBLE", column.storageType());
        assertTrue(column.meaning().contains("成交量"));
    }
}
