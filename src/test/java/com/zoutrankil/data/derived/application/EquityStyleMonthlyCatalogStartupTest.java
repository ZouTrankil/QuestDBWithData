package com.zoutrankil.data.derived.application;

import com.zoutrankil.data.service.*;

import com.zoutrankil.data.index.application.IndexMonthlySyncJobOwner;

import com.zoutrankil.data.QuestDataApplication;
import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.derived.storage.EquityStyleMonthlyReadRepository;
import java.nio.file.*;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.*;
import org.springframework.core.env.MapPropertySource;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class EquityStyleMonthlyCatalogStartupTest {
    @TempDir Path temporary;
    @Test void actualCatalogAdmitsTypedMonthlyDatasetAndCanonicalJobWithoutIo() throws Exception {
        Path ledger=temporary.resolve("uncreated.sqlite3");
        var application=new SpringApplication(QuestDataApplication.class,EtfMarketOverviewCacheCatalogStartupTest.NoConnectionConfiguration.class);
        application.setWebApplicationType(WebApplicationType.NONE);application.setAdditionalProfiles("d101-catalog-test");
        application.addInitializers(context->context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("d103-catalog-test",Map.of(
                "app.sync.ledger-path",ledger.toString(),"app.sync.etf-market-overview-cache.expected-pid","0",
                "spring.sql.init.mode","never","spring.flyway.enabled","false","spring.main.banner-mode","off"))));
        try(var context=application.run("show-sync-job-definitions")) {
            var datasets=context.getBean(DatasetRegistry.class);var jobs=context.getBean(SyncJobRegistry.class);
            assertEquals(EquityStyleMonthlyDataset.DEFINITION,datasets.require("equity_style_monthly").definition());
            assertNotNull(context.getBean(EquityStyleMonthlyReadRepository.class));
            assertEquals(EquityStyleMonthlyJobService.definition(),jobs.require("data.equity_style_monthly",1));
            assertEquals(1,jobs.definitions().stream().filter(job->job.datasetId().equals("equity_style_monthly")).count());
            assertNotNull(jobs.require(IndexMonthlySyncJobOwner.DEFINITION.jobId(),IndexMonthlySyncJobOwner.DEFINITION.version()));
            var source=context.getBean("d101NoConnectionDataSource",DataSource.class);
            verify(source,never()).getConnection();verify(source,never()).getConnection(anyString(),anyString());
            Path result=temporary.resolve("java-catalog-startup-final-20261006.json");
            Files.createDirectories(result.getParent());Files.writeString(result,JobDefinitionJson.mapper().writerWithDefaultPrettyPrinter().writeValueAsString(Map.of(
                    "task_id","D103","dataset_count",datasets.definitions().size(),"job_count",jobs.definitions().size(),
                    "definition",EquityStyleMonthlyDataset.DEFINITION,"job",EquityStyleMonthlyJobService.definition(),
                    "database_connections",0,"ledger_created",false)),StandardOpenOption.CREATE_NEW);
        }
        assertFalse(Files.exists(ledger));assertFalse(Files.exists(Path.of(ledger+"-wal")));
    }
}
