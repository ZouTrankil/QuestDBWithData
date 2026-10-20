package com.zoutrankil.data.catalog.port;

import com.zoutrankil.data.catalog.domain.DataCatalog;
import com.zoutrankil.data.catalog.domain.DataObjectDescription;

import java.util.List;
import java.util.Map;

/** 市场声明自己的目录说明；公共 API 按实际装入的模块发现，不猜测可用数据。 */
public interface DataCatalogProvider {
    List<DataObjectDescription> descriptions();

    /** 可选的逐列来源说明，以真实物理对象和存储列名匹配，不猜别名或市场。 */
    default Map<String, Map<String, DataCatalog.SourceField>> columnDocumentation() {
        return Map.of();
    }
}
