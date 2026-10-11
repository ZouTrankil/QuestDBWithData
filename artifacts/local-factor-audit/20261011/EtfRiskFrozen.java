import com.zoutrankil.data.factor.engine.*;
import java.nio.file.*;
import java.time.LocalDate;
import java.util.*;
public class EtfRiskFrozen {
    record Bar(LocalDate date,double close,double amount) {}
    public static void main(String[] args)throws Exception {
        var groups=new TreeMap<String,List<Bar>>();var lines=Files.readAllLines(Path.of(args[0]));
        for(String line:lines.subList(1,lines.size())) {
            var c=line.split("\t");groups.computeIfAbsent(c[0],ignored->new ArrayList<>()).add(new Bar(LocalDate.parse(c[1]),Double.parseDouble(c[2]),Double.parseDouble(c[3])));
        }
        var inputs=new ArrayList<EtfMarketPanel.Input>();
        groups.forEach((symbol,rows)->{
            var dates=new LocalDate[rows.size()];double[] close=new double[rows.size()],amount=new double[rows.size()];
            for(int i=0;i<rows.size();i++){dates[i]=rows.get(i).date();close[i]=rows.get(i).close();amount[i]=rows.get(i).amount();}
            inputs.add(new EtfMarketPanel.Input(symbol,dates,close,amount));
        });
        var result=EtfRiskContext.compute(inputs,EtfMarketPanel.compute(inputs));
        try(var writer=Files.newBufferedWriter(Path.of(args[1]))) {
            writer.write("trade_date\taverage_correlation_20d\tcorrelation_z_60d\tbreadth_1d\tcross_section_volatility\tliquidity_ratio_5d_20d\tstate\n");
            for(var row:result.rows())writer.write(row.date()+"\t"+row.averageCorrelation()+"\t"+row.correlationZ()+"\t"+row.breadth()+"\t"+row.crossSectionVolatility()+"\t"+row.liquidityRatio()+"\t"+row.state()+"\n");
        }
        Files.write(Path.of(args[1]+".symbols"),result.selectedSymbols());
        System.out.println("dates="+result.rows().size()+", selected="+result.selectedSymbols().size());
    }
}
