import com.zoutrankil.questdbwithdata.domain.*;
import com.zoutrankil.questdbwithdata.service.*;
import org.springframework.boot.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
class EtfShareMarketScopeOperation {
    public static void main(String[] args)throws Exception{
        var app=new SpringApplication(local.market.EtfShareOperationApplication.class);app.setWebApplicationType(WebApplicationType.NONE);app.setLogStartupInfo(false);
        String date=args.length==0?"20260928":LocalDate.parse(args[0]).format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE);
        var report=new LinkedHashMap<String,Object>();report.put("task","D016");report.put("purpose","Check unfiltered Python fund_share scope against documented SH/SZ partitions and legacy market O");report.put("formalTableMutated",false);report.put("date",date);
        var results=new ArrayList<Map<String,Object>>();report.put("probes",results);
        try(var context=app.run("list-sync-jobs")){
            var pages=context.getBean(TusharePageService.class);
            var contract=new PageContract("fund_share",List.of("ts_code","trade_date","fd_share","fund_type","market"),List.of("ts_code","trade_date"),Set.of("trade_date","market"),PageContract.Paging.NONE,PageContract.Completion.SHORT_PAGE,null,null,2000,2000,1,2000,"Bounded source scope diagnostic, not completion evidence");
            for(String market:List.of("UNFILTERED","O")){
                var params=new LinkedHashMap<String,Object>();params.put("trade_date",date);if(!market.equals("UNFILTERED"))params.put("market",market);
                var probe=new LinkedHashMap<String,Object>();results.add(probe);probe.put("parameters",params);
                try{
                    var response=pages.fetcher(contract,()->false).fetch(params);if(response.rows().size()>2000)throw new IllegalStateException("Diagnostic response exceeded cap");
                    var counts=new TreeMap<String,Integer>();var samples=new ArrayList<Object>();
                    for(var row:response.rows()){
                        String value=row.get("market").isNull()?"NULL":row.get("market").asText();counts.merge(value,1,Integer::sum);
                        if(!Set.of("SH","SZ").contains(value)&&samples.size()<12)samples.add(row);
                    }
                    probe.put("returnedRows",response.rows().size());probe.put("marketCounts",counts);probe.put("nonExchangeSamples",samples);probe.put("belowCap",response.rows().size()<2000);
                }catch(Exception failure){probe.put("failureType",failure.getClass().getSimpleName());}
            }
        }finally{
            report.put("finishedAt",Instant.now().toString());Path root=Path.of("artifacts/java-migration/D016/provider-diagnostic");Files.createDirectories(root);Files.writeString(root.resolve("market-scope-"+UUID.randomUUID()+".json"),JobDefinitionJson.mapper().writerWithDefaultPrettyPrinter().writeValueAsString(report));
        }
    }
}
