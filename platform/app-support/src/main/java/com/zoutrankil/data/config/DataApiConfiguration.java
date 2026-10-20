package com.zoutrankil.data.config;

import com.zoutrankil.data.catalog.config.DataCatalogConfiguration;
import com.zoutrankil.data.catalog.web.DataCatalogController;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/** 市场应用按需导入公共 API；这里不扫描市场、不启动同步，也不自动执行迁移。 */
@Configuration(proxyBeanMethods = false)
@Import({
    WebApiConfiguration.class,
    SchemaMigrationConfiguration.class,
    DataCatalogConfiguration.class,
    DataCatalogController.class
})
public class DataApiConfiguration {}
