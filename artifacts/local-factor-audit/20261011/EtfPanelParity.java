import com.zoutrankil.data.factor.engine.EtfMarketPanel;
import java.nio.file.*;
import java.time.LocalDate;
import java.util.*;

public class EtfPanelParity {
    record Row(LocalDate date,double[] values) {}
    public static void main(String[] args) throws Exception {
        var lines=Files.readAllLines(Path.of(args[0]));var names=lines.getFirst().split("\t");
        var groups=new LinkedHashMap<String,List<Row>>();
        for(String line:lines.subList(1,lines.size())) {
            var cells=line.split("\t");double[] values=new double[cells.length-2];
            for(int i=2;i<cells.length;i++)values[i-2]=Double.parseDouble(cells[i]);
            groups.computeIfAbsent(cells[0],ignored->new ArrayList<>()).add(new Row(LocalDate.parse(cells[1]),values));
        }
        var inputs=new ArrayList<EtfMarketPanel.Input>();
        groups.forEach((symbol,rows)-> {
            var dates=new LocalDate[rows.size()];double[] close=new double[rows.size()],amount=new double[rows.size()];
            for(int i=0;i<rows.size();i++) { dates[i]=rows.get(i).date();close[i]=rows.get(i).values()[0];amount[i]=rows.get(i).values()[1]; }
            inputs.add(new EtfMarketPanel.Input(symbol,dates,close,amount));
        });
        var actual=EtfMarketPanel.compute(inputs,Map.of());int checks=0;double maximum=0;
        for(var section:actual)for(int i=0;i<section.dates().length;i++)for(int j=4;j<names.length;j++) {
            double expected=groups.get(section.symbol()).get(i).values()[j-2],value=section.columns().get(names[j])[i];checks++;
            if(Double.isNaN(expected)) { if(!Double.isNaN(value))throw new AssertionError("Missing mismatch "+names[j]); }
            else {
                double error=Math.abs(value-expected)/Math.max(1,Math.abs(expected));
                if(!Double.isFinite(value) || error>1e-9)throw new AssertionError(section.symbol()+"/"+i+"/"+names[j]+": "+expected+" != "+value);
                maximum=Math.max(maximum,error);
            }
        }
        System.out.println("{\"checks\":"+checks+",\"mismatches\":0,\"maxScaledError\":"+maximum+"}");
    }
}
