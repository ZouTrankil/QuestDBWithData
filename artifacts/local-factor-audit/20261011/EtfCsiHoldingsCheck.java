import com.zoutrankil.data.factor.engine.*;
import java.time.LocalDate;
import java.util.*;
public class EtfCsiHoldingsCheck {
    static EtfConstituentWeights.Input row(String stock,String ann,String end,double ratio) {
        return new EtfConstituentWeights.Input("E",stock,ratio,ratio,LocalDate.parse(ann),LocalDate.parse(end));
    }
    public static void main(String[] args) {
        var source=List.of(row("A","2026-08-01","2026-06-30",1),row("B","2026-09-01","2026-03-31",1),
            row("C","2026-09-01","2026-06-30",1),row("D","2026-09-20","2026-03-31",1),row("FUTURE","2026-10-01","2026-09-30",1));
        var index=new EtfCsiHoldings(source,Set.of("E"),LocalDate.parse("2026-09-30"));
        for(String[] check:new String[][]{{"2026-08-15","A"},{"2026-09-01","C"},{"2026-09-30","D"}}) {
            var rows=index.at(LocalDate.parse(check[0]));if(rows.size()!=1 || !rows.getFirst().stock().equals(check[1]) || rows.getFirst().weight()!=1)throw new AssertionError(rows);
        }
        if(!index.at(LocalDate.parse("2026-07-31")).isEmpty())throw new AssertionError("Future snapshot");
        System.out.println("{\"checks\":4,\"failures\":0}");
    }
}
