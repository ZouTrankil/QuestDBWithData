import com.zoutrankil.data.factor.engine.EtfEarningsOutlook;
import java.nio.file.*;
import java.time.LocalDate;
import java.util.*;
public class EtfEarningsParity {
    public static void main(String[] args) throws Exception {
        var forecasts=new ArrayList<EtfEarningsOutlook.Event>();var express=new ArrayList<EtfEarningsOutlook.Event>();
        var members=new ArrayList<EtfEarningsOutlook.Exposure>();var expectedStocks=new HashMap<String,Double>();var expectedEtfs=new HashMap<String,double[]>();
        for(String line:Files.readAllLines(Path.of(args[0]))) {
            var c=line.split("\t");
            switch(c[0]) {
                case "F" -> forecasts.add(new EtfEarningsOutlook.Event(c[1],LocalDate.parse(c[2]),EtfEarningsOutlook.forecast(Double.parseDouble(c[3]),Double.parseDouble(c[4]))));
                case "X" -> express.add(new EtfEarningsOutlook.Event(c[1],LocalDate.parse(c[2]),EtfEarningsOutlook.express(Double.parseDouble(c[3]),Double.parseDouble(c[4]),Double.parseDouble(c[5]))));
                case "M" -> members.add(new EtfEarningsOutlook.Exposure(c[1],c[2],Double.parseDouble(c[3])));
                case "S" -> expectedStocks.put(c[1],Double.parseDouble(c[2]));
                case "E" -> expectedEtfs.put(c[1],new double[]{Double.parseDouble(c[2]),Double.parseDouble(c[3]),Double.parseDouble(c[4])});
            }
        }
        var date=LocalDate.parse("2026-09-30");
        forecasts.add(new EtfEarningsOutlook.Event("S0",date.plusDays(1),2));
        var latest=EtfEarningsOutlook.latest(forecasts,express,date);
        if(!latest.keySet().equals(expectedStocks.keySet()))throw new AssertionError("Stock coverage mismatch");
        for(var row:latest.entrySet())check(row.getValue().value(),expectedStocks.get(row.getKey()));
        var result=EtfEarningsOutlook.aggregate(latest,members,date);
        if(result.size()!=expectedEtfs.size())throw new AssertionError("ETF coverage mismatch");
        for(var row:result) {var e=expectedEtfs.get(row.etf());check(row.weightedOutlook(),e[0]);check(row.coverage(),e[1]);check(row.value(),e[2]);}
        System.out.println("{\"stockChecks\":"+latest.size()+",\"etfChecks\":"+result.size()*3+",\"mismatches\":0,\"futureAnnouncementExcluded\":true}");
    }
    static void check(double a,double e) {if(Double.isNaN(e)?!Double.isNaN(a):!Double.isFinite(a)||Math.abs(a-e)>1e-12)throw new AssertionError("Earnings mismatch");}
}
