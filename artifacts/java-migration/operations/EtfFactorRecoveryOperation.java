import com.fasterxml.jackson.databind.JsonNode;
import com.zoutrankil.questdbwithdata.cli.CommandLineRunner;
import com.zoutrankil.questdbwithdata.domain.*;
import com.zoutrankil.questdbwithdata.mapper.EtfFactorMapper;
import com.zoutrankil.questdbwithdata.repository.*;
import com.zoutrankil.questdbwithdata.service.*;
import io.questdb.client.QuestDB;
import org.springframework.boot.*;
import org.springframework.core.env.MapPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** D017 cancellation/resume, CLI frozen-plan recovery and unknown-ack reconciliation exercise. */
class EtfFactorRecoveryOperation {
    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("D017 verified acceptance report required");
        Path acceptance = Path.of(args[0]).toAbsolutePath().normalize();
        Path root = acceptance.getParent();
        var json = JobDefinitionJson.mapper();
        JsonNode accepted = json.readTree(Files.readAllBytes(acceptance));
        String table = accepted.path("table").asText();
        if (!"D017".equals(accepted.path("task").asText())
                || !"VERIFIED".equals(accepted.path("result").asText())
                || !table.matches("java_d017_etf_factor_[a-f0-9]{32}"))
            throw new IllegalArgumentException("An owned, verified D017 isolated acceptance report is required");

        String nonce = UUID.randomUUID().toString();
        Path ledger = root.resolve("D017-recovery-" + nonce + ".sqlite");
        Path evidence = root.resolve("sync-evidence").resolve("D017-recovery-" + nonce);
        var report = new LinkedHashMap<String, Object>();
        report.put("task", "D017"); report.put("table", table); report.put("ledger", ledger.toString());
        report.put("startedAt", Instant.now().toString()); report.put("formalTableMutated", false);
        report.put("humanReview", "pending_review");

        var app = new SpringApplication(local.market.EtfFactorOperationApplication.class);
        app.setWebApplicationType(WebApplicationType.NONE); app.setLogStartupInfo(false);
        app.addInitializers(context -> context.getEnvironment().getPropertySources().addFirst(
                new MapPropertySource("d017-recovery", Map.of("app.sync.ledger-path", ledger.toString(),
                        "app.sync.etf-factor-table", table, "app.tushare.concurrency", 1))));
        try (var context = app.run("list-sync-jobs")) {
            var jdbc = context.getBean(JdbcTemplate.class);
            var questdb = context.getBean(QuestDB.class);
            var pages = context.getBean(TusharePageService.class);
            var calendars = context.getBean(ExchangeCalendarReadRepository.class);
            var service = context.getBean(EtfFactorJobService.class);
            var cli = context.getBean(CommandLineRunner.class);
            LocalDate date = LocalDate.parse(accepted.path("requestWindows").path("initial").get(0).asText());
            LocalDate logicalDate = LocalDate.now(ZoneId.of("Asia/Shanghai"));
            var plan = service.plan(SyncJobDefinition.Mode.BACKFILL, date, date, logicalDate);
            String acceptedTarget = accepted.path("targetId").asText();
            if (!acceptedTarget.equals(plan.targetId()))
                throw new IllegalStateException("D017 recovery plan resolved a different physical target");
            Map<String, Object> before = EtfFactorAcceptanceOperation.snapshot(jdbc, table, EtfFactorDataset.DEFINITION);
            report.put("before", before); report.put("rowsBefore", before.get("rows"));

            var adapter = new EtfFactorSyncAdapter(
                    new EtfFactorSource(pages, new EtfFactorMapper(), evidence.resolve("source")),
                    new EtfFactorTradingDates(calendars),
                    new EtfFactorWritePort(table, plan.targetId(), jdbc, questdb), evidence);
            MarketRecoveryOperation.exercise("D017", table, jdbc, ledger, plan.request(), plan.targetId(), adapter, report);

            var cancelled = (SyncJobRunner.Result) report.get("cancelAfterVerifiedPage");
            if (cancelled == null || cancelled.state() != SyncRunState.CANCELLED || cancelled.verifiedRows() < 251)
                throw new IllegalStateException("D017 recovery did not persist a verified source page before cancellation");
            var cliResume = EtfFactorAcceptanceOperation.call(cli,
                    new String[]{"run-etf-factor-job", "--resume-from", cancelled.runId()});
            report.put("cliResume", cliResume);
            if (!SyncRunState.VERIFIED.name().equals(cliResume.path("state").asText())
                    || cliResume.path("reusedRows").asLong(-1) != cancelled.verifiedRows())
                throw new IllegalStateException("D017 CLI resume did not restore and revalidate the saved page without rewriting it");
            var readback = EtfFactorIndependentReadback.verify(jdbc, ledger, table, cliResume.path("runId").asText());
            report.put("independentCliResumeReadback", readback);
            if (!"MATCHED".equals(readback.get("status")))
                throw new IllegalStateException("D017 CLI resume independent 89-column readback failed");

            Map<String, Object> after = EtfFactorAcceptanceOperation.snapshot(jdbc, table, EtfFactorDataset.DEFINITION);
            report.put("after", after); report.put("rowsAfter", after.get("rows"));
            if (!before.equals(after)) throw new IllegalStateException("D017 recovery changed the isolated target snapshot");
            report.put("status", "VERIFIED");
        } catch (Exception failure) {
            report.put("status", "FAILED"); report.put("failure", failure.toString());
            throw failure;
        } finally {
            report.put("finishedAt", Instant.now().toString());
            Files.writeString(root.resolve("D017-recovery-" + nonce + ("VERIFIED".equals(report.get("status")) ? "" : "-failure") + ".json"),
                    json.writerWithDefaultPrettyPrinter().writeValueAsString(report));
        }
        System.out.println("D017 cancellation, receipt-backed resume, CLI frozen-request recovery and unknown acknowledgement: VERIFIED");
    }
}
