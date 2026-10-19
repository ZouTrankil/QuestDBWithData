package com.zoutrankil.data.catalog.port;

import com.zoutrankil.data.catalog.domain.DataObjectDescription;
import java.util.List;

/** 市场声明自己的目录说明；公共 API 按实际装入的模块发现，不猜测可用数据。 */
public interface DataCatalogProvider {
    List<DataObjectDescription> descriptions();
}
