package com.zoutrankil.data.derived.domain;

import com.fasterxml.jackson.databind.JsonNode;
import com.zoutrankil.data.domain.*;
import java.io.IOException;
import java.time.*;
import java.util.*;

/** Strict pure decoding shared by owner protocol and physical target. */
public final class EtfMarketOverviewOwnerJson {
    private EtfMarketOverviewOwnerJson() {}
    public static Map<String,JsonNode> snapshotMap(JsonNode node,List<String> tables) throws IOException {
        exactKeys(node,tables);var result=new LinkedHashMap<String,JsonNode>();
        for(String table:tables){var row=node.get(table);validateSnapshot(row,EtfMarketOverviewCachePublicationEnvelope.TARGET_TABLES.contains(table));result.put(table,row.deepCopy());}return result;
    }
    public static void validateSnapshot(JsonNode node)throws IOException{validateSnapshot(node,false);}
    public static void validateSnapshot(JsonNode node,boolean allowUninitializedTarget)throws IOException{
        var p=node==null?null:node.get("physical");var w=node==null?null:node.get("wal");
        if(p==null||w==null||!bool(node,"settled")||!bool(p,"walEnabled")||!bool(p,"dedup")||bool(p,"table_suspended")||bool(w,"suspended")
                ||nonnegative(p,"wal_pending_row_count")!=0||nonnegative(w,"bufferedTxnSize")!=0||nonnegative(w,"writerTxn")!=nonnegative(w,"sequencerTxn"))
            throw new IOException("Unsettled physical/WAL snapshot");
        nonnegative(p,"id");string(p,"directoryName");string(p,"partitionBy");string(p,"designatedTimestamp");
        Long txn=nullableCounter(p,"table_txn");
        if(txn==null){
            Long rows=nullableCounter(p,"table_row_count");
            if(!allowUninitializedTarget||nonnegative(w,"sequencerTxn")!=0||rows!=null&&rows!=0)
                throw new IOException("Null physical txn requires a newly empty target with WAL0 and explicit raw rows null/0");
        }
    }
    public static Long nullableCounter(JsonNode row,String field)throws IOException{
        var value=row==null?null:row.get(field);if(value==null)throw new IOException("Explicit metadata counter required: "+field);
        return value.isNull()?null:nonnegative(row,field);
    }
    public static Optional<EtfMarketOverviewDailyCache> parseCache(JsonNode rows,LocalDate day,String version) throws IOException {
        if(rows==null||!rows.isArray()||rows.size()>1)throw new IOException("One exact cache row at most required");if(rows.isEmpty())return Optional.empty();var row=rows.get(0);exactKeys(row,List.of("trade_date","etf_count","total_share","total_size_yi","source_version"));
        var result=new EtfMarketOverviewDailyCache(businessDate(string(row,"trade_date")),nonnegative(row,"etf_count"),nullableDouble(row,"total_share"),nullableDouble(row,"total_size_yi"),hash(row,"source_version"));
        if(!result.tradeDate().equals(day)||!result.sourceVersion().equals(version))throw new IOException("Cache complete key differs");return Optional.of(result);
    }
    public static MarketBarometerCacheCoverage parseReceipt(JsonNode row) throws IOException {if(row==null||row.isNull())return null;exactKeys(row,List.of("trade_date","dataset_id","source_version","row_count","content_digest"));return new MarketBarometerCacheCoverage(businessDate(string(row,"trade_date")),string(row,"dataset_id"),hash(row,"source_version"),nonnegative(row,"row_count"),hash(row,"content_digest"));}
    public static LocalDate businessDate(String text) throws IOException {try{Instant time=Instant.parse(text);LocalDate day=time.atOffset(ZoneOffset.UTC).toLocalDate();if(!time.equals(day.atStartOfDay().toInstant(ZoneOffset.UTC)))throw new IOException("Exact UTC midnight required");return day;}catch(java.time.DateTimeException error){throw new IOException("UTC timestamp required",error);}}
    public static String string(JsonNode row,String key) throws IOException {var node=row==null?null:row.get(key);if(node==null||!node.isTextual()||node.textValue().isBlank())throw new IOException("Required string: "+key);return node.textValue();}
    public static long nonnegative(JsonNode row,String key) throws IOException {var node=row==null?null:row.get(key);if(node==null||!node.isIntegralNumber()||!node.canConvertToLong()||node.longValue()<0)throw new IOException("Required exact nonnegative LONG: "+key);return node.longValue();}
    public static boolean bool(JsonNode row,String key) throws IOException {var node=row==null?null:row.get(key);if(node==null||!node.isBoolean())throw new IOException("Required boolean: "+key);return node.booleanValue();}
    private static Double nullableDouble(JsonNode row,String key) throws IOException {var node=row.get(key);if(node==null)throw new IOException("Missing nullable field");if(node.isNull())return null;if(!node.isNumber()||!Double.isFinite(node.doubleValue()))throw new IOException("Finite binary64 required");return node.doubleValue();}
    private static String hash(JsonNode row,String key) throws IOException {String hash=string(row,key);if(!hash.matches("[0-9a-f]{64}"))throw new IOException("Lowercase SHA required: "+key);return hash;}
    public static void exactKeys(JsonNode node,Collection<String> expected) throws IOException {if(node==null||!node.isObject())throw new IOException("Exact object required");var keys=new HashSet<String>();node.fieldNames().forEachRemaining(keys::add);if(!keys.equals(new HashSet<>(expected)))throw new IOException("Object fields differ");}
}
