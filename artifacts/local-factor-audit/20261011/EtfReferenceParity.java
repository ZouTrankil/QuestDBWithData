import com.zoutrankil.data.factor.engine.*;
import java.nio.file.*;
import java.time.LocalDate;
import java.util.*;
public class EtfReferenceParity {
    public static void main(String[] args)throws Exception {
        int checks=0;
        for(String line:Files.readAllLines(Path.of(args[0]))) {
            var parts=line.split("\t",-1);
            if(!parts[1].equals(EtfResearchStyle.classify(parts[0])))throw new AssertionError(parts[0]);checks++;
        }
        var date=LocalDate.parse("2026-09-30");
        var bars=List.of(new EtfMarketInputs.Bar("B",date,1,1),new EtfMarketInputs.Bar("A",date,2,2),new EtfMarketInputs.Bar("A",date,3,3),new EtfMarketInputs.Bar("C",date,4,4),new EtfMarketInputs.Bar("A",null,5,5));
        var refs=List.of(new EtfMarketInputs.Reference("A","old","D"),new EtfMarketInputs.Reference("A","芯片",null),new EtfMarketInputs.Reference("A","消费","L"),new EtfMarketInputs.Reference("B","银行","D"));
        var filtered=EtfMarketInputs.prepare(bars,refs);
        if(filtered.inputs().size()!=1 || filtered.inputs().getFirst().close()[0]!=3 || !filtered.categories().get("A").equals("半导体"))throw new AssertionError("Reference filtering");
        var absent=EtfMarketInputs.prepare(bars,List.of());
        if(absent.inputs().size()!=3 || !absent.inputs().getFirst().symbol().equals("A"))throw new AssertionError("Empty reference behavior");
        if(!EtfMarketInputs.prepare(bars,List.of(new EtfMarketInputs.Reference("A","a","D"))).inputs().isEmpty())throw new AssertionError("Inactive reference");
        System.out.println("{\"styleChecks\":"+checks+",\"mismatches\":0,\"referenceBoundaryChecks\":3}");
    }
}
