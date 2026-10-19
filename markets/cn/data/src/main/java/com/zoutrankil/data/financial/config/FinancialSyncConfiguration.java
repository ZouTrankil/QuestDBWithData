package com.zoutrankil.data.financial.config;

import com.zoutrankil.data.domain.JobDefinitionJson;
import com.zoutrankil.data.financial.application.FinancialSyncOwner;
import com.zoutrankil.data.financial.domain.FinancialContract;
import com.zoutrankil.data.financial.storage.FinancialWritePort;
import com.zoutrankil.data.service.TusharePageService;
import io.questdb.client.QuestDB;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.context.annotation.*;
import org.springframework.beans.factory.annotation.Value;
import java.nio.file.Path;
import java.util.*;

@Configuration
public class FinancialSyncConfiguration {
    public static FinancialContract contract(String dataset) {
        try(var stream=FinancialSyncConfiguration.class.getResourceAsStream("/financial/schemas.json")) {
            var fields=JobDefinitionJson.mapper().readTree(Objects.requireNonNull(stream)).required(dataset);
            return new FinancialContract(dataset,JobDefinitionJson.mapper().convertValue(fields,new com.fasterxml.jackson.core.type.TypeReference<List<FinancialContract.Field>>(){}));
        }catch(Exception e){throw new IllegalStateException("财务列契约不可读",e);}
    }
    private FinancialSyncOwner owner(String name,TusharePageService pages,JdbcTemplate jdbc,QuestDB questdb,String ledger){var contract=contract(name);return new FinancialSyncOwner(contract,new FinancialWritePort(contract,jdbc,questdb),pages,Path.of(ledger));}
    @Bean FinancialSyncOwner cashflowSync(TusharePageService p,JdbcTemplate j,@Lazy QuestDB q,@Value("${app.sync.ledger-path:var/sync-ledger.sqlite3}")String l){return owner("cashflow",p,j,q,l);}
    @Bean FinancialSyncOwner incomeSync(TusharePageService p,JdbcTemplate j,@Lazy QuestDB q,@Value("${app.sync.ledger-path:var/sync-ledger.sqlite3}")String l){return owner("income",p,j,q,l);}
    @Bean FinancialSyncOwner expressSync(TusharePageService p,JdbcTemplate j,@Lazy QuestDB q,@Value("${app.sync.ledger-path:var/sync-ledger.sqlite3}")String l){return owner("express",p,j,q,l);}
    @Bean FinancialSyncOwner forecastSync(TusharePageService p,JdbcTemplate j,@Lazy QuestDB q,@Value("${app.sync.ledger-path:var/sync-ledger.sqlite3}")String l){return owner("forecast",p,j,q,l);}
    @Bean FinancialSyncOwner indicatorSync(TusharePageService p,JdbcTemplate j,@Lazy QuestDB q,@Value("${app.sync.ledger-path:var/sync-ledger.sqlite3}")String l){return owner("fina_indicator",p,j,q,l);}
    @Bean com.zoutrankil.data.service.ReadBindingCatalog.Registration<com.zoutrankil.data.domain.DatasetValues> cashflowRead(){return com.zoutrankil.data.service.ReadBindingCatalog.generic("cashflow",1);}
    @Bean com.zoutrankil.data.service.ReadBindingCatalog.Registration<com.zoutrankil.data.domain.DatasetValues> incomeRead(){return com.zoutrankil.data.service.ReadBindingCatalog.generic("income",1);}
    @Bean com.zoutrankil.data.service.ReadBindingCatalog.Registration<com.zoutrankil.data.domain.DatasetValues> expressRead(){return com.zoutrankil.data.service.ReadBindingCatalog.generic("express",1);}
    @Bean com.zoutrankil.data.service.ReadBindingCatalog.Registration<com.zoutrankil.data.domain.DatasetValues> forecastRead(){return com.zoutrankil.data.service.ReadBindingCatalog.generic("forecast",1);}
    @Bean com.zoutrankil.data.service.ReadBindingCatalog.Registration<com.zoutrankil.data.domain.DatasetValues> indicatorRead(){return com.zoutrankil.data.service.ReadBindingCatalog.generic("fina_indicator",1);}
}
