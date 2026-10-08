package com.zoutrankil.data.cli;

import com.zoutrankil.data.index.application.IndexMembershipJobPlan;
import com.zoutrankil.data.index.application.IndexMembershipJobService;
import com.zoutrankil.data.index.application.IndexMembershipSource;

import com.zoutrankil.data.QuestDataApplication;
import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.service.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.*;
import java.nio.file.*;
import java.time.LocalDate;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class IndexMembershipPlanningStartupTest {
    @TempDir Path temp;
    static final String RECEIPT="artifacts/java-migration/D005/discovery-8303c5c1-b6ec-4472-9a0d-0d9e2486ea66/classification-8b4429b7-1613-49cd-9692-e6ce0422602a.json";
    static final String SHA="00936922ec3a79d7105e7cae37d28d955ebc41da1c67de9c45bc87024817d668";
    @org.junit.jupiter.api.Tag("retained-parity")
    @org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named="RUN_RETAINED_PARITY", matches="1")
    @Test void offlinePlanRegistersOwnerAndRejectsExecutionOnlyOrMalformedOptions() throws Exception {
        assertTrue(Files.isRegularFile(Path.of("artifacts/java-migration/D005/discovery-8303c5c1-b6ec-4472-9a0d-0d9e2486ea66/classification-8b4429b7-1613-49cd-9692-e6ce0422602a.json")), "Retained parity explicitly requested but original input is missing: artifacts/java-migration/D005/discovery-8303c5c1-b6ec-4472-9a0d-0d9e2486ea66/classification-8b4429b7-1613-49cd-9692-e6ce0422602a.json");
        Path ledger=temp.resolve("not-created.sqlite");var app=new SpringApplication(QuestDataApplication.class);
        app.setWebApplicationType(WebApplicationType.NONE);
        app.addInitializers(c->c.getEnvironment().getPropertySources().addFirst(new org.springframework.core.env.MapPropertySource(
                "membership-plan-test",Map.of("app.sync.ledger-path",ledger.toString(),"spring.datasource.url","jdbc:postgresql://127.0.0.1:1/no_database"))));
        var arguments=List.of("plan-index-member-job","--classification-receipt",RECEIPT,"--classification-sha256",SHA,
                "--industries","801011.SI,801207.SI","--selection","CURRENT","--logical-date","2026-09-29");
        try(var context=app.run(arguments.toArray(String[]::new))) {
            assertFalse(Files.exists(ledger));
            assertEquals(IndexMembershipJobPlan.definition(),context.getBean(SyncJobRegistry.class).require("data.index_member",1));
            var owner=context.getBean(IndexMembershipJobService.class);
            var request=owner.planFromReceipt(Path.of(RECEIPT),SHA,List.of("801011.SI","801207.SI"),IndexMembershipSource.Selection.CURRENT,LocalDate.of(2026,9,29));
            assertEquals(SyncJobDefinition.Mode.INCREMENTAL,request.mode());assertEquals(2,IndexMembershipJobPlan.scopes(request).size());
            var cli=context.getBean(CommandLineRunner.class);
            var resume=new ArrayList<>(arguments);resume.addAll(List.of("--resume-from","old"));
            assertThrows(IllegalArgumentException.class,()->cli.run(new DefaultApplicationArguments(resume.toArray(String[]::new))));
            assertThrows(IllegalArgumentException.class,()->cli.run(new DefaultApplicationArguments("finish-index-member-child","--run","absent","--writer-stopped","false")));
            var missing=new ArrayList<>(arguments);missing.remove(5);missing.remove(5);
            assertThrows(IllegalArgumentException.class,()->cli.run(new DefaultApplicationArguments(missing.toArray(String[]::new))));
            assertThrows(IllegalArgumentException.class,()->owner.planFromReceipt(Path.of(RECEIPT),"0".repeat(64),List.of("801011.SI"),IndexMembershipSource.Selection.CURRENT,request.logicalDate()));
            assertFalse(Files.exists(ledger));assertFalse(Files.exists(temp.resolve("sync-evidence")));
        }
    }
}
