package com.zoutrankil.data.catalog.config;

import com.zoutrankil.data.catalog.domain.CatalogTag;
import com.zoutrankil.data.catalog.domain.DataObjectDescription;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import com.zoutrankil.data.catalog.port.DataCatalogProvider;
import java.util.ServiceLoader;

class MarketCatalogAssemblyTest {
    @Test void curatedDescriptionsHaveMultipleTagsAndExcludeRetiredObjects() {
        var all = new ArrayList<DataObjectDescription>();
        ServiceLoader.load(DataCatalogProvider.class).forEach(provider -> all.addAll(provider.descriptions()));
        all.addAll(SharedDataDescriptions.all());
        assertTrue(all.stream().anyMatch(d -> d.objectName().equals("us_tycr")));
        assertFalse(all.isEmpty());
        assertEquals(all.size(), all.stream().map(DataObjectDescription::objectName).distinct().count());
        assertTrue(all.stream().allMatch(description -> description.tags().size() >= 2));
        assertTrue(all.stream().noneMatch(description -> List.of("backtest_daily", "qmt_tick_data").contains(description.objectName())));
        var backtest = all.stream().filter(description -> description.objectName().equals("mv_backtest_daily")).findFirst().orElseThrow();
        assertTrue(backtest.tags().containsAll(List.of(CatalogTag.CN, CatalogTag.BACKTEST, CatalogTag.DERIVED)));
        assertTrue(backtest.notes().contains("关联表单独变化不触发"));
    }
    @Test void marketFieldDocumentationReachesTheSharedCatalogWithoutStartingClients() {
        var metadata = org.mockito.Mockito.mock(com.zoutrankil.data.catalog.port.DataCatalogMetadataPort.class);
        var physical = new com.zoutrankil.data.catalog.domain.DataCatalog.PhysicalObject("daily",
                com.zoutrankil.data.domain.DatasetDefinition.ObjectKind.TABLE, "trade_date", null, null, null, null, null);
        org.mockito.Mockito.when(metadata.snapshot()).thenReturn(new com.zoutrankil.data.catalog.domain.DataCatalog.Snapshot(
                "fixture", java.time.Instant.parse("2026-10-19T00:00:00Z"), List.of(physical)));
        org.mockito.Mockito.when(metadata.columns("daily")).thenReturn(List.of(
                new com.zoutrankil.data.catalog.domain.DataCatalog.Column("vol", "DOUBLE", true, "待补充", null)));
        var beans = new org.springframework.beans.factory.support.StaticListableBeanFactory();
        var service = new DataCatalogConfiguration().dataCatalogService(metadata,
                beans.getBeanProvider(com.zoutrankil.data.service.DatasetRegistry.class));
        var column = service.detail("daily").columns().getFirst();
        assertEquals("手", column.sourceDocumentation().unit());
        assertEquals("vol", column.sourceDocumentation().sourceField());
        assertEquals("DOUBLE", column.storageType());
        assertTrue(column.meaning().contains("成交量"));
    }

}
