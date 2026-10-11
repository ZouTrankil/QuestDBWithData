import com.zoutrankil.data.factor.engine.*;
import java.time.LocalDate;
import java.util.*;
public class EtfComponentPanelCheck {
    public static void main(String[] args) {
        var date=LocalDate.parse("2026-09-30");
        var members=List.of(new EtfConstituentWeights.Member("E0","S0",.4,date,date),new EtfConstituentWeights.Member("E0","S1",.6,date,date),new EtfConstituentWeights.Member("E1","S0",1,date,date),new EtfConstituentWeights.Member("E2","S2",1,date,date));
        var stocks=List.of(new EtfComponentPanel.Stock("S0",.1,.2));
        var l2=List.of(new EtfComponentPanel.L2("S1",10,20,30,40,50,60,70));
        var result=EtfComponentPanel.compute(date,members,stocks,l2,true);
        if(result.size()!=2)throw new AssertionError("Unobserved ETF included");
        var a=result.getFirst().columns();
        if(a.get("component_up_breadth_1d")!=1 || a.get("component_top10_weight")!=1 || a.get("basket_ofi")!=50)throw new AssertionError(a);
        if(!Double.isNaN(result.get(1).columns().get("basket_ofi")))throw new AssertionError("L2 missing");
        if(EtfComponentPanel.compute(date,members,stocks,List.of(),false).getFirst().columns().containsKey("basket_ofi"))throw new AssertionError("Absent source");
        try {EtfComponentPanel.compute(date.minusDays(1),members,stocks,l2,true);throw new AssertionError("Future announcement");}catch(IllegalArgumentException expected){}
        System.out.println("{\"checks\":6,\"failures\":0}");
    }
}
