import com.zoutrankil.data.factor.engine.*;
import java.time.LocalDate;
import java.util.*;
public class EtfCsiAggregationCheck {
    public static void main(String[] args) {
        var date=LocalDate.parse("2026-09-30");var members=new ArrayList<EtfConstituentWeights.Member>();
        for(int i=0;i<3;i++){double weight=new double[]{.59,.60,.61}[i];members.add(new EtfConstituentWeights.Member("E"+i,"A",weight,date,date));members.add(new EtfConstituentWeights.Member("E"+i,"B",1-weight,date,date));}
        var factors=new HashMap<String,Double>();EtfCsiAggregation.FACTORS.forEach(f->factors.put(f,2.));
        var rows=EtfCsiAggregation.compute(date,List.of(new EtfCsiAggregation.Stock("A",factors)),members);
        if(rows.size()!=24)throw new AssertionError("Factor grid");
        for(var row:rows){if(row.componentCount()!=2)throw new AssertionError("Count");if(row.etf().equals("E0")?!Double.isNaN(row.value()):row.value()!=2)throw new AssertionError("Coverage threshold");}
        if(!EtfCsiAggregation.compute(date,List.of(),members).isEmpty())throw new AssertionError("Empty stocks");
        System.out.println("{\"rows\":24,\"coverageThresholds\":[0.59,0.60,0.61],\"failures\":0}");
    }
}
