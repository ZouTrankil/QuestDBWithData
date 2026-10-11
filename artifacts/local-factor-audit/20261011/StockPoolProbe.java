import com.zoutrankil.data.factor.engine.PointInTimeStockPools;
import java.nio.file.*;
import java.time.*;
import java.util.*;
public class StockPoolProbe {
    public static void main(String[] args) throws Exception {
        var stocks = new ArrayList<PointInTimeStockPools.Stock>();
        for (String line : Files.readAllLines(Path.of(args[0]))) {
            var c=line.split("\t",-1);
            stocks.add(new PointInTimeStockPools.Stock(c[0],Double.parseDouble(c[1]),Integer.parseInt(c[2]),
                    Double.parseDouble(c[3]),Double.parseDouble(c[4]),Boolean.parseBoolean(c[5])));
        }
        var indices = new ArrayList<PointInTimeStockPools.IndexMember>();
        for (String line : Files.readAllLines(Path.of(args[1]))) {
            var c=line.split("\t",-1);
            indices.add(new PointInTimeStockPools.IndexMember(c[0],LocalDate.parse(c[1]),c[2]));
        }
        var pools = new PointInTimeStockPools.Index(indices).resolve(LocalDate.parse(args[3]),stocks,
                PointInTimeStockPools.DEFINITIONS.stream().map(PointInTimeStockPools.Definition::id).toList());
        var output = new ArrayList<String>();
        for (var pool:pools) {
            output.add("STATUS\t"+pool.poolId()+"\t"+pool.available()+"\t"+pool.membershipAsOf()+"\t"+pool.unavailableReason());
            for(var m:pool.members())output.add("MEMBER\t"+pool.poolId()+"\t"+m.symbol()+"\t"+m.included()+"\t"+m.parentPercentile()+"\t"+String.join(",",m.reasons()));
            if(pool.available() && pool.asPool().memberIndices().length != pool.members().stream().filter(PointInTimeStockPools.Member::included).count())
                throw new AssertionError("pool projection");
        }
        Files.write(Path.of(args[2]),output);
    }
}
