import com.zoutrankil.data.factor.engine.PointInTimeFactorJoin;
import java.time.*;import java.util.*;
public class PitVectorProbe {
 public static void main(String[] args){var a=LocalDate.of(2026,1,1);var days=new LocalDate[]{a,a.plusDays(1),a.plusDays(2)};
 var src=List.of(new PointInTimeFactorJoin.VectorObservation(a.plusDays(1),new double[]{1,2}),new PointInTimeFactorJoin.VectorObservation(a.plusDays(1),new double[]{3,Double.NaN}),new PointInTimeFactorJoin.VectorObservation(a.plusDays(3),new double[]{9,9}));
 var result=PointInTimeFactorJoin.joinColumns(days,src,2);
 for(int c=0;c<2;c++){final int col=c;var one=PointInTimeFactorJoin.join(days,src.stream().map(r->new PointInTimeFactorJoin.Observation(null,r.availableDate(),r.values()[col])).toList(),false);if(!Arrays.equals(one,result[c]))throw new AssertionError();}
 if(!Double.isNaN(result[0][0]) || result[0][1]!=3 || !Double.isNaN(result[1][2]))throw new AssertionError();
 System.out.println("PASS: vector/single equivalence, same-day last row, future exclusion and missing values");}
}
