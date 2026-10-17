package com.zoutrankil.data.catalog.config;

import com.zoutrankil.data.catalog.application.DataCatalogService;
import com.zoutrankil.data.catalog.domain.CatalogTag;
import com.zoutrankil.data.catalog.domain.DataCatalog;
import com.zoutrankil.data.catalog.domain.DataObjectDescription;
import com.zoutrankil.data.catalog.port.DataCatalogMetadataPort;
import com.zoutrankil.data.service.DatasetRegistry;
import com.zoutrankil.market.cn.catalog.CnDataDescriptions;
import com.zoutrankil.market.us.catalog.UsDataDescriptions;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DataCatalogConfigurationTest {
    @Test void appConfigurationAssemblesAllMarketsWithoutOpeningTheDatabase() {
        var source = mock(DataSource.class);
        var datasets = mock(DatasetRegistry.class);
        when(datasets.definitions()).thenReturn(List.of());
        try (var context = new AnnotationConfigApplicationContext()) {
            context.registerBean(DataSource.class, () -> source);
            context.registerBean(DatasetRegistry.class, () -> datasets);
            context.register(DataCatalogConfiguration.class);
            context.refresh();
            assertNotNull(context.getBean(DataCatalogService.class));
            assertNotNull(context.getBean(DataCatalogMetadataPort.class));
            verifyNoInteractions(source);
        }
    }
    @Test void curatedDescriptionsHaveMultipleTagsAndExcludeRetiredObjects() {
        var all = new ArrayList<DataObjectDescription>();
        all.addAll(CnDataDescriptions.all()); all.addAll(UsDataDescriptions.all()); all.addAll(SharedDataDescriptions.all());
        assertFalse(all.isEmpty());
        assertEquals(all.size(), all.stream().map(DataObjectDescription::objectName).distinct().count());
        assertTrue(all.stream().allMatch(description -> description.tags().size() >= 2));
        assertTrue(all.stream().noneMatch(description -> List.of("backtest_daily", "qmt_tick_data").contains(description.objectName())));
        var backtest = all.stream().filter(description -> description.objectName().equals("mv_backtest_daily")).findFirst().orElseThrow();
        assertTrue(backtest.tags().containsAll(List.of(CatalogTag.CN, CatalogTag.BACKTEST, CatalogTag.DERIVED)));
        assertTrue(backtest.notes().contains("关联表单独变化不触发"));
    }
}
