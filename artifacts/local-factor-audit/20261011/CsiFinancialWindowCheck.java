import com.zoutrankil.data.factor.storage.QuestDbCsiFinancials;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.time.LocalDate;
import java.nio.file.*;
import java.util.*;
public class CsiFinancialWindowCheck {
 public static void main(String[] args)throws Exception {
  var ds=new DriverManagerDataSource("jdbc:postgresql://localhost:8812/qdb?sslmode=disable",System.getenv().getOrDefault("QUESTDB_USER","admin"),System.getenv().getOrDefault("QUESTDB_PASSWORD","quest"));
  var reader=new QuestDbCsiFinancials(new JdbcTemplate(ds));var from=LocalDate.of(2026,9,1);var through=LocalDate.of(2026,9,30);
  var full=reader.read(through,1000000);var window=reader.read(from,through,1000000);
  if(!full.sourceState().equals(window.sourceState()))throw new AssertionError("Source changed");
  var dates=from.datesUntil(through.plusDays(1)).toArray(LocalDate[]::new);var symbols=new TreeSet<String>();
  full.tables().values().forEach(t->symbols.addAll(t.keySet()));long checks=0;
  for(String symbol:symbols) {
   var expected=full.align(symbol,dates);var actual=window.align(symbol,dates);
   for(String field:expected.keySet()) {
    if(!Arrays.equals(expected.get(field),actual.get(field)))throw new AssertionError(symbol+" "+field);
    checks+=dates.length;
   }
  }
  reader.verifyUnchanged(window);
  long retained=window.tables().values().stream().flatMap(t->t.values().stream()).mapToLong(List::size).sum();
  String report="{\"sourceRows\":"+full.rows()+",\"retainedEvents\":"+retained+",\"checks\":"+checks+",\"mismatches\":0,\"sourceStateMatched\":true}";
  Files.writeString(Path.of("artifacts/local-factor-audit/20261011/csi-financial-window-parity.json"),report);System.out.println(report);
 }
}
