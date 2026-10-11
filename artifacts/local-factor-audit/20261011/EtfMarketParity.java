import com.zoutrankil.data.factor.engine.EtfMarketFeatures;
import java.nio.file.*;
import java.util.*;

public class EtfMarketParity {
    public static void main(String[] args) throws Exception {
        var lines=Files.readAllLines(Path.of(args[0]));var names=lines.getFirst().split("\t");
        var grouped=new LinkedHashMap<String,List<double[]>>();
        for(String line:lines.subList(1,lines.size())) {
            var cells=line.split("\t");double[] row=new double[cells.length-1];
            for(int i=1;i<cells.length;i++)row[i-1]=Double.parseDouble(cells[i]);
            grouped.computeIfAbsent(cells[0],ignored->new ArrayList<>()).add(row);
        }
        int checks=0;double maxRelativeError=0;
        for(var group:grouped.values()) {
            double[] close=new double[group.size()],amount=new double[group.size()];
            for(int i=0;i<group.size();i++) { close[i]=group.get(i)[0];amount[i]=group.get(i)[1]; }
            @SuppressWarnings("unchecked") var actual=args.length>1
                    ?(Map<String,double[]>)Class.forName(args[1]).getMethod("compute",double[].class,double[].class).invoke(null,close,amount)
                    :EtfMarketFeatures.compute(close,amount);
            for(int i=0;i<group.size();i++)for(int j=3;j<names.length;j++) {
                double expected=group.get(i)[j-1],value=actual.get(names[j])[i];checks++;
                if(Double.isNaN(expected)) { if(!Double.isNaN(value))throw new AssertionError(names[j]+"/"+i+" expected missing"); }
                else if(!Double.isFinite(expected)) { if(value!=expected)throw new AssertionError("Infinity mismatch"); }
                else {
                    double error=Math.abs(value-expected)/Math.max(1,Math.abs(expected));
                    if(!Double.isFinite(value) || error>1e-9)throw new AssertionError(names[j]+"/"+i+": "+expected+" != "+value);
                    maxRelativeError=Math.max(maxRelativeError,error);
                }
            }
        }
        System.out.println("{\"checks\":"+checks+",\"mismatches\":0,\"maxScaledError\":"+maxRelativeError+"}");
    }
}
