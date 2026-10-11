import com.zoutrankil.data.factor.engine.EtfSelfL2;
import java.time.LocalDate;
import java.nio.file.*;
import java.util.*;
public class EtfSelfL2Parity {
    public static void main(String[] args) throws Exception {
        var a=LocalDate.parse("2026-09-29");var b=a.plusDays(1);
        var market=List.of(new EtfSelfL2.Market(a,"E0","old","A"),new EtfSelfL2.Market(a,"E0","new","B"),new EtfSelfL2.Market(b,"E1","one","C"));
        var source=List.of(new EtfSelfL2.Input(a,"E0",1,2,3,4),new EtfSelfL2.Input(a,"E0",5,6,7,8),new EtfSelfL2.Input(b,"E0",9,10,11,12),new EtfSelfL2.Input(b,"X",13,14,15,16));
        var actual=new ArrayList<String>();
        for(String name:List.of("all","empty","selected")) {
            Set<LocalDate> dates=name.equals("all")?null:name.equals("empty")?Set.of():Set.of(b);
            for(var r:EtfSelfL2.compute(source,market,dates))actual.add(String.join("\t",name,r.date().toString(),r.symbol(),r.name()==null?"":r.name(),r.category()==null?"":r.category(),Double.toString(r.obi1()),Double.toString(r.ofi()),Double.toString(r.spread()),Double.toString(r.depth())));
        }
        if(!actual.equals(Files.readAllLines(Path.of(args[0]))))throw new AssertionError("Python parity mismatch");
        if(!EtfSelfL2.compute(source,List.of(),null).isEmpty())throw new AssertionError("Empty market");
        System.out.println("{\"rows\":"+actual.size()+",\"mismatches\":0,\"emptyMarketChecked\":true}");
    }
}
