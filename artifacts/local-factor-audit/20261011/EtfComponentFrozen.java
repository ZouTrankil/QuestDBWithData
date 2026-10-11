import com.zoutrankil.data.factor.engine.*;
import com.zoutrankil.data.domain.JobDefinitionJson;
import java.nio.file.*;
import java.time.LocalDate;
import java.util.*;
public class EtfComponentFrozen {
    public static void main(String[] args)throws Exception {
        var mapper=JobDefinitionJson.mapper();var root=Path.of(args[0]);var members=new ArrayList<EtfConstituentWeights.Member>();
        for(var r:mapper.readTree(root.resolve("etf-constituents-live.json").toFile()).get("members"))
            members.add(new EtfConstituentWeights.Member(r.get("etf").asText(),r.get("stock").asText(),r.get("weight").asDouble(),LocalDate.parse(r.get("announced").asText()),LocalDate.parse(r.get("periodEnd").asText())));
        var stocks=new ArrayList<EtfComponentPanel.Stock>();
        for(var r:mapper.readTree(root.resolve("full-panel-java-values-r3.json").toFile()))
            stocks.add(new EtfComponentPanel.Stock(r.get("ts_code").asText(),r.get("ret_1").isNull()?Double.NaN:r.get("ret_1").asDouble(),r.get("ret_5").isNull()?Double.NaN:r.get("ret_5").asDouble()));
        var l2=new ArrayList<EtfComponentPanel.L2>();
        if(args.length>1)for(var r:mapper.readTree(root.resolve("etf-component-l2-source.json").toFile()).get("rows")) {
            double[] v=new double[7];String[] names={"main_net_inflow","mfi_score","obi_top1_mean","mean_obi_top5","ofi_mean","mean_spread","depth_top5_mean"};
            for(int i=0;i<v.length;i++)v[i]=r.get(names[i]).isNull()?Double.NaN:r.get(names[i]).asDouble();
            l2.add(new EtfComponentPanel.L2(r.get("symbol").asText(),v[0],v[1],v[2],v[3],v[4],v[5],v[6]));
        }
        var result=EtfComponentPanel.compute(LocalDate.parse("2026-09-30"),members,stocks,l2,args.length>1);
        Files.write(root.resolve(args.length>1?"etf-component-l2-frozen.json":"etf-component-frozen.json"),mapper.writeValueAsBytes(result));
        System.out.println("ETF sections: "+result.size());
    }
}
