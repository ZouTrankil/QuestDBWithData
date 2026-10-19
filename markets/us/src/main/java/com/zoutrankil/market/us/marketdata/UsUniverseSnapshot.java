package com.zoutrankil.market.us.marketdata;

import com.zoutrankil.data.domain.DatasetDefinition;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import static com.zoutrankil.data.domain.DatasetDefinition.*;

/** 官方当日挂牌范围是当前证券快照；不把当前列表当成二十年的历史成分表。 */
public final class UsUniverseSnapshot {
    private UsUniverseSnapshot() {}
    public static final DatasetDefinition SECURITIES=new DatasetDefinition("us_security_universe",1,"nasdaqtrader.symbol_directory","java.market.us","us_security_universe",ObjectKind.TABLE,List.of(
            field("observed_at",StorageType.TIMESTAMP,false,"实际取得官方列表的 UTC 时间"),field("symbol",StorageType.SYMBOL,false,"官方原始挂牌证券代码"),
            field("directory",StorageType.SYMBOL,false,"NASDAQ_LISTED 或 OTHER_LISTED"),field("security_name",StorageType.VARCHAR,false,"官方证券名称"),field("exchange",StorageType.SYMBOL,false,"挂牌场所代码"),
            field("is_etf",StorageType.BOOLEAN,false,"官方 ETF 标识"),field("is_test",StorageType.BOOLEAN,false,"官方测试证券标识"),
            field("daily_candidate",StorageType.BOOLEAN,false,"是否纳入股票日线来源请求，不能解释为已经取得历史"),field("scope_reason",StorageType.SYMBOL,false,"候选或排除原因，便于复核证券范围"),
            field("yahoo_symbol",StorageType.SYMBOL,true,"明确的 Yahoo 请求代码；类别股点号按 Yahoo 连字符规范映射"),field("source_sha256",StorageType.SYMBOL,false,"官方范围原文 SHA-256")),
            List.of("observed_at","symbol","directory"),List.of("observed_at","symbol","directory"),"observed_at",Partition.YEAR,true,Set.of(Capability.READ,Capability.WRITE),List.of(),"当前官方挂牌清单全量保留，包括明确排除的 ETF、测试和非普通股票；历史退市范围另需供应商历史证券表，不能混为一谈。");
    public static List<Map<String,Object>> rows(byte[] bytes,boolean nasdaq,String sha,Instant observed) {
        String[] lines=new String(bytes,StandardCharsets.UTF_8).split("\\R");var headings=Arrays.asList(lines[0].split("\\|",-1));
        var expected=nasdaq?List.of("Symbol","Security Name","Market Category","Test Issue","Financial Status","Round Lot Size","ETF","NextShares"):
                List.of("ACT Symbol","Security Name","Exchange","CQS Symbol","ETF","Round Lot Size","Test Issue","NASDAQ Symbol");
        if(!headings.equals(expected)||!lines[lines.length-1].startsWith("File Creation Time:"))throw new IllegalArgumentException("官方证券列表表头或完整文件尾不符");
        var rows=new ArrayList<Map<String,Object>>();var seen=new HashSet<String>();
        for(int i=1;i<lines.length-1;i++) {
            var cells=lines[i].split("\\|",-1);if(cells.length!=headings.size())throw new IllegalArgumentException("官方证券列表字段数量不符");var record=new HashMap<String,String>();for(int j=0;j<cells.length;j++)record.put(headings.get(j),cells[j]);
            String symbol=record.get(nasdaq?"Symbol":"ACT Symbol"),name=record.get("Security Name");if(symbol.isBlank()||name.isBlank()||!seen.add(symbol))throw new IllegalArgumentException("官方挂牌代码缺失或重复");
            boolean etf=flag(record.get("ETF")),test=flag(record.get("Test Issue"));String upper=name.toUpperCase(Locale.ROOT);
            // 单数 Right 也可能是证券类型；只补认末尾类型词，不能误伤 Bright/Wright 或 ADR 的解释文字。
            boolean other=upper.matches(".*(WARRANT|RIGHTS|UNITS| UNIT$|PREFERRED|PREFERENCE|DEBENTURE| NOTES| BONDS).*")||upper.endsWith(" RIGHT"),mapping=symbol.matches("[A-Z0-9]+(?:[.-][A-Z0-9]+)?");
            boolean candidate=!etf&&!test&&!other&&mapping;String reason=test?"TEST_SECURITY":etf?"ETF_SEPARATE_SCOPE":other?"NON_COMMON_SECURITY":!mapping?"SYMBOL_MAPPING_REQUIRED":"EQUITY_CANDIDATE";
            var row=new LinkedHashMap<String,Object>();row.put("observed_at",observed.toString());row.put("symbol",symbol);row.put("directory",nasdaq?"NASDAQ_LISTED":"OTHER_LISTED");row.put("security_name",name);row.put("exchange",nasdaq?"NASDAQ":record.get("Exchange"));
            row.put("is_etf",etf);row.put("is_test",test);row.put("daily_candidate",candidate);row.put("scope_reason",reason);row.put("yahoo_symbol",mapping?symbol.replace('.','-'):null);row.put("source_sha256",sha);rows.add(row);
        }if(rows.size()<1000)throw new IllegalArgumentException("官方挂牌范围异常偏小");return rows;
    }
    private static boolean flag(String value){if(!Set.of("Y","N").contains(value))throw new IllegalArgumentException("官方挂牌标识非法");return value.equals("Y");}
    private static Column field(String name,StorageType type,boolean nullable,String meaning){return new Column(name,name,name,type,nullable,meaning,type==StorageType.TIMESTAMP?new TemporalContract(TemporalKind.INSTANT,"ISO-8601","UTC","MICROS",meaning):null);}
}
