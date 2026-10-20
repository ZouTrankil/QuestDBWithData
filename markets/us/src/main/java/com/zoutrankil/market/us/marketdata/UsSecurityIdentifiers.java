package com.zoutrankil.market.us.marketdata;

import com.fasterxml.jackson.databind.*;
import com.zoutrankil.data.domain.DatasetDefinition;
import java.time.*;
import java.util.*;
import static com.zoutrankil.data.domain.DatasetDefinition.*;

/** 永久代码对应独立证券；同名交易代码不能把不同发行人的价格键合并。 */
public final class UsSecurityIdentifiers {
    private UsSecurityIdentifiers() {}
    private static final ObjectMapper JSON=new ObjectMapper();
    private static final Set<String> FIELDS=Set.of("provider","security_id","price_symbol","source_ticker","security_name","catalog_exchange","asset_type","is_active","openfigi_composite","history_source_row","history_source_sha256","metadata_source_sha256","price_from","price_through","price_source_sha256","price_run_id");
    public static final DatasetDefinition IDENTIFIERS=new DatasetDefinition("us_security_identifiers",2,"market.us.external","java.market.us","us_security_identifiers",ObjectKind.TABLE,List.of(
            field("observed_at",StorageType.TIMESTAMP,"本次核对映射的 UTC 时间",false),
            field("provider",StorageType.SYMBOL,"永久代码所属供应商",false),field("security_id",StorageType.SYMBOL,"供应商永久证券代码，不等于发行人可复用的市场交易代码",false),
            field("price_symbol",StorageType.SYMBOL,"us_equity_daily 实际保存的请求代码，与 provider 一起定位价格",false),field("source_ticker",StorageType.SYMBOL,"供应商搜索记录中的市场代码；允许不同永久代码同名",false),
            field("security_name",StorageType.VARCHAR,"永久代码对应的来源证券名称",false),field("catalog_exchange",StorageType.SYMBOL,"历史目录所标挂牌场所，不等于每根价格的成交场所",false),
            field("asset_type",StorageType.SYMBOL,"来源 Stock 或 ETF 分类",false),field("is_active",StorageType.BOOLEAN,"该永久代码在来源搜索中的存续标识",false),new Column("openfigi_composite","openfigi_composite","openfigi_composite",StorageType.SYMBOL,true,"来源复合 FIGI；原搜索明确为空时保留 null，不补造标识",null),
            field("history_source_row",StorageType.LONG,"原历史目录行号，不按同代码去重",false),field("history_source_sha256",StorageType.SYMBOL,"原历史目录完整文件 SHA-256",false),
            field("metadata_source_sha256",StorageType.SYMBOL,"对应永久代码的搜索原文 SHA-256",false),
            field("price_from",StorageType.TIMESTAMP,"本次已验收来源的首个价格业务日期",true),field("price_through",StorageType.TIMESTAMP,"本次已验收来源的最后价格业务日期，不冒充正式退市日",true),
            field("price_source_sha256",StorageType.SYMBOL,"已验收价格原文 SHA-256",false),field("price_run_id",StorageType.VARCHAR,"已验收的原价格运行 ID",false),field("source_sha256",StorageType.SYMBOL,"本次核对清单 SHA-256",false)),
            List.of("observed_at","provider","security_id"),List.of("observed_at","provider","security_id"),"observed_at",Partition.YEAR,true,Set.of(Capability.READ,Capability.WRITE),List.of(),
            "只登记有原搜索、目录行及成功价格运行支持的独立证券映射；价格和原代码不改写，未登记部分不推断为已覆盖。");

