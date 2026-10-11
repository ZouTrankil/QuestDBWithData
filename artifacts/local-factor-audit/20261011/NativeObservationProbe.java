import com.zoutrankil.data.factor.application.*;import com.zoutrankil.data.domain.*;
import java.nio.file.*;import java.time.*;import java.util.*;
public class NativeObservationProbe {
 public static void main(String[] args)throws Exception{
  Path root=Path.of(args[0]);var json=JobDefinitionJson.mapper();var artifact=json.readTree(Files.readAllBytes(root.resolve("daily-feature-java-direct.json")));var source=artifact.get("input");
  LocalDate[] dates=new LocalDate[source.get("dates").size()];for(int i=0;i<dates.length;i++)dates[i]=LocalDate.parse(source.get("dates").get(i).asText());
  Map<String,double[]> columns=new HashMap<>();var fields=source.get("columns").fields();while(fields.hasNext()){var field=fields.next();double[] values=new double[dates.length];for(int i=0;i<values.length;i++)values[i]=field.getValue().get(i).isNull()?Double.NaN:field.getValue().get(i).asDouble();columns.put(field.getKey(),values);}
  var date=LocalDate.of(2026,9,30);var rows=NativeDailyObservations.compute(source.get("symbol").asText(),dates,columns,Set.of(date),List.of("reversal_1d","risk_adv20"),FactorDefinitionCatalog.load(),date.atTime(20,0));
  if(rows.size()!=2||!rows.getFirst().signalDate().equals(date)||Math.abs(rows.getFirst().factorValue()-artifact.get("columns").get("ret_1").get(dates.length-1).asDouble())>1e-12)throw new AssertionError(rows);
  Path output=Files.createTempDirectory(root,"native-observations-").resolve("observations.json");
  FactorObservationEvidence.writeNew(output,rows,false,artifact.get("inputSha256").asText());
  System.out.println("PASS: real source -> native kernel -> catalog raw observations -> frozen evidence; 309 input dates, 1 output date, 2 factors; "+output);
 }
}
