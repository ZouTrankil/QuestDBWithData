import com.zoutrankil.data.factor.engine.EtfCsiTemporal;
import java.nio.file.*;
import java.time.LocalDate;
public class EtfCsiTemporalParity {
    public static void main(String[] args)throws Exception {
        var market=Files.readAllLines(Path.of(args[0]));var basic=Files.readAllLines(Path.of(args[1]));
        int n=market.size()-1,m=basic.size()-1;var dates=new LocalDate[n];var bd=new LocalDate[m];
        double[] close=new double[n],amount=new double[n],turnover=new double[m];
        for(int i=0;i<n;i++){var c=market.get(i+1).split("\t");dates[i]=LocalDate.parse(c[0]);close[i]=Double.parseDouble(c[1]);amount[i]=Double.parseDouble(c[2]);}
        for(int i=0;i<m;i++){var c=basic.get(i+1).split("\t");bd[i]=LocalDate.parse(c[0]);turnover[i]=Double.parseDouble(c[1]);}
        var actual=EtfCsiTemporal.compute(dates,close,amount,bd,turnover);var names=market.getFirst().split("\t");int checks=0;
        for(int i=0;i<n;i++){var c=market.get(i+1).split("\t");for(int j=3;j<names.length;j++){
            double expected=Double.parseDouble(c[j]),value=actual.get(names[j])[i];checks++;
            if(Double.isNaN(expected)?!Double.isNaN(value):!Double.isFinite(value)||Math.abs(expected-value)>1e-10)throw new AssertionError(names[j]+"/"+i);
        }}
        System.out.println("{\"checks\":"+checks+",\"mismatches\":0}");
    }
}
