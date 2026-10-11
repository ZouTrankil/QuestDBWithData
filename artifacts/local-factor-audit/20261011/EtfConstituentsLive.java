import com.zoutrankil.data.factor.storage.QuestDbEtfConstituents;
import com.zoutrankil.data.domain.JobDefinitionJson;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.time.LocalDate;
import java.nio.file.*;
import java.util.*;
public class EtfConstituentsLive {
    public static void main(String[] args)throws Exception {
        var ds=new DriverManagerDataSource();ds.setUrl("jdbc:postgresql://localhost:8812/qdb?sslmode=disable");
        ds.setUsername(System.getenv().getOrDefault("QUESTDB_USER","admin"));ds.setPassword(System.getenv().getOrDefault("QUESTDB_PASSWORD","quest"));
        var reader=new QuestDbEtfConstituents(new JdbcTemplate(ds));var snapshot=reader.read(LocalDate.parse("2026-09-30"),1000000);
        var etfs=new HashSet<String>();snapshot.rows().forEach(row->etfs.add(row.etf()));
        var members=snapshot.normalize(etfs);reader.verifyUnchanged(snapshot);
        Files.write(Path.of(args[0]),JobDefinitionJson.mapper().writeValueAsBytes(Map.of("snapshot",snapshot,"members",members)));
        System.out.println("{\"sourceRows\":"+snapshot.rows().size()+",\"members\":"+members.size()+",\"etfs\":"+etfs.size()+"}");
    }
}
