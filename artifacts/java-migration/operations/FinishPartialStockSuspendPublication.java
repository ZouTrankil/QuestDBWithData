import com.zoutrankil.questdbwithdata.service.*;
import com.zoutrankil.questdbwithdata.repository.*;
import com.zoutrankil.questdbwithdata.domain.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.nio.file.*;
import java.time.*;
import java.util.*;
class FinishPartialStockSuspendPublication {
    public static void main(String[] args)throws Exception {
        Path input=Path.of(args[0]).toAbsolutePath();var json=JobDefinitionJson.mapper();var failed=json.readTree(Files.readAllBytes(input));
        String table=failed.path("table").asText();var scenario=failed.path("scenarios").get(0);String run=scenario.path("runId").asText();
        if(!failed.path("task").asText().equals("D011")||!failed.path("status").asText().equals("FAILED")||!scenario.path("faultFired").asBoolean()
                ||!table.matches("java_d011_stk_suspend_[a-f0-9]{32}"))throw new IllegalArgumentException("Owned stopped failed publication required");
        Path ledger=input.getParent().resolve("sync-ledger.sqlite");var durable=new SyncRunLedger(ledger);
        if(durable.get(run).state()!=SyncRunState.PARTIAL)throw new IllegalStateException("Preserved PARTIAL run required");
        var ds=new DriverManagerDataSource("jdbc:postgresql://127.0.0.1:8812/qdb",Objects.requireNonNull(System.getenv("APP_QUESTDB_USERNAME")),Objects.requireNonNull(System.getenv("APP_QUESTDB_PASSWORD")));
        var jdbc=new JdbcTemplate(ds);jdbc.setQueryTimeout(30);
        StockSuspendPublication.finish(ledger,jdbc,table,run,true);
        var snapshot=StockStAcceptanceOperation.snapshot(jdbc,table,StockSuspendDataset.DEFINITION);
        if(!json.valueToTree(snapshot).equals(failed.path("before")))throw new IllegalStateException("Published values changed");
        if(durable.get(run).state()!=SyncRunState.PARTIAL)throw new IllegalStateException("Historical terminal run must remain PARTIAL");
        var evidence=Map.of("task","D011","runId",run,"originalFailure",input.toString(),"publicationState",new ReferencePublicationJournal(ledger,"stk_suspend").forRun(run).state(),
            "historicalRunState",durable.get(run).state(),"wholeTarget",snapshot,"formalTableMutated",false,"finishedAt",Instant.now().toString());
        Files.writeString(input.resolveSibling("D011-partial-publication-finished-"+UUID.randomUUID()+".json"),json.writerWithDefaultPrettyPrinter().writeValueAsString(evidence));
        System.out.println("D011 interrupted rename finished; historical PARTIAL retained, target snapshot unchanged");
    }
}
