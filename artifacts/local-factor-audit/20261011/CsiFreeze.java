import com.zoutrankil.data.factor.storage.*;
import com.zoutrankil.data.factor.engine.*;
import com.zoutrankil.data.domain.JobDefinitionJson;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.time.LocalDate;
import java.nio.file.*;
import java.util.*;
public class CsiFreeze {
 public static void main(String[] args)throws Exception {
  var root=Path.of("artifacts/local-factor-audit/20261011");var json=JobDefinitionJson.mapper();
  var report=json.readTree(root.resolve("etf-command-csi-window-panel.json").toFile());
  var etfs=new HashSet<String>();for(var row:report.path("securities"))etfs.add(row.path("symbol").asText());
  // Include ETFs observed earlier in the same input history, as the production command does.
  var ds=new DriverManagerDataSource("jdbc:postgresql://localhost:8812/qdb?sslmode=disable",System.getenv().getOrDefault("QUESTDB_USER","admin"),System.getenv().getOrDefault("QUESTDB_PASSWORD","quest"));
  var jdbc=new JdbcTemplate(ds);var date=LocalDate.of(2026,9,30);var history=args.length>0?LocalDate.parse(args[0]):LocalDate.of(2026,7,30);
  var etfReader=new QuestDbEtfInputs(jdbc);var etf=etfReader.read(history,date,1000000);etfs.addAll(etf.market().categories().keySet());
  var reader=new QuestDbDailyFactorPanel(jdbc);var source=reader.readCsi(history,date,5000000);
  var financialReader=new QuestDbCsiFinancials(jdbc);var financial=financialReader.read(date,1000000);
  var holdingsReader=new QuestDbEtfConstituents(jdbc);var holdings=holdingsReader.readWindow(date,date,date,etfs,1000000);
  long started=System.nanoTime();
  var result=EtfCsiPipeline.compute(source.prepare(financial),holdings.history().csiHoldings(etfs),date,date,Set.of(date));
  double computeSeconds=(System.nanoTime()-started)/1e9;
  reader.verifyUnchanged(source);financialReader.verifyUnchanged(financial);holdingsReader.verifyUnchanged(holdings.history());etfReader.verifyUnchanged(etf);
  var output=root.resolve(args.length>1?args[1]:"csi-frozen-inputs.json");if(Files.exists(output))throw new IllegalStateException("Output exists");
  json.writeValue(output.toFile(),Map.of("market",source.market(),"basic",source.basic(),"financial",financial,"holdings",holdings,"validEtfs",etfs,"java",result,"javaComputeSeconds",computeSeconds));
  System.out.println("frozen rows="+result.size()+" file="+output);
 }
}
