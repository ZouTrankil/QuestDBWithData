import com.zoutrankil.questdbwithdata.domain.JobDefinitionJson;
import com.zoutrankil.questdbwithdata.service.MarketSourceReadbackVerifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

class MarketReceiptReadback {
    public static void main(String[] args) throws Exception {
        if (args.length == 0) throw new IllegalArgumentException("Explicit acceptance report paths required");
        var json = JobDefinitionJson.mapper();
        var dataSource = new DriverManagerDataSource("jdbc:postgresql://127.0.0.1:8812/qdb",
                Objects.requireNonNull(System.getenv("APP_QUESTDB_USERNAME")),
                Objects.requireNonNull(System.getenv("APP_QUESTDB_PASSWORD")));
        var jdbc = new JdbcTemplate(dataSource);
        jdbc.setQueryTimeout(60);
        for (String arg : args) {
            Path input = Path.of(arg).toAbsolutePath().normalize();
            var acceptance = json.readTree(Files.readAllBytes(input));
            String task = acceptance.path("task").asText();
            String table = acceptance.path("table").asText();
            if (!Set.of("D007", "D008", "D009", "D010", "D014", "D015").contains(task)
                    || !table.matches("java_(d00[789]_[a-z]+|d010_stk_limit|d014_etf_daily|d015_etf_adj)_[a-f0-9]{32}"))
                throw new IllegalArgumentException("Expected an explicit owned market acceptance report");
            var reports = new ArrayList<Map<String,Object>>();
            var output = new LinkedHashMap<String,Object>();
            output.put("task", task); output.put("table", table);
            output.put("acceptanceReport", input.toString()); output.put("startedAt", Instant.now().toString());
            output.put("databaseOperation", "SELECT only"); output.put("sourceRequests", 0);
            output.put("runs", reports); output.put("humanReview", "pending_review");
            Path destination = input.resolveSibling(task + "-independent-source-readback.json");
            try {
                for (var run : acceptance.path("runs")) {
                    String runId = run.path("runId").asText();
                    var report = MarketSourceReadbackVerifier.verify(jdbc, task, table,
                            input.resolveSibling("sync-ledger.sqlite"), runId);
                    reports.add(report);
                    if (!"MATCHED".equals(report.get("status"))
                            || ((Number) report.get("sourceRows")).longValue() != run.path("sourceRows").asLong())
                        throw new IllegalStateException("Independent raw-source comparison failed for " + runId);
                    System.out.println(task + " " + runId + " MATCHED rows=" + report.get("sourceRows"));
                }
                if (reports.size() != 4) throw new IllegalStateException("Expected initial, two repeats and increment");
                output.put("status", "MATCHED");
            } catch (Exception failure) {
                output.put("status", "FAILED"); output.put("failure", failure.toString());
                throw failure;
            } finally {
                output.put("finishedAt", Instant.now().toString());
                Files.writeString(destination, json.writerWithDefaultPrettyPrinter().writeValueAsString(output));
            }
        }
    }
}
