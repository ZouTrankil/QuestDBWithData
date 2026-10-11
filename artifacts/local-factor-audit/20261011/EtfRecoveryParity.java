import com.zoutrankil.data.factor.engine.EtfRecoveryQuality;
import java.nio.file.*;
import java.util.*;
public class EtfRecoveryParity {
    public static void main(String[] args) throws Exception {
        var groups=new LinkedHashMap<String,List<double[]>>();
        for(String line:Files.readAllLines(Path.of(args[0]))) {
            var c=line.split("\t");double[] row=new double[7];for(int i=0;i<7;i++)row[i]=Double.parseDouble(c[i+1]);
            groups.computeIfAbsent(c[0],k->new ArrayList<>()).add(row);
        }
        int checks=0;double maximum=0;
        for(var rows:groups.values()) {
            double[][] input=new double[6][rows.size()];
            for(int i=0;i<rows.size();i++)for(int j=0;j<6;j++)input[j][i]=rows.get(i)[j];
            var actual=EtfRecoveryQuality.compute(input[0],input[1],input[2],input[3],input[4],input[5]);
            for(int i=0;i<rows.size();i++) {
                double expected=rows.get(i)[6];checks++;
                if(Double.isNaN(expected)) {if(!Double.isNaN(actual[i]))throw new AssertionError("Missing mismatch");}
                else {double error=Math.abs(expected-actual[i]);if(!Double.isFinite(actual[i]) || error>1e-12)throw new AssertionError("Rank mismatch");maximum=Math.max(maximum,error);}
            }
        }
        System.out.println("{\"checks\":"+checks+",\"mismatches\":0,\"maxAbsoluteError\":"+maximum+"}");
    }
}
