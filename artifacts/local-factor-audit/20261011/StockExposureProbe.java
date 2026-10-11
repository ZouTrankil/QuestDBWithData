import com.zoutrankil.data.factor.engine.PointInTimeStockExposures;
import java.nio.file.*;
import java.time.*;
import java.util.*;

public class StockExposureProbe {
    static String nullable(String s) { return s.equals("<NULL>") ? null : s; }
    static LocalDate date(String s) { return s.equals("<NULL>") ? null : LocalDate.parse(s); }
    public static void main(String[] args) throws Exception {
        var members = new ArrayList<PointInTimeStockExposures.Membership>();
        for (String line : Files.readAllLines(Path.of(args[0]))) {
            String[] c = line.split("\t", -1);
            members.add(new PointInTimeStockExposures.Membership(c[0],date(c[1]),date(c[2]),
                    nullable(c[3]),nullable(c[4]),nullable(c[5])));
        }
        var index = new PointInTimeStockExposures.Index(members);
        var rows = Files.readAllLines(Path.of(args[1]));
        String[] symbols = new String[rows.size()];
        double[] circ = new double[rows.size()], total = new double[rows.size()];
        for (int i = 0; i < rows.size(); i++) {
            String[] c = rows.get(i).split("\t", -1);
            symbols[i]=c[0]; circ[i]=Double.parseDouble(c[1]); total[i]=Double.parseDouble(c[2]);
        }
        var output = new ArrayList<String>();
        for (String d : args[3].split(",")) {
            var result = index.resolve(LocalDate.parse(d), symbols, circ, total);
            for (int i=0;i<symbols.length;i++) output.add(d+"\t"+symbols[i]+"\t"+
                    result.logMarketCap()[i]+"\t"+Objects.toString(result.industry()[i],"<NULL>"));
        }
        Files.write(Path.of(args[2]), output);
    }
}