    public static List<Map<String,Object>> rows(byte[] bytes,String sha,Instant observed)throws Exception {
        if(bytes.length>1024*1024)throw new IllegalArgumentException("永久代码核对清单超过读取边界");hash(sha);var document=JSON.readTree(bytes);var versionNode=document.path("version");int version=versionNode.asInt();if(!versionNode.isIntegralNumber()||!versionNode.canConvertToInt()||!Set.of(1,2).contains(version)||!names(document).equals(Set.of("version","records","sources"))||!document.path("records").isArray()||document.path("records").isEmpty()||document.path("records").size()>100||!document.path("sources").isArray()||document.path("sources").size()>300)throw new IllegalArgumentException("永久代码核对清单格式或边界不符");
        var sources=new HashMap<String,Set<String>>();
        for(var source:document.path("sources")) {
            if(!names(source).equals(Set.of("kind","path","sha256"))||!Set.of("TIINGO_SEARCH","HISTORY_CATALOG","PRICE").contains(source.path("kind").asText())||source.path("path").asText().isBlank())throw new IllegalArgumentException("永久代码原件声明不符");
            hash(source.path("sha256").asText());if(!sources.computeIfAbsent(source.path("kind").asText(),k->new HashSet<>()).add(source.path("sha256").asText()))throw new IllegalArgumentException("原件声明重复");
        }
        var rows=new ArrayList<Map<String,Object>>();var ids=new HashSet<String>();var priceKeys=new HashSet<String>();
        for(var record:document.path("records")) {
            if(!names(record).equals(FIELDS)||!record.path("provider").asText().equals("TIINGO")||!record.path("security_id").asText().matches("US[0-9]{12}")||!record.path("is_active").isBoolean()||!record.path("history_source_row").isIntegralNumber()||!record.path("history_source_row").canConvertToLong()||record.path("history_source_row").asLong()<1)throw new IllegalArgumentException("永久证券标识、类型或目录行号不符");
            for(String field:List.of("price_symbol","source_ticker"))if(!record.path(field).asText().matches("[A-Z0-9][A-Z0-9.\\-^]{0,31}"))throw new IllegalArgumentException("请求代码格式不符");
            for(String field:List.of("security_name","catalog_exchange","price_run_id"))if(!record.path(field).isTextual()||record.path(field).asText().isBlank())throw new IllegalArgumentException("证券身份或原运行缺失");
            // v1 保留原必填约定；v2 仅接受原搜索明确的 null，后续仍须逐字段核对原永久身份及价格证据。
            var figi=record.path("openfigi_composite");if(!(version==2&&figi.isNull())&&(!figi.isTextual()||figi.asText().isBlank()||figi.asText().equalsIgnoreCase("null")))throw new IllegalArgumentException("来源 FIGI 必须是有效文本，或版本 2 中明确的 null");
            if(!Set.of("Stock","ETF").contains(record.path("asset_type").asText())||!record.path("price_run_id").asText().matches("market-[0-9a-f-]{36}"))throw new IllegalArgumentException("证券类别或价格运行不符");
            if(!ids.add(record.path("provider").asText()+"|"+record.path("security_id").asText())||!priceKeys.add(record.path("provider").asText()+"|"+record.path("price_symbol").asText()))throw new IllegalArgumentException("同一清单的永久证券重复，或不同证券占用了同一价格代码");
            for(var pair:Map.of("history_source_sha256","HISTORY_CATALOG","metadata_source_sha256","TIINGO_SEARCH","price_source_sha256","PRICE").entrySet()){String value=record.path(pair.getKey()).asText();hash(value);if(!sources.getOrDefault(pair.getValue(),Set.of()).contains(value))throw new IllegalArgumentException("身份或价格摘要没有对应原件");}
            LocalDate from=LocalDate.parse(record.path("price_from").asText()),through=LocalDate.parse(record.path("price_through").asText());if(through.isBefore(from))throw new IllegalArgumentException("已验收价格窗口倒置");
            var row=new LinkedHashMap<String,Object>();row.put("observed_at",observed.toString());
            for(var field:IDENTIFIERS.columns()) {
                String name=field.storageName();if(name.equals("observed_at")||name.equals("source_sha256"))continue;
                Object value=switch(name){case "price_from"->from.atStartOfDay(ZoneOffset.UTC).toInstant().toString();case "price_through"->through.atStartOfDay(ZoneOffset.UTC).toInstant().toString();case "is_active"->record.path(name).booleanValue();case "history_source_row"->record.path(name).longValue();case "openfigi_composite"->figi.isNull()?null:figi.asText();default->record.path(name).asText();};row.put(name,value);
            }row.put("source_sha256",sha);rows.add(Collections.unmodifiableMap(row));
        }return List.copyOf(rows);
    }
    private static Set<String> names(JsonNode node){var result=new HashSet<String>();node.fieldNames().forEachRemaining(result::add);return result;}
    private static void hash(String value){if(!value.matches("[0-9a-f]{64}"))throw new IllegalArgumentException("原件摘要必须是 SHA-256");}
    private static Column field(String name,StorageType type,String meaning,boolean businessDate){return new Column(name,name,name,type,false,meaning,type==StorageType.TIMESTAMP?new TemporalContract(businessDate?TemporalKind.BUSINESS_DATE:TemporalKind.INSTANT,"ISO-8601",businessDate?"calendar":"UTC","MICROS",meaning):null);}
}
