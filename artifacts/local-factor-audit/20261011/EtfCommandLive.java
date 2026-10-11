import com.zoutrankil.data.cli.EtfFactorPanelCommands;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.util.Map;
public class EtfCommandLive {
    public static void main(String[] args)throws Exception {
        var ds=new DriverManagerDataSource();ds.setUrl("jdbc:postgresql://localhost:8812/qdb?sslmode=disable");
        ds.setUsername(System.getenv().getOrDefault("QUESTDB_USER","admin"));
        ds.setPassword(System.getenv().getOrDefault("QUESTDB_PASSWORD","quest"));
        new EtfFactorPanelCommands(new JdbcTemplate(ds)).execute("compute-etf-factor-panel",Map.of(
                "--history-from",args.length>1?args[1]:"2026-07-30","--from","2026-09-30","--to","2026-09-30","--output",args[0]));
    }
}
