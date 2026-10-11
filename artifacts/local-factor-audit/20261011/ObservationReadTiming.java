import com.zoutrankil.data.factor.storage.QuestDbGlobalObservations;
import com.zoutrankil.data.factor.application.GlobalObservationCoverage;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.time.LocalDate;
public class ObservationReadTiming {
 public static void main(String[] args) {
  var ds=new DriverManagerDataSource("jdbc:postgresql://localhost:8812/qdb?sslmode=disable",
   System.getenv().getOrDefault("QUESTDB_USER","admin"),System.getenv().getOrDefault("QUESTDB_PASSWORD","quest"));
  var jdbc=new JdbcTemplate(ds);jdbc.setQueryTimeout(30);var reader=new QuestDbGlobalObservations(jdbc);
  long begin=System.nanoTime();
  var rows=reader.read(LocalDate.of(2026,9,30),"stock","l2_continuous_auction_ratio_raw","factor_platform_v2_0_2",10000);
  System.out.println("section rows="+rows.size()+" seconds="+(System.nanoTime()-begin)/1e9);
  var keys=rows.stream().limit(250).map(GlobalObservationCoverage::key).toList();
  for(int i=0;i<3;i++){begin=System.nanoTime();var actual=reader.readKeys(keys);
   System.out.println("page rows="+actual.size()+" seconds="+(System.nanoTime()-begin)/1e9);}
 }
}
