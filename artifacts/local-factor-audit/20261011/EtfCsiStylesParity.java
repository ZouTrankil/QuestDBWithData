import com.zoutrankil.data.factor.engine.EtfCsiStyles;
import java.nio.file.*;
import java.util.*;
public class EtfCsiStylesParity {
    public static void main(String[] args)throws Exception {
        var lines=Files.readAllLines(Path.of(args[0]));var names=lines.getFirst().split("\t");
        var columns=new LinkedHashMap<String,double[]>();for(String name:names)columns.put(name,new double[lines.size()-1]);
        for(int r=1;r<lines.size();r++){var cells=lines.get(r).split("\t");for(int c=0;c<names.length;c++)columns.get(names[c])[r-1]=Double.parseDouble(cells[c].replace("inf","Infinity"));}
        var actual=EtfCsiStyles.compute(lines.size()-1,columns);int checks=0;double maximum=0;
        for(var entry:actual.entrySet())for(int i=0;i<entry.getValue().length;i++) {
            double expected=columns.get(entry.getKey())[i],value=entry.getValue()[i];checks++;
            if(Double.isNaN(expected)){if(!Double.isNaN(value))throw new AssertionError("Missing "+entry.getKey());}
            else{double error=Math.abs(value-expected);if(!Double.isFinite(value)||error>1e-10)throw new AssertionError(entry.getKey()+"/"+i+" "+value+" != "+expected);maximum=Math.max(maximum,error);}
        }
        System.out.println("{\"factors\":"+actual.size()+",\"checks\":"+checks+",\"mismatches\":0,\"maxAbsoluteError\":"+maximum+"}");
    }
}
