package com.zoutrankil.market.us.app;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zoutrankil.data.marketdata.*;
import com.zoutrankil.market.us.marketdata.FredMarketDaily;
import org.springframework.core.env.Environment;
import java.net.*;
import java.nio.file.*;
import java.nio.channels.FileChannel;
import java.time.*;
import java.util.*;

/** 既有指数表只补缺；保留源返回的缺值统计，并逐字段证明旧行未改变。 */
final class FredMacroCollector {
    private static final ObjectMapper JSON=new ObjectMapper();
    static void collect(Environment env,Path work,VerifiedMarketDataStore store,LocalDate through)throws Exception {
        Path root=work.toAbsolutePath().normalize();Files.createDirectories(root);
        try(var channel=FileChannel.open(root.resolve("fred-collector.lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE);var lock=channel.tryLock()) {
            if(lock==null)throw new IllegalStateException("已有指数采集器运行");String key=env.getProperty("app.market-data.fred-key","");if(key.isBlank())throw new IllegalStateException("本机 FRED key 缺失");
            LocalDate from=LocalDate.parse(env.getProperty("app.market-data.history-start","2006-01-01"));var d=FredMarketDaily.DEFINITION;store.requireNoUnresolved(d.datasetId());store.ensure(d);
            var policy=new PersistentMarketApiBudget.Policy(60,1000,1000,1024L*1024*1024,1000);String account=VerifiedMarketDataStore.sha(key.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            // FRED 可单独指定本机代理，不改变其他来源及数据库的连接。
            String proxy=env.getProperty("app.market-data.fred-proxy-url","");if(proxy.isBlank())proxy=env.getProperty("app.market-data.proxy-url","");
            try(var budget=new PersistentMarketApiBudget(root.resolve("api-budget.sqlite3"),Map.of("fred",policy));var source=new FrozenHttpSource(root,budget,proxy)) {
                for(String series:List.of("NASDAQCOM","SP500","VIXCLS")) {
                    store.requireNoUnresolved(d.datasetId());URI uri=URI.create("https://api.stlouisfed.org/fred/series/observations?series_id="+series+"&observation_start="+from+"&observation_end="+through+"&file_type=json&limit=100000&api_key="+URLEncoder.encode(key,java.nio.charset.StandardCharsets.UTF_8));
                    // FRED 边缘节点对默认 HTTP/2 和应用 UA 实测重置；使用已验证的 HTTP/1.1 兼容请求头。
                    var headers=Map.of("User-Agent",env.getProperty("app.market-data.fred-user-agent","python-requests/2.32.5"),"Accept","*/*","Accept-Encoding","identity");
                    var response=source.get("fred",account,series,uri,headers,4*1024*1024,java.net.http.HttpClient.Version.HTTP_1_1);var parsed=FredMarketDaily.parse(Files.readAllBytes(response.path()),series,response.observedAt(),from,through);
                    var before=existing(store,series,from,through);var codec=VerifiedMarketDataStore.codec(d);var old=new LinkedHashMap<String,Map<String,Object>>();for(var row:before)if(old.put(codec.key(row),row)!=null)throw new IllegalStateException("原指数表有重复业务键");
                    var missing=parsed.rows().stream().map(r->VerifiedMarketDataStore.normalize(d,r)).filter(r->!old.containsKey(codec.key(r))).toList();
                    Path folder=root.resolve("macro-fill/"+UUID.randomUUID());Files.createDirectories(folder);Files.write(folder.resolve("before.json"),JSON.writeValueAsBytes(Map.of("series",series,"rows",before,"sourceSha256",response.sha256())),StandardOpenOption.CREATE_NEW);
                    Object result=missing.isEmpty()?Map.of("state","NO_MISSING_KEYS"):store.publishMissing(d,missing,response.path(),response.sha256(),Map.of("series",series,"sourceUrl",response.sourceUrl(),"sourceObservations",parsed.observations(),"sourceMissingValues",parsed.missingValues(),"before",folder.resolve("before.json").toString(),"mode","只补缺失业务键，旧行全部保留"));
                    var after=existing(store,series,from,through);var current=new HashMap<String,Map<String,Object>>();for(var row:after)if(current.put(codec.key(row),row)!=null)throw new IllegalStateException("补缺后指数表有重复业务键");
                    for(var entry:old.entrySet())if(!current.containsKey(entry.getKey())||!Arrays.equals(codec.canonicalBytes(entry.getValue()),codec.canonicalBytes(current.get(entry.getKey()))))throw new IllegalStateException("原指数行发生变化，停止验收");
                    for(var row:missing)if(!current.containsKey(codec.key(row))||!Arrays.equals(codec.canonicalBytes(row),codec.canonicalBytes(current.get(codec.key(row)))))throw new IllegalStateException("指数补缺未完整可见");
                    if(!store.settled(d.objectName()))throw new IllegalStateException("指数表 WAL 未结清");
                    var receipt=Map.of("series",series,"result",result,"sourceObservations",parsed.observations(),"sourceMissingValues",parsed.missingValues(),"newRows",missing.size(),"preservedRows",before.size(),"oldRowsUnchanged",true,"walSettled",true,"after",after);
                    Files.write(folder.resolve("receipt.json"),JSON.writeValueAsBytes(receipt),StandardOpenOption.CREATE_NEW);System.out.println(JSON.writeValueAsString(Map.of("series",series,"newRows",missing.size(),"preservedRows",before.size(),"missingValues",parsed.missingValues(),"receipt",folder.resolve("receipt.json").toString())));
                }
            }
        }
    }
    private static List<Map<String,Object>> existing(VerifiedMarketDataStore store,String series,LocalDate from,LocalDate through)throws Exception {
        return store.query("SELECT * FROM us_market_daily WHERE ts_code="+VerifiedMarketDataStore.literal(series+".US")+" AND trade_date >= "+VerifiedMarketDataStore.literal(from.toString())+" AND trade_date <= "+VerifiedMarketDataStore.literal(through.toString())+" LIMIT 100001").stream().map(r->VerifiedMarketDataStore.normalize(FredMarketDaily.DEFINITION,r)).toList();
    }
}
