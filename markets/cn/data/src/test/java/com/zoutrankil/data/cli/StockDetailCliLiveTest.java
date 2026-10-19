package com.zoutrankil.data.cli;

import com.zoutrankil.data.QuestDataApplication;
import com.zoutrankil.data.stock.application.StockDetailInfoJobService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.*;
import org.springframework.core.env.MapPropertySource;

import java.nio.file.*;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="QUESTDB_WRITE_LIVE",matches="1")
class StockDetailCliLiveTest {
    @TempDir Path temp;

    @Test void registeredCliPlansAgainstActualPhysicalTargetWithoutSourceOrLedgerWrite() {
        Path ledger=temp.resolve("stock-detail-plan.sqlite");
        var app=new SpringApplication(QuestDataApplication.class);
        app.setWebApplicationType(WebApplicationType.NONE);
        app.addInitializers(context->context.getEnvironment().getPropertySources().addFirst(
                new MapPropertySource("d002-cli-plan",Map.of("app.sync.ledger-path",ledger.toString(),
                        "spring.main.banner-mode","off"))));
        try(var context=app.run("plan-stock-detail-job","--logical-date","2026-09-29",
                "--codes","000001.SZ")) {
            assertTrue(context.getBean(StockDetailInfoJobService.class).targetId().matches("static-v2-[0-9a-f]{64}"));
            assertFalse(Files.exists(ledger));
        }
        assertFalse(Files.exists(ledger));
    }
}
