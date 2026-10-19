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
}
