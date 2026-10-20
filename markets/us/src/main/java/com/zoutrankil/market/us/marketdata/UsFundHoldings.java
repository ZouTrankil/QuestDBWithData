package com.zoutrankil.market.us.marketdata;

import com.fasterxml.jackson.databind.*;
import com.zoutrankil.data.domain.DatasetDefinition;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.zip.*;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.*;
import static com.zoutrankil.data.domain.DatasetDefinition.*;

/** 发行人持仓是当前指数范围的参照，不能冒充官方历史指数成分。 */
public final class UsFundHoldings {
    private UsFundHoldings() {}
    private static final ObjectMapper JSON=new ObjectMapper();
    public static final DatasetDefinition EQUITIES=new DatasetDefinition("us_etf_equity_holdings",1,"etf.issuer.holdings","java.market.us","us_etf_equity_holdings",ObjectKind.TABLE,List.of(
            field("asof_date",StorageType.TIMESTAMP,false,"发行人提供的持仓业务日期，UTC 零点编码",true),field("fund",StorageType.SYMBOL,false,"SPY 或 QQQ",false),
            field("symbol",StorageType.SYMBOL,false,"规范类别股代码，斜杠或连字符类后缀统一点号",false),field("issuer",StorageType.VARCHAR,false,"发行人给出的公司名称",false),
            field("security_id",StorageType.VARCHAR,false,"源 CUSIP",false),field("weight_pct",StorageType.DOUBLE,false,"基金资产百分比，不是小数权重",false),
            field("currency",StorageType.SYMBOL,false,"USD",false),field("membership_basis",StorageType.SYMBOL,false,"ETF_HOLDINGS_PROXY，不是官方历史指数成分",false),
            field("source_sha256",StorageType.SYMBOL,false,"发行人原文摘要",false),field("observed_at",StorageType.TIMESTAMP,false,"原文取得 UTC 时间",false)),
            List.of("asof_date","fund","symbol"),List.of("asof_date","fund","symbol"),"asof_date",Partition.YEAR,true,Set.of(Capability.READ,Capability.WRITE),List.of(),"基金、业务日期及证券代码完整去重；只保存普通股票及 ADR，原文仍保留现金及衍生品，以免把它们当股票指数成分。");
    public static List<Map<String,Object>> qqq(byte[] raw,String sha,Instant observed)throws Exception {
        var document=JSON.readTree(raw);var asof=LocalDate.parse(document.path("effectiveBusinessDate").asText());var holdings=document.path("holdings");
        if(!document.path("cusip").asText().equals("QQQ")||!holdings.isArray()||document.path("totalNumberOfHoldings").asInt()!=holdings.size())throw new IllegalArgumentException("QQQ 完整持仓结构不符");
        var rows=new ArrayList<Map<String,Object>>();for(var value:holdings)if(Set.of("Common Stock","American Depository Receipt","American Depository Receipt - NY").contains(value.path("securityTypeName").asText()))
            rows.add(row(asof,"QQQ",value.path("ticker").asText(),value.path("issuerName").asText(),value.path("cusip").asText(),value.path("percentageOfTotalNetAssets").asDouble(Double.NaN),value.path("currency").asText(),sha,observed));
        validate(rows,95,110);return rows;
    }
    public static List<Map<String,Object>> spy(byte[] raw,String sha,Instant observed)throws Exception {
        var entries=new HashMap<String,byte[]>();try(var zip=new ZipInputStream(new ByteArrayInputStream(raw))) {
            for(var entry=zip.getNextEntry();entry!=null;entry=zip.getNextEntry())if(Set.of("xl/sharedStrings.xml","xl/worksheets/sheet1.xml").contains(entry.getName())) {
                byte[] bytes=zip.readNBytes(16*1024*1024+1);if(bytes.length>16*1024*1024||entries.put(entry.getName(),bytes)!=null)throw new IllegalArgumentException("SPY 持仓 XML 超限或重复");
            }
        }
        var strings=new ArrayList<String>();if(entries.containsKey("xl/sharedStrings.xml")){var items=xml(entries.get("xl/sharedStrings.xml")).getElementsByTagNameNS("*","si");for(int i=0;i<items.getLength();i++)strings.add(items.item(i).getTextContent());}
        if(!entries.containsKey("xl/worksheets/sheet1.xml"))throw new IllegalArgumentException("SPY 持仓缺少首张工作表");
        var data=new TreeMap<Integer,Map<String,String>>();var sheet=xml(entries.get("xl/worksheets/sheet1.xml")).getElementsByTagNameNS("*","row");
        for(int i=0;i<sheet.getLength();i++) {
            var element=(Element)sheet.item(i);var cells=element.getElementsByTagNameNS("*","c");var row=new HashMap<String,String>();
            for(int k=0;k<cells.getLength();k++){var cell=(Element)cells.item(k);var values=cell.getElementsByTagNameNS("*","v");String text=values.getLength()==0?"":values.item(0).getTextContent();if(cell.getAttribute("t").equals("s"))text=strings.get(Integer.parseInt(text));else if(cell.getAttribute("t").equals("inlineStr"))text=cell.getTextContent();row.put(cell.getAttribute("r").replaceAll("[0-9]",""),text);}
            data.put(Integer.parseInt(element.getAttribute("r")),row);
        }
        if(!"SPY".equals(data.getOrDefault(2,Map.of()).get("B"))||!"Ticker".equals(data.getOrDefault(5,Map.of()).get("B")))throw new IllegalArgumentException("SPY 发行人表头不符");
        var dateText=data.getOrDefault(3,Map.of()).getOrDefault("B","");if(!dateText.startsWith("As of "))throw new IllegalArgumentException("SPY 持仓日期缺失");
        var asof=LocalDate.parse(dateText.substring(6),DateTimeFormatter.ofPattern("d-MMM-uuuu",Locale.ENGLISH));
        var rows=new ArrayList<Map<String,Object>>();for(var entry:data.tailMap(6).entrySet()) {
            var r=entry.getValue();String symbol=r.getOrDefault("B",""),id=r.getOrDefault("C","");
            // SSGA 股票表尾也含现金和 CVR；这些不属于普通股持仓范围，原文继续保留。
            if(symbol.isBlank()||symbol.equals("-")||id.contains("CVR")||!id.matches("[A-Z0-9]{9}")||!r.getOrDefault("H","").equals("USD"))continue;
            rows.add(row(asof,"SPY",symbol,r.getOrDefault("A",""),id,Double.parseDouble(r.get("E")),"USD",sha,observed));
        }validate(rows,500,510);return rows;
    }
    private static Document xml(byte[] raw)throws Exception {
        var factory=DocumentBuilderFactory.newInstance();factory.setNamespaceAware(true);factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);factory.setFeature("http://xml.org/sax/features/external-general-entities",false);factory.setFeature("http://xml.org/sax/features/external-parameter-entities",false);factory.setXIncludeAware(false);factory.setExpandEntityReferences(false);
        return factory.newDocumentBuilder().parse(new ByteArrayInputStream(raw));
    }
    private static Map<String,Object> row(LocalDate asof,String fund,String symbol,String issuer,String id,double weight,String currency,String sha,Instant observed) {
        String normalized=symbol.replace('/','.').replace('-','.');if(!normalized.matches("[A-Z0-9]+(?:\\.[A-Z])?")||issuer.isBlank()||!id.matches("[A-Z0-9]{9}")||!currency.equals("USD")||!Double.isFinite(weight)||weight<0||weight>20)throw new IllegalArgumentException("发行人股票持仓字段非法");
        return Map.of("asof_date",asof.atStartOfDay(ZoneOffset.UTC).toInstant().toString(),"fund",fund,"symbol",normalized,"issuer",issuer,"security_id",id,"weight_pct",weight,"currency",currency,"membership_basis","ETF_HOLDINGS_PROXY","source_sha256",sha,"observed_at",observed.toString());
    }
    private static void validate(List<Map<String,Object>> rows,int minimum,int maximum) {
        double weight=rows.stream().mapToDouble(r->((Number)r.get("weight_pct")).doubleValue()).sum();
        if(rows.size()<minimum||rows.size()>maximum||rows.stream().map(r->r.get("symbol")).distinct().count()!=rows.size()||weight<95||weight>105)throw new IllegalArgumentException("发行人股票持仓数量、去重或总权重不符");
    }
    private static Column field(String name,StorageType type,boolean nullable,String meaning,boolean date){return new Column(name,name,name,type,nullable,meaning,type==StorageType.TIMESTAMP?new TemporalContract(date?TemporalKind.BUSINESS_DATE:TemporalKind.INSTANT,"ISO-8601",date?"calendar":"UTC","MICROS",meaning):null);}
}
