import com.zoutrankil.data.factor.engine.*;
import java.time.LocalDate;
import java.util.*;
public class EtfStockReturnsCheck {
    public static void main(String[] args) {
        var date=LocalDate.parse("2026-09-30");var dates=new LocalDate[]{date.minusDays(9),date.minusDays(8),date.minusDays(7),date.minusDays(4),date.minusDays(1),date};
        double[] close={10,11,Double.NaN,12,13,15};
        var source=new DailyFactorPanel.Security("S0",dates,Map.of("close",close),null);
        var value=EtfComponentPanel.stockReturns(date,List.of(source)).getFirst();
        if(value.return1()!=15./13-1 || value.return5()!=.5)throw new AssertionError("Observed row lag");
        var missing=EtfComponentPanel.stockReturns(date.minusDays(4),List.of(source)).getFirst();
        if(!Double.isNaN(missing.return1()) || !Double.isNaN(missing.return5()))throw new AssertionError("No fill");
        if(!EtfComponentPanel.stockReturns(date.minusDays(2),List.of(source)).isEmpty())throw new AssertionError("No fabricated date");
        if(close[0]!=10 || !Double.isNaN(close[2]))throw new AssertionError("Mutated source");
        System.out.println("{\"checks\":6,\"failures\":0}");
    }
}
