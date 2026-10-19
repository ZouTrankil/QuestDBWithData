package com.zoutrankil.market.cn.catalog;

import com.zoutrankil.data.catalog.domain.DataObjectDescription;
import com.zoutrankil.data.catalog.port.DataCatalogProvider;
import java.util.List;
import java.util.Map;
import com.zoutrankil.data.catalog.domain.DataCatalog;

/** 仅登记本市场说明，不启动采集、策略或 L2。 */
public final class CnDataCatalogProvider implements DataCatalogProvider {
    @Override public List<DataObjectDescription> descriptions() { return CnDataDescriptions.all(); }
    @Override public Map<String, Map<String, DataCatalog.SourceField>> columnDocumentation() {
        return CnColumnDocumentation.all();
    }
}
