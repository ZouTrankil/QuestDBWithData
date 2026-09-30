package com.zoutrankil.data.cli;

import com.zoutrankil.data.QuestDataApplication;
import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.repository.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.*;
import org.springframework.jdbc.core.JdbcTemplate;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="QUESTDB_WRITE_LIVE",matches="1")
@EnabledIfEnvironmentVariable(named="TUSHARE_PAGE_LIVE",matches="1")
class IndexMembershipCliLiveTest {
    @Test void registeredCliRunsTwoNonemptyIndustriesAndResumesFrozenBatch() throws Exception {
        String nonce=UUID.randomUUID().toString().replace("-","");Path folder=Path.of("artifacts/java-migration/D005","cli-"+nonce);
        Files.createDirectories(folder);Path ledgerPath=folder.resolve("ledger.sqlite");String table="java_d005_cli_"+nonce;
        var app=new SpringApplication(QuestDataApplication.class);app.setWebApplicationType(WebApplicationType.NONE);
        app.addInitializers(c->c.getEnvironment().getPropertySources().addFirst(new org.springframework.core.env.MapPropertySource(
                "membership-cli-test",Map.of("app.sync.ledger-path",ledgerPath.toString(),"app.sync.index-member-table",table))));
        var args=new ArrayList<>(List.of("plan-index-member-job","--classification-receipt",IndexMembershipPlanningStartupTest.RECEIPT,
                "--classification-sha256",IndexMembershipPlanningStartupTest.SHA,"--industries","801011.SI,801207.SI",
                "--selection","CURRENT","--logical-date","2026-09-29"));
        try(var context=app.run(args.toArray(String[]::new))) {
            assertFalse(Files.exists(ledgerPath));var jdbc=context.getBean(JdbcTemplate.class);var json=JobDefinitionJson.mapper();
            jdbc.execute("CREATE TABLE "+table+" ("+String.join(",",IndexMembershipDataset.DEFINITION.columns().stream()
                    .map(c->"\""+c.storageName()+"\" "+c.storageType().name()).toList())+") TIMESTAMP(update_time) PARTITION BY YEAR WAL");
            long deadline=System.nanoTime()+Duration.ofSeconds(30).toNanos();
            while(!QuestDbWriteChecks.walSettled(jdbc,table)) {
                if(System.nanoTime()>deadline) throw new IllegalStateException("CLI fixture WAL did not settle");Thread.sleep(50);
            }
            var cli=context.getBean(CommandLineRunner.class);args.set(0,"run-index-member-job");
            cli.run(new DefaultApplicationArguments(args.toArray(String[]::new)));
            var ledger=SyncRunLedger.openReadOnly(ledgerPath);
            var first=ledger.history("data.index_member",null,20).stream().filter(r->r.parentRunId()==null).findFirst().orElseThrow();
            assertEquals(SyncRunState.VERIFIED,first.state());
            var actual=new IndexMembershipStorage(jdbc,table).snapshot();assertEquals(6,actual.rows().size());
            assertEquals(4,actual.rows().stream().filter(r->r.indexCode().equals("801011.SI")).count());
            assertEquals(2,actual.rows().stream().filter(r->r.indexCode().equals("801207.SI")).count());
            args.addAll(List.of("--resume-from",first.id()));cli.run(new DefaultApplicationArguments(args.toArray(String[]::new)));
            var resumed=ledger.history("data.index_member",null,20).stream().filter(r->first.id().equals(r.parentRunId())
                    && Files.exists(folder.resolve("sync-evidence").resolve(r.id()).resolve("batch-plan.json"))).findFirst().orElseThrow();
            assertEquals(SyncRunState.VERIFIED,resumed.state());assertEquals(actual,new IndexMembershipStorage(jdbc,table).snapshot());
            var proof=json.readTree(folder.resolve("sync-evidence").resolve(resumed.id()).resolve("batch-completion.json").toFile());
            assertEquals(2,proof.path("members").size());proof.path("members").forEach(m->assertTrue(m.path("reused").asBoolean()));
            cli.run(new DefaultApplicationArguments("show-sync-run","--run",resumed.id(),"--ledger",ledgerPath.toString()));
            json.writerWithDefaultPrettyPrinter().writeValue(folder.resolve("cli-readback.json").toFile(),Map.of(
                    "first",first,"resumed",resumed,"actual",actual,"resume",proof,"offlinePlanCreatedLedger",false));
            var original=json.readTree(folder.resolve("sync-evidence").resolve(first.id()).resolve("batch-completion.json").toFile());
            var journal=new ReferencePublicationJournal(ledgerPath,"index_member");
            for(var member:original.path("members")) {
                var publication=journal.forRun(member.path("childRunId").asText());jdbc.execute("DROP TABLE "+publication.intent().backup());
            }
            jdbc.execute("DROP TABLE "+table);
        }
    }
}
