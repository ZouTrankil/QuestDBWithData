import com.zoutrankil.data.factor.engine.*;
import java.time.LocalDate;
import java.util.*;
public class EtfConstituentPipelineCheck {
    public static void main(String[] args) {
        var date=LocalDate.parse("2026-09-30");var yesterday=date.minusDays(1);
        var etfs=EtfMarketInputs.prepare(List.of(new EtfMarketInputs.Bar("E",yesterday,1,10),new EtfMarketInputs.Bar("E",date,2,10)),List.of());
        var stock=new DailyFactorPanel.Security("S",new LocalDate[]{yesterday,date},Map.of("close",new double[]{1,2}),null);
        var member=new EtfConstituentWeights.Member("E","S",1,yesterday,yesterday);
        var result=EtfConstituentPipeline.compute(etfs,Map.of(),List.of(stock),Map.of(date,List.of(member)),Map.of(),false);
        var values=result.market().securities().getFirst().columns().get("component_up_breadth_1d");
        if(result.components().size()!=1 || !Double.isNaN(values[0]) || values[1]!=1)throw new AssertionError("Date alignment");
        if(result.components().getFirst().columns().containsKey("basket_ofi"))throw new AssertionError("Absent L2");
        if(result.market().select(date,Set.of("E")).size()!=1)throw new AssertionError("Pool projection");
        System.out.println("{\"checks\":5,\"failures\":0}");
    }
}
