import com.zoutrankil.data.factor.engine.EtfResidualMomentum;
import java.nio.file.*;
import java.util.*;
public class EtfResidualParity {
    public static void main(String[] args) throws Exception {
        var cases=new LinkedHashMap<String,List<String[]>>();
        for(String line:Files.readAllLines(Path.of(args[0]))) { var cells=line.split("\t");cases.computeIfAbsent(cells[0],k->new ArrayList<>()).add(cells); }
        int checked=0;double maximum=0;
        for(var rows:cases.values()) {
            int n=rows.size();double[] returns=new double[n],beta=new double[n];String[] styles=new String[n];
            for(int i=0;i<n;i++) { var row=rows.get(i);returns[i]=Double.parseDouble(row[1]);beta[i]=Double.parseDouble(row[2]);styles[i]=row[3].equals("<NULL>")?null:row[3]; }
            var actual=EtfResidualMomentum.compute(returns,beta,styles);
            for(int i=0;i<n;i++) {
                double expected=Double.parseDouble(rows.get(i)[4]);checked++;
                if(Double.isNaN(expected)) { if(!Double.isNaN(actual[i]))throw new AssertionError("Missing mismatch"); }
                else { double error=Math.abs(actual[i]-expected);if(!Double.isFinite(actual[i]) || error>1e-10)throw new AssertionError("Residual mismatch: "+error);maximum=Math.max(maximum,error); }
            }
        }
        System.out.println("{\"cases\":"+cases.size()+",\"checks\":"+checked+",\"mismatches\":0,\"maxAbsoluteError\":"+maximum+"}");
    }
}
