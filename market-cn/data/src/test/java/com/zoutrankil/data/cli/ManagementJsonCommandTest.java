package com.zoutrankil.data.cli;

import com.zoutrankil.data.index.application.IndexCatalogJobService;

import com.zoutrankil.data.stock.application.StockBasicJobService;
import com.zoutrankil.data.stock.application.StockBasicSyncAdapter;
import com.zoutrankil.data.stock.application.StockBasicSyncService;
import com.zoutrankil.data.stock.application.StockDetailInfoJobService;
import com.zoutrankil.data.calendar.application.ExchangeCalendarJobService;

import com.fasterxml.jackson.databind.*;
import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.repository.SyncRunLedger;
import com.zoutrankil.data.service.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.DefaultApplicationArguments;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ManagementJsonCommandTest {
    @TempDir Path temp;
    private final StockBasicJobService runner = mock(StockBasicJobService.class);
    private final StockBasicSyncService legacy = mock(StockBasicSyncService.class);
    private final StockBasicGroupService group = mock(StockBasicGroupService.class);
    private CommandLineRunner cli() {
        return cli(new LedgerManagementService(LedgerManagementService.DEFAULT_LEDGER_PATH));
    }
    private CommandLineRunner cli(LedgerManagementService ledgerManagement) {
        var d = StockBasicSyncAdapter.definition(true);
        var datasets = new DatasetRegistry(List.of(() -> StockBasicDataset.DEFINITION));
        var jobs = new SyncJobRegistry(List.of(d), datasets, Map.of(d.datasetId(),d.supportedModes()),
                new SyncJobRegistry.Policies(Set.of(d.ratePolicyRef()),Set.of(d.slicePolicyRef()),Set.of(d.verificationPolicyRef())));
        return new CommandLineRunner(legacy,datasets,jobs,runner,group,mock(ReadGroupReader.class),
                mock(StockBasicWriteGroupService.class),mock(StockBasicScheduleService.class),
                mock(ExchangeCalendarJobService.class),mock(StockDetailInfoJobService.class),
                mock(IndexCatalogJobService.class),ledgerManagement);
    }
    private JsonNode output(CommandLineRunner cli, String... args) throws Exception {
        var buffer = new ByteArrayOutputStream();
        var original = System.out;
        try (var stream = new PrintStream(buffer,true,StandardCharsets.UTF_8)) {
            System.setOut(stream);
            cli.run(new DefaultApplicationArguments(args));
        } finally { System.setOut(original); }
        return new ObjectMapper().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .readTree(buffer.toString(StandardCharsets.UTF_8));
    }
    @Test void planValidateListAndShowProduceParseableNonExecutingOutput() throws Exception {
        var cli = cli();
        for (String command : List.of("plan-sync-job","validate-sync-job")) {
            var result = output(cli,command,"--job","data.stock_basic","--version","2",
                    "--logical-date","2026-09-29","--parameters","{\"codes\":[\"000001.SZ\"]}");
            assertFalse(result.get("executed").booleanValue());
            assertFalse(result.get("dataVerified").booleanValue());
            assertEquals(command.startsWith("plan") ? "PLANNED" : "VALIDATED",result.get("status").textValue());
            assertEquals("2026-09-29",result.at("/request/logicalDate").textValue());
            assertEquals("000001.SZ",result.at("/request/parameters/codes/0").textValue());
        }
        assertEquals("data.stock_basic",output(cli,"list-sync-jobs").get(0).get("jobId").textValue());
        assertEquals(2,output(cli,"show-sync-job","--job","data.stock_basic","--version","2").get("version").intValue());
        assertThrows(IllegalArgumentException.class,()->output(cli,"show-sync-job","--job","data.stock_basic","--version","99"));
        assertThrows(IllegalArgumentException.class,()->output(cli,"list-sync-jobs","--run","true"));
        verifyNoInteractions(runner,legacy,group);
    }
    @Test void cancelPersistsRequestWhileStatusDoesNotPretendWriterStopped() throws Exception {
        Path path = temp.resolve("cancel.sqlite");
        var ledger = new SyncRunLedger(path);
        ledger.createRun(new SyncRunLedger.Run("run-cancel",null,"data.stock_basic",2,"2026-09-29","target","{}"));
        var before = ledger.get("run-cancel").state();
        var command = cli();
        var result = output(command,"cancel-sync-run","--ledger",path.toString(),"--run","run-cancel");
        assertTrue(result.get("cancellationRequested").booleanValue());
        assertEquals(before.name(),result.get("state").textValue());
        var reopened = SyncRunLedger.openReadOnly(path);
        assertTrue(reopened.cancellationRequested("run-cancel"));
        assertEquals(before,reopened.get("run-cancel").state());
        var status = output(command,"show-sync-run","--ledger",path.toString(),"--run","run-cancel");
        assertEquals("run-cancel",status.at("/run/id").textValue());
        assertEquals(before.name(),status.at("/entries/0/state").textValue());
        verifyNoInteractions(runner,legacy,group);
    }
    @Test void historyJsonRetainsUnexecutedStateAndReadsExistingLedgerOnly() throws Exception {
        Path path = temp.resolve("history.sqlite");
        var ledger = new SyncRunLedger(path);
        ledger.createRun(new SyncRunLedger.Run("run-one",null,"data.stock_basic",2,"2026-09-29","target","{}"));
        var result = output(cli(),"show-sync-history","--ledger",path.toString(),"--limit","1");
        assertTrue(result.get("readOnly").booleanValue());
        assertEquals(ledger.get("run-one").state().name(),result.at("/runs/0/state").textValue());
        assertEquals("run-one",result.get("nextAfter").textValue());
        assertFalse(result.at("/runs/0").has("frozenJson"));
        verifyNoInteractions(runner,legacy,group);
    }
    @Test void allManagementCommandsUseConfiguredLedgerAndAcceptAnExplicitOverride() throws Exception {
        Path configured = temp.resolve("configured.sqlite"), explicit = temp.resolve("explicit.sqlite");
        var first = new SyncRunLedger(configured);
        first.createRun(new SyncRunLedger.Run("run-shared",null,"data.stock_basic",2,"2026-09-29","configured-target","{}"));
        var second = new SyncRunLedger(explicit);
        second.createRun(new SyncRunLedger.Run("run-shared",null,"data.stock_basic",2,"2026-09-29","explicit-target","{}"));
        var command = cli(new LedgerManagementService(configured.toString()));
        assertEquals("configured-target",output(command,"show-sync-history").at("/runs/0/targetId").textValue());
        assertEquals("configured-target",output(command,"show-sync-run","--run","run-shared").at("/run/targetId").textValue());
        assertTrue(output(command,"cancel-sync-run","--run","run-shared").path("cancellationRequested").booleanValue());
        assertTrue(first.cancellationRequested("run-shared"));
        assertFalse(second.cancellationRequested("run-shared"));
        assertEquals("explicit-target",output(command,"show-sync-history","--ledger",explicit.toString()).at("/runs/0/targetId").textValue());
        assertEquals("explicit-target",output(command,"show-sync-run","--ledger",explicit.toString(),"--run","run-shared").at("/run/targetId").textValue());
        var cancelled = output(command,"cancel-sync-run","--ledger",explicit.toString(),"--run","run-shared");
        assertTrue(cancelled.path("cancellationRequested").booleanValue());
        assertEquals(second.get("run-shared").state().name(),cancelled.path("state").textValue());
        assertTrue(second.cancellationRequested("run-shared"));
        verifyNoInteractions(runner,legacy,group);
    }
}
