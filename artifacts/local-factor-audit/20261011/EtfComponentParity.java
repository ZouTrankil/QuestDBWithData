import com.zoutrankil.data.factor.engine.EtfComponentFeatures;
import java.nio.file.*;
import java.util.*;
public class EtfComponentParity {
    public static void main(String[] args) throws Exception {
        var stocks=new ArrayList<EtfComponentFeatures.Stock>();var l2=new ArrayList<EtfComponentFeatures.L2>();
        var weights=new ArrayList<Double>();var expected=new LinkedHashMap<String,Double>();int checks=0;double maximum=0;
        for(String line:Files.readAllLines(Path.of(args[0]))) {
            var c=line.split("\t");
            switch(c[0]) {
                case "CASE" -> {stocks.clear();l2.clear();weights.clear();expected.clear();}
                case "WEIGHT" -> weights.add(Double.parseDouble(c[1]));
                case "STOCK" -> stocks.add(new EtfComponentFeatures.Stock(c[1],Double.parseDouble(c[2]),Double.parseDouble(c[3]),Double.parseDouble(c[4])));
                case "L2" -> {double[] v=new double[8];for(int i=0;i<8;i++)v[i]=Double.parseDouble(c[i+1]);l2.add(new EtfComponentFeatures.L2(v[0],v[1],v[2],v[3],v[4],v[5],v[6],v[7]));}
                case "EXPECT" -> expected.put(c[1],Double.parseDouble(c[2]));
                case "END" -> {
                    var actual=EtfComponentFeatures.compute(stocks,l2,weights.stream().mapToDouble(Double::doubleValue).toArray(),true);
                    if(!actual.keySet().equals(expected.keySet()))throw new AssertionError("Column mismatch");
                    for(var entry:expected.entrySet()) {
                        double a=actual.get(entry.getKey()),e=entry.getValue();checks++;
                        if(Double.isNaN(e)) {if(!Double.isNaN(a))throw new AssertionError(entry.getKey()+" missing mismatch");}
                        else {double error=Math.abs(a-e)/Math.max(1,Math.abs(e));if(!Double.isFinite(a) || error>1e-12)throw new AssertionError(entry.getKey()+" mismatch");maximum=Math.max(maximum,error);}
                    }
                }
                default -> throw new AssertionError("Unknown fixture row");
            }
        }
        System.out.println("{\"checks\":"+checks+",\"mismatches\":0,\"maxScaledError\":"+maximum+"}");
    }
}
