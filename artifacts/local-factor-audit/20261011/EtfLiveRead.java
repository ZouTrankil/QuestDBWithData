import com.zoutrankil.data.factor.storage.QuestDbEtfInputs;
import com.zoutrankil.data.factor.engine.EtfSharedPanel;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.time.LocalDate;
import java.util.Map;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
public class EtfLiveRead {
    public static void main(String[] args)throws Exception {
        var ds=new DriverManagerDataSource();ds.setUrl("jdbc:postgresql://localhost:8812/qdb?sslmode=disable");
        ds.setUsername(System.getenv().getOrDefault("QUESTDB_USER","admin"));
        ds.setPassword(System.getenv().getOrDefault("QUESTDB_PASSWORD","quest"));
        long start=System.nanoTime();
        var snapshot=new QuestDbEtfInputs(new JdbcTemplate(ds)).read(LocalDate.parse(args[0]),LocalDate.parse(args[1]),100000);
        double read=(System.nanoTime()-start)/1e9;start=System.nanoTime();
        var panel=EtfSharedPanel.compute(snapshot.market().inputs(),snapshot.market().categories(),snapshot.shares(),Map.of());
        double compute=(System.nanoTime()-start)/1e9;
        if(args.length>2) {
            Path root=Path.of(args[2]);Files.createDirectories(root);
            try(var writer=Files.newBufferedWriter(root.resolve("inputs.tsv"))) {
                writer.write("symbol\tdate\tclose\tamount\tname_base64\n");
                for(var input:snapshot.market().inputs())for(int i=0;i<input.dates().length;i++) {
                    String name=snapshot.market().names().get(input.symbol());
                    writer.write(input.symbol()+"\t"+input.dates()[i]+"\t"+input.close()[i]+"\t"+input.amount()[i]+"\t"
                        +Base64.getEncoder().encodeToString((name==null?"":name).getBytes(StandardCharsets.UTF_8))+"\n");
                }
            }
            try(var writer=Files.newBufferedWriter(root.resolve("shares.tsv"))) {
                writer.write("symbol\tdate\tfd_share\n");
                for(var row:snapshot.shares().entrySet())writer.write(row.getKey().symbol()+"\t"+row.getKey().date()+"\t"+row.getValue()+"\n");
            }
            try(var writer=Files.newBufferedWriter(root.resolve("java.tsv"))) {
                var columns=panel.securities().getFirst().columns().keySet().stream().toList();
                writer.write("symbol\tdate\t"+String.join("\t",columns)+"\n");
                for(var section:panel.securities())for(int i=0;i<section.dates().length;i++) {
                    writer.write(section.symbol()+"\t"+section.dates()[i]);
                    for(String column:columns)writer.write("\t"+section.columns().get(column)[i]);writer.write("\n");
                }
            }
        }
        long rows=panel.securities().stream().mapToLong(s->s.dates().length).sum();
        System.out.println("{\"marketSourceRows\":"+snapshot.marketRows()+",\"shareSourceRows\":"+snapshot.shareRows()+",\"securities\":"+panel.securities().size()+",\"panelRows\":"+rows+",\"readSeconds\":"+read+",\"computeSeconds\":"+compute+",\"sourceComplete\":false,\"referencePointInTime\":false,\"published\":false}");
    }
}
