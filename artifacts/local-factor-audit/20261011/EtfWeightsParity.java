import com.zoutrankil.data.factor.engine.EtfConstituentWeights;
import java.nio.file.*;
import java.time.LocalDate;
import java.util.*;
public class EtfWeightsParity {
    public static void main(String[] args) throws Exception {
        var source=new ArrayList<EtfConstituentWeights.Input>();var expected=new LinkedHashMap<String,Double>();
        for(String line:Files.readAllLines(Path.of(args[0]))) {
            var c=line.split("\t");
            if(c[0].equals("INPUT"))source.add(new EtfConstituentWeights.Input(c[1],c[2],Double.parseDouble(c[3]),Double.parseDouble(c[4]),LocalDate.parse("2026-09-01"),LocalDate.parse("2026-06-30")));
            else expected.put(c[1]+"/"+c[2],Double.parseDouble(c[3]));
        }
        var actual=EtfConstituentWeights.normalize(source,Set.of("E0","E1","E2","E3"));
        if(actual.size()!=expected.size())throw new AssertionError("Member count mismatch");
        double maximum=0;var keys=new ArrayList<String>();
        for(var row:actual) {
            var key=row.etf()+"/"+row.stock();keys.add(key);double error=Math.abs(row.weight()-expected.get(key));
            if(error>1e-12)throw new AssertionError("Weight mismatch: "+key);maximum=Math.max(maximum,error);
        }
        if(!keys.equals(new ArrayList<>(expected.keySet())))throw new AssertionError("Last-occurrence ordering differs");
        System.out.println("{\"members\":"+actual.size()+",\"mismatches\":0,\"maxAbsoluteError\":"+maximum+"}");
    }
}
