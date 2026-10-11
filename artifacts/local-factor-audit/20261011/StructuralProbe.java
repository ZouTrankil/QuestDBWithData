import com.zoutrankil.data.factor.engine.StructuralFinancialFeatures;
import java.time.*;import java.util.*;
public class StructuralProbe {
 static StructuralFinancialFeatures.Statement row(String p,String a,Map<String,Double> v){return new StructuralFinancialFeatures.Statement(LocalDate.parse(p),LocalDate.parse(a),v);}
 static Map<String,Double> inc(double n){return Map.of("revenue",n*4,"oper_cost",n*2,"operate_profit",n,"n_income_attr_p",n);}
 static Map<String,Double> cash(double n){return Map.of("n_cashflow_act",n,"c_pay_acq_const_fiolta",n/2);}
 public static void main(String[] args){
 var i=List.of(row("2025-06-30","2025-08-01",inc(20)),row("2025-12-31","2026-04-01",inc(50)),row("2026-06-30","2026-08-01",inc(30)),row("2026-06-30","2026-09-02",inc(40)));
 var c=List.of(row("2025-06-30","2025-08-01",cash(10)),row("2025-12-31","2026-04-01",cash(30)),row("2026-06-30","2026-08-01",cash(20)));
 var b=List.of(row("2025-06-30","2025-08-01",Map.of("total_assets",100.,"money_cap",10.,"total_liab",50.)),row("2026-06-30","2026-08-01",Map.of("total_assets",120.,"money_cap",20.,"total_liab",60.)));
 var dates=new LocalDate[]{LocalDate.parse("2026-09-01"),LocalDate.parse("2026-09-02"),LocalDate.parse("2026-09-03")};var r=StructuralFinancialFeatures.compute(dates,new double[]{1,1,2},i,b,c);
 if(Math.abs(r.get("quality_operating_profitability")[0]-.5)>1e-12 || Math.abs(r.get("quality_operating_profitability")[1]-70./120)>1e-12)throw new AssertionError("revision/TTM");
 if(Math.abs(r.get("value_cfp")[0]-.004)>1e-12 || Math.abs(r.get("investment_asset_growth")[0]-.2)>1e-12 || r.get("investment_noa_growth")[0]!=0)throw new AssertionError("formula");
 if(r.get("quality_operating_profitability")[2]!=r.get("quality_operating_profitability")[1] || r.get("value_cfp")[2]!=.002)throw new AssertionError("unchanged statements cache / changed market value");
 var missing=StructuralFinancialFeatures.compute(dates,new double[]{1,1,2},List.of(i.get(2)),b,c);
 if(!Double.isNaN(missing.get("quality_operating_profitability")[0]))throw new AssertionError("missing prior year");
 var absent=StructuralFinancialFeatures.compute(dates,new double[]{1,1,2},i,List.of(),c);
 if(absent.get(StructuralFinancialFeatures.ELIGIBLE_COLUMN)[0]!=0)throw new AssertionError("absent report eligible");
 var empty=StructuralFinancialFeatures.compute(dates,new double[]{1,1,2},i,List.of(row("2026-06-30","2026-08-01",Map.of())),c);
 if(empty.get(StructuralFinancialFeatures.ELIGIBLE_COLUMN)[0]!=1 || !Double.isNaN(empty.get("quality_accruals")[0]))throw new AssertionError("missing fields confused with absent report");
 System.out.println("PASS: PIT revision boundary, TTM, market-value units, asset/NOA growth, missing history");
 }
}
