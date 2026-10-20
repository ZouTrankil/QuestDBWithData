package com.zoutrankil.data.catalog.config;

import com.zoutrankil.data.catalog.application.DataCatalogService;
import com.zoutrankil.data.catalog.domain.DataCatalog;
import com.zoutrankil.data.catalog.domain.DataObjectDescription;
import com.zoutrankil.data.catalog.port.DataCatalogMetadataPort;
import com.zoutrankil.data.catalog.port.DataCatalogProvider;
import com.zoutrankil.data.catalog.storage.QuestDbDataCatalogMetadata;
import com.zoutrankil.data.service.DatasetRegistry;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;

import javax.sql.DataSource;

/** 公共目录只装入当前 classpath 的市场说明，不依赖任何市场实现。 */
@Configuration(proxyBeanMethods = false)
public class DataCatalogConfiguration {
    @Bean
    DataCatalogMetadataPort dataCatalogMetadata(
            DataSource source, @Value("${app.data-catalog.query-timeout-seconds:10}") int timeout) {
        return new QuestDbDataCatalogMetadata(source, timeout, Clock.systemUTC());
    }

    @Bean
    DataCatalogService dataCatalogService(
            DataCatalogMetadataPort metadata, ObjectProvider<DatasetRegistry> datasets) {
        var descriptions = new ArrayList<DataObjectDescription>();
        var columns = new HashMap<String, Map<String, DataCatalog.SourceField>>();
        // SPI 只读取说明；缺席的市场不创建客户端或启动应用。
        ServiceLoader.load(DataCatalogProvider.class)
                .forEach(
                        provider -> {
                            descriptions.addAll(provider.descriptions());
                            provider.columnDocumentation()
                                    .forEach(
                                            (name, fields) -> {
                                                if (columns.putIfAbsent(name, fields) != null)
                                                    throw new IllegalArgumentException(
                                                            "重复的字段来源说明：" + name);
                                            });
                        });
        descriptions.addAll(SharedDataDescriptions.all());
        var registry = datasets.getIfAvailable();
        return new DataCatalogService(
                metadata,
                descriptions,
                registry == null ? List.of() : registry.definitions(),
                columns);
    }
}
