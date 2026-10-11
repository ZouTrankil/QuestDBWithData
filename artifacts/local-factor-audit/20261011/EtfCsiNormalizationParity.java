import com.zoutrankil.data.factor.engine.EtfCsiCrossSection;
import java.nio.file.*;
import java.util.*;
public class EtfCsiNormalizationParity {
    public static void main(String[] args)throws Exception {
        var cases=new LinkedHashMap<String,List<double[]>>();
        for(String line:Files.readAllLines(Path.of(args[0]))){var c=line.split("\t");cases.computeIfAbsent(c[0],ignored->new ArrayList<>()).add(new double[]{Double.parseDouble(c[1]),Double.parseDouble(c[2])});}
        int checks=0;double max=0;
        for(var rows:cases.values()) {
            var actual=EtfCsiCrossSection.normalize(rows.stream().mapToDouble(r->r[0]).toArray());
            for(int i=0;i<actual.length;i++){double expected=rows.get(i)[1];checks++;if(Double.isNaN(expected)){if(!Double.isNaN(actual[i]))throw new AssertionError();}else{double error=Math.abs(expected-actual[i]);if(error>1e-12)throw new AssertionError(error);max=Math.max(max,error);}}
        }
        System.out.println("{\"checks\":"+checks+",\"cases\":"+cases.size()+",\"mismatches\":0,\"maxAbsoluteError\":"+max+"}");
    }
}
