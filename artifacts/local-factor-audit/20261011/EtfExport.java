import com.zoutrankil.data.cli.EtfFactorPanelCommands;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.Map;
public class EtfExport {
 public static void main(String[] args)throws Exception {
  new EtfFactorPanelCommands(new JdbcTemplate()).execute("export-etf-observations",Map.of(
   "--panel",args.length>0?args[0]:"artifacts/local-factor-audit/20261011/etf-command-csi-window-panel.json",
   "--output",args.length>1?args[1]:"artifacts/local-factor-audit/20261011/etf-observations.json","--computed-at","2026-10-11T08:00:00"));
 }
}
