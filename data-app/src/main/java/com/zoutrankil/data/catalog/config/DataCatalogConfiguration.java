package com.zoutrankil.data.catalog.config;

import com.zoutrankil.data.catalog.application.DataCatalogService;
import com.zoutrankil.data.catalog.port.DataCatalogMetadataPort;
import com.zoutrankil.data.catalog.storage.QuestDbDataCatalogMetadata;
import com.zoutrankil.data.catalog.domain.DataObjectDescription;
import com.zoutrankil.data.service.DatasetRegistry;
import com.zoutrankil.market.cn.catalog.CnDataDescriptions;
import com.zoutrankil.market.us.catalog.UsDataDescriptions;
import java.time.Clock;
import java.util.ArrayList;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** App 组装各市场说明和通用查询组件，市场之间不互相依赖。 */
@Configuration(proxyBeanMethods=false)
public class DataCatalogConfiguration {
    @Bean DataCatalogMetadataPort dataCatalogMetadata(DataSource source,
            @Value("${app.data-catalog.query-timeout-seconds:10}") int timeout) {
        return new QuestDbDataCatalogMetadata(source, timeout, Clock.systemUTC());
    }
    @Bean DataCatalogService dataCatalogService(DataCatalogMetadataPort metadata, DatasetRegistry datasets) {
        var descriptions = new ArrayList<DataObjectDescription>();
        descriptions.addAll(CnDataDescriptions.all());
        descriptions.addAll(UsDataDescriptions.all());
        descriptions.addAll(SharedDataDescriptions.all());
        return new DataCatalogService(metadata, descriptions, datasets.definitions());
    }
}
