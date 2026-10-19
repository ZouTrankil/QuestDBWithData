package com.zoutrankil.market.us.marketdata;

import com.fasterxml.jackson.databind.*;
import java.io.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.zip.*;

/** 更多来源共用冻结原文，按年度有界投影；不会一次构建二十年全部证券的行对象。 */
public final class UsYahooAnnualBundle {
    private UsYahooAnnualBundle() {}
    private static final ObjectMapper JSON=new ObjectMapper();
    public static final int MAX_MEMBERS=128,MAX_BYTES=64*1024*1024,MANIFEST_BYTES=128*1024;
    public record Page(int rows,int bytes) {
        public Page {if(rows<1||rows>10000||bytes<1||bytes>8*1024*1024)throw new IllegalArgumentException("年度来源组的发送页超限");}
    }
    public record Window(int number,LocalDate from,LocalDate through,int expectedRows) {}
    public record Parsed(String bundle,LocalDate from,LocalDate through,Page page,List<UsYahooDailyBundle.Source> sources,List<Window> windows,int totalRows) {
        public Parsed {sources=List.copyOf(sources);windows=List.copyOf(windows);}
        public List<Map<String,Object>> rows(Window window)throws Exception {
            if(window.number()<0||window.number()>=windows.size()||!windows.get(window.number()).equals(window))throw new IllegalArgumentException("年度窗口不属于原清单");
            var rows=new ArrayList<Map<String,Object>>();
            for(var s:sources)rows.addAll(YahooPriceMapper.dailyWindow(s.body(),s.symbol(),s.yahooSymbol(),s.sha256(),s.observedAt(),window.from(),window.through()));
            if(rows.size()!=window.expectedRows()||rows.size()>100000)throw new IllegalStateException("年度来源行数与原清单不符或超过正式十万行上限");
            rows.sort(Comparator.comparing((Map<String,Object> r)->r.get("trade_date").toString()).thenComparing(r->r.get("symbol").toString()));return List.copyOf(rows);
        }
    }
    public static byte[] freeze(String bundle,LocalDate from,LocalDate through,Page page,List<UsYahooDailyBundle.Source> sources)throws Exception {
        validateRange(bundle,from,through);if(sources.isEmpty()||sources.size()>MAX_MEMBERS)throw new IllegalArgumentException("年度来源组须为 1 至 128 个响应");
        var counts=counts(sources,from,through);var members=new ArrayList<Map<String,Object>>();
        for(int i=0;i<sources.size();i++){var s=sources.get(i);members.add(Map.of("task",s.task(),"symbol",s.symbol(),"yahooSymbol",s.yahooSymbol(),"sourceUrl",s.sourceUrl(),"sha256",s.sha256(),"observedAt",s.observedAt().toString(),"entry",entry(i)));}
        byte[] manifest=JSON.writeValueAsBytes(Map.of("version",2,"bundle",bundle,"from",from.toString(),"through",through.toString(),"page",page,"members",members,"windows",windowCards(windows(from,through,counts))));
        if(manifest.length>MANIFEST_BYTES)throw new IllegalArgumentException("年度来源清单超过 128 KiB");
        var bytes=new ByteArrayOutputStream();try(var zip=new ZipOutputStream(bytes,StandardCharsets.UTF_8)){put(zip,"manifest.json",manifest);for(int i=0;i<sources.size();i++)put(zip,entry(i),sources.get(i).body());}
        if(bytes.size()>MAX_BYTES)throw new IllegalArgumentException("年度来源 ZIP 超过 64 MiB");return bytes.toByteArray();
    }
    public static Parsed parse(byte[] frozen)throws Exception {
        if(frozen.length>MAX_BYTES)throw new IllegalArgumentException("年度来源 ZIP 超限");var entries=new HashMap<String,byte[]>();long bytes=0;
        try(var zip=new ZipInputStream(new ByteArrayInputStream(frozen),StandardCharsets.UTF_8)) {
            for(var e=zip.getNextEntry();e!=null;e=zip.getNextEntry()) {
                if(e.isDirectory()||!(e.getName().equals("manifest.json")||e.getName().matches("[0-9]{4}\\.response"))||entries.size()>=MAX_MEMBERS+1)throw new IllegalArgumentException("年度来源成员非法或超限");
                int limit=e.getName().equals("manifest.json")?MANIFEST_BYTES:4*1024*1024;byte[] body=zip.readNBytes(limit+1);bytes+=body.length;
                if(body.length>limit||bytes>MAX_BYTES||entries.put(e.getName(),body)!=null)throw new IllegalArgumentException("年度来源重复或解压内容超限");
            }
        }
        byte[] manifest=entries.remove("manifest.json");if(manifest==null)throw new IllegalArgumentException("年度来源缺少清单");var card=JSON.readTree(manifest);
        if(!card.path("version").isIntegralNumber()||card.path("version").asInt()!=2)throw new IllegalArgumentException("年度来源版本非法");
        String bundle=card.path("bundle").asText();var from=LocalDate.parse(card.path("from").asText());var through=LocalDate.parse(card.path("through").asText());validateRange(bundle,from,through);
        if(!card.path("page").path("rows").isIntegralNumber()||!card.path("page").path("rows").canConvertToInt()||!card.path("page").path("bytes").isIntegralNumber()||!card.path("page").path("bytes").canConvertToInt())throw new IllegalArgumentException("年度发送页必须是整数");
        var page=new Page(card.path("page").path("rows").asInt(),card.path("page").path("bytes").asInt());var refs=card.path("members");
        if(!refs.isArray()||refs.isEmpty()||refs.size()>MAX_MEMBERS||refs.size()!=entries.size())throw new IllegalArgumentException("年度清单没有覆盖全部来源");
        var sources=new ArrayList<UsYahooDailyBundle.Source>();
        for(int i=0;i<refs.size();i++) {
            var r=refs.get(i);String name=r.path("entry").asText();byte[] body=entries.remove(name);if(!name.equals(entry(i))||body==null)throw new IllegalArgumentException("年度来源顺序或文件缺失");
            sources.add(new UsYahooDailyBundle.Source(r.path("task").asText(),r.path("symbol").asText(),r.path("yahooSymbol").asText(),r.path("sourceUrl").asText(),r.path("sha256").asText(),Instant.parse(r.path("observedAt").asText()),body));
        }
        // 重算完整来源的年度计数，不能通过删掉某年或写零行清单掩盖缺失。
        var counts=counts(sources,from,through);var windows=windows(from,through,counts);
        if(!JSON.valueToTree(windowCards(windows)).equals(card.path("windows"))||!entries.isEmpty())throw new IllegalArgumentException("年度窗口漏段、越界或行数与完整来源不符");
        return new Parsed(bundle,from,through,page,List.copyOf(sources),windows,counts.values().stream().mapToInt(Integer::intValue).sum());
    }
    public static boolean annual(byte[] frozen)throws Exception {
        try(var zip=new ZipInputStream(new ByteArrayInputStream(frozen),StandardCharsets.UTF_8)) {
            var e=zip.getNextEntry();if(e==null||!e.getName().equals("manifest.json"))throw new IllegalArgumentException("来源 ZIP 首项须为清单");
            byte[] body=zip.readNBytes(MANIFEST_BYTES+1);if(body.length>MANIFEST_BYTES)throw new IllegalArgumentException("来源清单超限");return JSON.readTree(body).path("version").asInt()==2;
        }
    }
    private static SortedMap<Integer,Integer> counts(List<UsYahooDailyBundle.Source> sources,LocalDate from,LocalDate through)throws Exception {
        var counts=new TreeMap<Integer,Integer>();var tasks=new HashSet<String>();var symbols=new HashSet<String>();long bytes=MANIFEST_BYTES;
        for(var s:sources) {
            byte[] body=s.body();bytes+=body.length;var uri=URI.create(s.sourceUrl());String task=sha(("v1|DAILY|"+s.symbol()+"|"+through+"|").getBytes(StandardCharsets.UTF_8));
            if(body.length>4*1024*1024||bytes>MAX_BYTES||s.observedAt().getNano()%1000!=0||!sha(body).equals(s.sha256())||!task.equals(s.task())||!tasks.add(task)||!symbols.add(s.symbol())||!s.symbol().matches("[A-Z0-9.-]{1,32}")||!s.yahooSymbol().matches("[A-Z0-9-]{1,32}")
                    ||!"https".equals(uri.getScheme())||!"query1.finance.yahoo.com".equals(uri.getHost())||uri.getUserInfo()!=null||uri.getPort()!=-1||!uri.getPath().equals("/v8/finance/chart/"+s.yahooSymbol()))throw new IllegalArgumentException("年度来源摘要、原队列、映射或来源地址不符");
            for(var row:YahooPriceMapper.daily(body,s.symbol(),s.yahooSymbol(),s.sha256(),s.observedAt(),from,through)) {
                if(row.values().stream().anyMatch(v->v instanceof Double n&&!Double.isFinite(n)))throw new IllegalArgumentException("年度完整来源含非有限派生数值");
                counts.merge(Instant.parse(row.get("trade_date").toString()).atOffset(ZoneOffset.UTC).getYear(),1,Integer::sum);
            }
        }return counts;
    }
    private static List<Window> windows(LocalDate from,LocalDate through,Map<Integer,Integer> counts) {
        var windows=new ArrayList<Window>();for(int year=from.getYear();year<=through.getYear();year++) {
            var start=LocalDate.of(year,1,1);var end=LocalDate.of(year,12,31);int rows=counts.getOrDefault(year,0);if(rows>100000)throw new IllegalArgumentException("年度窗口超过十万行");
            windows.add(new Window(windows.size(),start.isBefore(from)?from:start,end.isAfter(through)?through:end,rows));
        }return List.copyOf(windows);
    }
    private static void validateRange(String bundle,LocalDate from,LocalDate through) {if(!bundle.matches("broad-[a-f0-9-]{36}")||through.isBefore(from)||through.getYear()-from.getYear()>=64)throw new IllegalArgumentException("年度组身份或日期范围非法，最多 64 年");}
    private static List<Map<String,Object>> windowCards(List<Window> windows){return windows.stream().map(w->Map.<String,Object>of("number",w.number(),"from",w.from().toString(),"through",w.through().toString(),"expectedRows",w.expectedRows())).toList();}
    private static String entry(int i){return String.format(Locale.ROOT,"%04d.response",i);}
    private static void put(ZipOutputStream out,String name,byte[] body)throws IOException {var e=new ZipEntry(name);e.setTime(0);out.putNextEntry(e);out.write(body);out.closeEntry();}
    private static String sha(byte[] bytes)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
}
