package com.zoutrankil.data.etf.application;

import com.zoutrankil.data.QuestDataApplication;
import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.etf.port.EtfTarget;
import com.zoutrankil.data.etf.port.EtfWriteTarget;
import com.zoutrankil.data.etf.storage.*;
import com.zoutrankil.data.service.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.MapPropertySource;

import javax.sql.DataSource;
import java.nio.file.*;
import java.time.LocalDate;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class EtfAdditionalApplicationWiringTest {
    @TempDir Path temp;

    private enum Family {
        BASIC(EtfBasicSyncJobOwner.DEFINITION, EtfBasicSyncJobOwner.class, EtfBasicReadRepository.class,
                QuestDbEtfBasicTarget.class, "java_d013_etf_basic_wiring"),
        DAILY(EtfDailySyncJobOwner.DEFINITION, EtfDailySyncJobOwner.class, EtfDailyReadRepository.class,
                QuestDbEtfDailyTarget.class, "java_d014_etf_daily_wiring"),
        ADJ(EtfAdjSyncJobOwner.DEFINITION, EtfAdjSyncJobOwner.class, EtfAdjReadRepository.class,
                QuestDbEtfAdjTarget.class, "java_d015_etf_adj_wiring"),
        SHARE(EtfShareSyncJobOwner.DEFINITION, EtfShareSyncJobOwner.class, EtfShareReadRepository.class,
                QuestDbEtfShareTarget.class, "java_d016_etf_share_wiring"),
        FACTOR(EtfFactorSyncJobOwner.DEFINITION, EtfFactorSyncJobOwner.class, EtfFactorReadRepository.class,
                QuestDbEtfFactorTarget.class, "java_d017_etf_factor_wiring"),
        PORTFOLIO(EtfPortfolioSyncJobOwner.DEFINITION, EtfPortfolioSyncJobOwner.class, EtfPortfolioReadRepository.class,
                QuestDbEtfPortfolioTarget.class, "java_d018_etf_portfolio_wiring");
        final SyncJobDefinition definition;
        final Class<? extends SyncJobOwner> owner;
        final Class<? extends DatasetImplementation> reader;
        final Class<?> target;
        final String table;
        Family(SyncJobDefinition definition, Class<? extends SyncJobOwner> owner,
               Class<? extends DatasetImplementation> reader, Class<?> target, String table) {
            this.definition=definition; this.owner=owner; this.reader=reader; this.target=target; this.table=table;
        }
        String serviceTable(ApplicationContext context) {
            return switch (this) {
                case BASIC -> context.getBean(EtfBasicJobService.class).tableName();
                case DAILY -> context.getBean(EtfDailyJobService.class).tableName();
                case ADJ -> context.getBean(EtfAdjJobService.class).tableName();
                case SHARE -> context.getBean(EtfShareJobService.class).tableName();
                case FACTOR -> context.getBean(EtfFactorJobService.class).tableName();
                case PORTFOLIO -> context.getBean(EtfPortfolioJobService.class).tableName();
            };
        }
    }

    @Test void allSixEtfFamiliesHaveOneTypedTargetOwnerAndReaderWithOfflineFrozenPlans() throws Exception {
        var dataSource=mock(DataSource.class);
        Path ledger=temp.resolve("must-not-exist.sqlite");
        var properties=new LinkedHashMap<String,Object>();
        properties.put("app.sync.ledger-path",ledger.toString());
        properties.put("spring.main.banner-mode","off");
        for(var family:Family.values())
            properties.put("app.sync."+family.definition.datasetId().replace('_','-')+"-table",family.table);
        var application=new SpringApplication(QuestDataApplication.class);
        application.setWebApplicationType(WebApplicationType.NONE);
        application.addInitializers(context -> {
            context.getBeanFactory().registerSingleton("dataSource",dataSource);
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("all-etf-wiring",properties));
        });
        try(var context=application.run("plan-sync-job","--job","data.etf_basic","--version","1",
                "--mode","SNAPSHOT","--logical-date","2026-09-28","--parameters",
                "{\"targetId\":\"static-v2-frozen\",\"observedAt\":\"2026-09-28T00:00:00Z\"}")) {
            var owners=context.getBeansOfType(SyncJobOwner.class).values();
            var targets=context.getBeansOfType(EtfWriteTarget.class).values();
            assertEquals(6,targets.size());
            assertEquals(5,context.getBeansOfType(EtfTarget.class).size(),"Whole-directory Basic has no calendar range port");
            var jobs=context.getBean(SyncJobRegistry.class);
            var datasets=context.getBean(DatasetRegistry.class);
            var bindings=context.getBean(ReadBindingCatalog.class).bind(datasets);
            assertEquals(56,bindings.size());
            assertEquals(56,datasets.definitions().stream().filter(d -> d.capabilities().contains(DatasetDefinition.Capability.READ)).count());
            assertEquals(jobs.definitions().size(),jobs.definitions().stream().map(SyncJobDefinition::jobId).distinct().count());
            for(var family:Family.values()) {
                String dataset=family.definition.datasetId();
                var familyOwners=owners.stream().filter(owner -> owner.datasetId().equals(dataset)).toList();
                assertEquals(1,familyOwners.size(),dataset);
                assertEquals(family.owner,familyOwners.getFirst().getClass());
                assertSame(family.definition,jobs.require(family.definition.jobId(),family==Family.SHARE?3:1));
                var matchingTargets=targets.stream().filter(target -> target.tableName().equals(family.table)).toList();
                assertEquals(1,matchingTargets.size(),dataset);
                assertEquals(family.target,matchingTargets.getFirst().getClass());
                assertEquals(family.table,family.serviceTable(context),"Generic injection must select the family's target");
                assertEquals(1,context.getBeansOfType(family.reader).size());
                assertEquals(family.table,context.getBean(family.reader).definition().objectName());
                var binding=bindings.stream().filter(value -> value.definition().datasetId().equals(dataset)).findFirst().orElseThrow();
                assertEquals(family.table,binding.definition().objectName());
                assertEquals(DatasetValues.class,binding.rowType());
                assertNull(binding.sourceVersion().get());
                var parameters=new LinkedHashMap<String,Object>();
                parameters.put("targetId","static-v2-frozen");
                if(family==Family.BASIC || family==Family.SHARE || family==Family.PORTFOLIO)
                    parameters.put("observedAt","2026-09-28T00:00:00Z");
                if(family!=Family.BASIC)
                    parameters.put(family==Family.PORTFOLIO?"ann_dates":"trade_dates","20260928");
                var date=LocalDate.of(2026,9,28);
                var request=jobs.prepare(family.definition.jobId(),family.definition.version(),
                        family==Family.BASIC?SyncJobDefinition.Mode.SNAPSHOT:SyncJobDefinition.Mode.BACKFILL,
                        parameters,family==Family.BASIC?null:date,family==Family.BASIC?null:date,date);
                assertSame(family.definition,request.definition());
                assertEquals(parameters,request.parameters());
            }
            assertFalse(context.getBeanFactory().containsSingleton("questDbClient"));
            verify(dataSource,never()).getConnection();
            verify(dataSource,never()).getConnection(anyString(),anyString());
            assertFalse(Files.exists(ledger));
        }
        assertFalse(Files.exists(ledger));
    }
}
