package com.zoutrankil.data.catalog.port;

import com.zoutrankil.data.catalog.domain.DataCatalog;

import java.util.List;

/** 只提供目录和字段元数据，不能通过目录查询执行任何数据维护。 */
public interface DataCatalogMetadataPort {
    DataCatalog.Snapshot snapshot();

    List<DataCatalog.Column> columns(String objectName);
}
