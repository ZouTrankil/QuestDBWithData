import com.zoutrankil.data.factor.engine.*;
import java.time.LocalDate;
import java.util.*;
public class EtfSnapshotCheck {
    static EtfConstituentWeights.Input row(String etf,String stock,String ann,String end) {
        return new EtfConstituentWeights.Input(etf,stock,1,1,ann==null?null:LocalDate.parse(ann),end==null?null:LocalDate.parse(end));
    }
    public static void main(String[] args) {
        var source=List.of(row("E.SH","OLD","2026-09-29","2026-03-31"),row("E.SH","B","2026-08-01","2026-06-30"),
            row("E.SH","A","2026-08-01","2026-06-30"),row("E.SH","FUTURE","2026-10-01","2026-09-30"),
            row("E.SH","NULL",null,"2026-06-30"),row("E.BJ","X","2026-08-01","2026-06-30"),row("Z.SZ","Z","2026-09-30","2026-06-30"));
        var result=EtfConstituentSnapshot.select(source,LocalDate.parse("2026-09-30"));
        if(!result.stream().map(EtfConstituentWeights.Input::stock).toList().equals(List.of("A","B","Z")))throw new AssertionError(result);
        if(!EtfConstituentSnapshot.select(source,LocalDate.parse("2026-07-01")).isEmpty())throw new AssertionError("Future disclosure admitted");
        System.out.println("{\"selectedRows\":3,\"boundaryCases\":6,\"failures\":0}");
    }
}
