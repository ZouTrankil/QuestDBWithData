import com.zoutrankil.data.factor.engine.*;
import java.nio.file.*;
import java.time.LocalDate;
import java.util.*;
public class EtfSharedParity {
    record Row(LocalDate date,double[] values) {}
    public static void main(String[] args) throws Exception {
        var lines=Files.readAllLines(Path.of(args[0]));var names=lines.getFirst().split("\t");
        var groups=new LinkedHashMap<String,List<Row>>();
        var shares=new HashMap<EtfSharedPanel.Key,Double>();var breadth=new HashMap<EtfSharedPanel.Key,Double>();
        for(String line:lines.subList(1,lines.size())) {
            var cells=line.split("\t");var date=LocalDate.parse(cells[1]);double[] values=new double[cells.length-2];
            for(int i=2;i<cells.length;i++)values[i-2]=Double.parseDouble(cells[i]);
            groups.computeIfAbsent(cells[0],ignored->new ArrayList<>()).add(new Row(date,values));
            var key=new EtfSharedPanel.Key(cells[0],date);
            if(!Double.isNaN(values[2]))shares.put(key,values[2]);breadth.put(key,values[3]);
        }
        var inputs=new ArrayList<EtfMarketPanel.Input>();
        groups.forEach((symbol,rows)-> {
            var dates=new LocalDate[rows.size()];double[] close=new double[rows.size()],amount=new double[rows.size()];
            for(int i=0;i<rows.size();i++){dates[i]=rows.get(i).date();close[i]=rows.get(i).values()[0];amount[i]=rows.get(i).values()[1];}
            inputs.add(new EtfMarketPanel.Input(symbol,dates,close,amount));
        });
        var actual=EtfSharedPanel.compute(inputs,Map.of(),shares,breadth);int checks=0;double max=0;
        for(var section:actual.securities())for(int i=0;i<section.dates().length;i++)for(int j=6;j<names.length;j++) {
            double expected=groups.get(section.symbol()).get(i).values()[j-2],value=section.columns().get(names[j])[i];checks++;
            if(Double.isNaN(expected)){if(!Double.isNaN(value))throw new AssertionError("Missing "+names[j]);}
            else {double error=Math.abs(value-expected)/Math.max(1,Math.abs(expected));if(!Double.isFinite(value)||error>1e-9)throw new AssertionError(names[j]+" "+expected+" != "+value);max=Math.max(max,error);}
        }
        var date=actual.index().dates().lastKey();var selected=actual.select(date,Set.of(inputs.getFirst().symbol()));
        if(selected.size()!=1 || selected.getFirst().security()!=0)throw new AssertionError("Pool positions");
        System.out.println("{\"checks\":"+checks+",\"mismatches\":0,\"maxScaledError\":"+max+",\"poolPositionChecked\":true}");
    }
}
