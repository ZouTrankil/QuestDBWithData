package com.zoutrankil.data.strategy.input;

import com.zoutrankil.data.derived.storage.BseLegacyFactorAliases;
import java.time.LocalDate;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class QuestDbStrategyInputsTest {
    @Test void nullableJoinAuditsPrelistingDelistedUnknownAndUnquotedObservationsWithoutInventingPrices() {
        var day = LocalDate.of(2026, 10, 16);
        var raw = List.<Map<String,Object>>of(Map.of("ts",day,"symbol","001999.SZ"), Map.of("ts",day,"symbol","600999.SH"),
                Map.of("ts",day,"symbol","019001.SZ"), Map.of("ts",day,"symbol","688766.SH"));
        var metadata = List.<Map<String,Object>>of(Map.of("ts_code","001999.SZ","list_date","20261019"),
                Map.of("ts_code","600999.SH","list_status","D","delist_date","20261015"), Map.of("ts_code","688766.SH","list_date","20200101"));
        var audit = QuestDbStrategyInputs.verifyMarketJoin(Set.of(),raw,metadata,List.of());
        assertEquals(List.of("PRE_LISTING","POST_DELISTING","NO_STOCK_METADATA_OR_DAILY","NO_OBSERVED_STOCK_DAILY"),audit.stream().map(row->row.get("reason")).toList());
        assertTrue(audit.stream().allMatch(row->row.keySet().equals(Set.of("trade_date","symbol","reason"))));
    }
    @Test void observedDailyQuoteMissingFromCanonicalViewStillFailsTheNullableJoinGuard() {
        var day=LocalDate.of(2026,10,16);
        var l2=List.<Map<String,Object>>of(Map.of("ts",day,"symbol","001999.SZ"));
        var observed=List.<Map<String,Object>>of(Map.of("trade_date",day,"ts_code","001999.SZ"));
        assertThrows(IllegalStateException.class,()->QuestDbStrategyInputs.verifyMarketJoin(Set.of(),l2,List.of(),observed));
    }
    @Test void quotedCanonicalStockStillRequiresItsMetadata() {
        var day=LocalDate.of(2026,10,16);
        var quoted=Set.of(new QuestDbStrategyInputs.MarketKey(day,"001999.SZ"));
        var l2=List.<Map<String,Object>>of(Map.of("ts",day,"symbol","001999.SZ"));
        assertThrows(IllegalStateException.class,()->QuestDbStrategyInputs.verifyMarketJoin(quoted,l2,List.of(),List.of()));
        assertTrue(QuestDbStrategyInputs.verifyMarketJoin(quoted,l2,List.of(Map.of("ts_code","001999.SZ")),List.of()).isEmpty());
    }
    @Test void streamingSourceDigestMatchesTheOriginalSortedJsonAndLengthBytesExactly()throws Exception {
        var row=new LinkedHashMap<String,Object>();row.put("z",null);row.put("trade_date",LocalDate.of(2026,10,16));
        row.put("symbol","510300.SH");row.put("value",-0.0);row.put("flag",true);row.put("count",7935L);
        row.put("float",1.25f);row.put("name","中文\\\"\n");row.put("decimal",new java.math.BigDecimal("1.2300"));
        row.put("integer",17);row.put("short",(short)7);row.put("byte",(byte)2);row.put("small",1e-12);
        row.put("large",new java.math.BigInteger("123456789012345678901234567890"));
        var second=new LinkedHashMap<String,Object>(row);second.put("value",Double.NaN);
        var rows=List.<Map<String,Object>>of(row,com.zoutrankil.data.strategy.score.FrozenScalarValues.copyOf(second));
        var json=new com.fasterxml.jackson.databind.ObjectMapper();var expected=java.security.MessageDigest.getInstance("SHA-256");
        for(var input:rows){var sorted=new TreeMap<String,Object>();input.forEach((key,value)->sorted.put(key,value instanceof LocalDate date?date.toString():value));
            byte[] bytes=json.writeValueAsBytes(sorted);expected.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.length).array());expected.update(bytes);}
        assertEquals(HexFormat.of().formatHex(expected.digest()),QuestDbStrategyInputs.hashRows(rows));
        assertEquals(HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest()),QuestDbStrategyInputs.hashRows(List.of()));
    }
    @Test void monthlyReadsCoverTheExactFullWarmupAndSignalRangeWithoutOverlapOrGaps(){
        LocalDate start=LocalDate.of(2025,7,1).minusDays(460),end=LocalDate.of(2026,10,16);
        var actual=new ArrayList<LocalDate>();
        for(var range:QuestDbStrategyInputs.monthRanges(start,end)){
            assertFalse(range.from().isBefore(start));assertFalse(range.exclusiveEnd().isAfter(end.plusDays(1)));
            assertTrue(java.time.temporal.ChronoUnit.DAYS.between(range.from(),range.exclusiveEnd())<=31);
            range.from().datesUntil(range.exclusiveEnd()).forEach(actual::add);
        }
        assertEquals(start.datesUntil(end.plusDays(1)).toList(),actual);
        assertEquals(List.of(new QuestDbStrategyInputs.DateRange(end,end.plusDays(1))),QuestDbStrategyInputs.monthRanges(end,end));
    }
    private Map<String,Object> stockRow() {
        return new HashMap<>(Map.of("ts_code","000016.SZ","trade_date",java.time.LocalDate.of(2026,10,16),
                "price",2.46,"open_price",2.46,"amount",0.0,"up_limit",2.71,"down_limit",2.21,"is_suspended",1L));
    }
    @Test void suspendedCarriedPriceRowMayLackSameDayDailyBasicWithoutInventingValues(){
        var row=stockRow();
        assertDoesNotThrow(()->QuestDbStrategyInputs.validateStockRows(List.of(row)));
        assertFalse(row.containsKey("pb"));assertFalse(row.containsKey("turnover_rate"));
    }
    @Test void tradingStockStillRequiresExactDailyBasicKey(){
        var row=stockRow();row.put("is_suspended",0L);
        assertThrows(IllegalStateException.class,()->QuestDbStrategyInputs.validateStockRows(List.of(row)));
        row.put("basic_present","000016.SZ");
        assertDoesNotThrow(()->QuestDbStrategyInputs.validateStockRows(List.of(row)));
    }
    @Test void suspensionDoesNotExcuseMissingCriticalPriceOrUnprovenStatus(){
        var missingPrice=stockRow();missingPrice.remove("price");
        assertThrows(IllegalStateException.class,()->QuestDbStrategyInputs.validateStockRows(List.of(missingPrice)));
        var unproven=stockRow();unproven.remove("is_suspended");
        assertThrows(IllegalStateException.class,()->QuestDbStrategyInputs.validateStockRows(List.of(unproven)));
    }
    @Test void onlyProvenSuspensionMayRetainMissingLimitsAndItNeverInventsTheirValues(){
        var row=stockRow();row.put("up_limit",null);row.put("down_limit",null);
        assertDoesNotThrow(()->QuestDbStrategyInputs.validateStockRows(List.of(row)));
        assertNull(row.get("up_limit"));assertNull(row.get("down_limit"));
        row.put("is_suspended",0L);row.put("basic_present","000016.SZ");
        assertThrows(IllegalStateException.class,()->QuestDbStrategyInputs.validateStockRows(List.of(row)));
        row.put("is_suspended",1L);row.put("up_limit",Double.POSITIVE_INFINITY);
        assertThrows(IllegalStateException.class,()->QuestDbStrategyInputs.validateStockRows(List.of(row)));
    }
    private Map<String,Object> aliasRow(String symbol){
        var row=stockRow();row.put("trade_date",LocalDate.of(2025,7,1));row.put("ts_code",symbol);row.put("is_suspended",0L);
        row.put("basic_present",symbol);return row;
    }
    private Map<LocalDate,BseLegacyFactorAliases.Scope> aliasScope(){
        LocalDate day=LocalDate.of(2025,7,1);
        return Map.of(day,new BseLegacyFactorAliases.Scope(Set.of(new BseLegacyFactorAliases.Key(day,"839729.BJ")),Map.of("verifiedDuplicateAliasRows",1)));
    }
    @Test void canonicalStockUniverseKeepsHsAndBseStocksWhileExcludingOnlyTheProvenDuplicate(){
        var old=aliasRow("839729.BJ");old.put("basic_present",null);old.put("up_limit",null);old.put("down_limit",null);
        var current=aliasRow("920729.BJ");var hs=aliasRow("000016.SZ");var raw=List.of(old,current,hs);
        var result=QuestDbStrategyInputs.canonicalStockRows(raw,aliasScope());
        assertEquals(List.of(current,hs),result.rows());assertEquals(1,result.excluded());assertEquals(3,raw.size());
        assertNull(old.get("basic_present"));assertNull(old.get("up_limit"));
        assertDoesNotThrow(()->QuestDbStrategyInputs.validateStockRows(result.rows()));
    }
    @Test void missingOrDifferentMvCounterpartCannotAuthorizeAliasExclusion(){
        var old=aliasRow("839729.BJ");
        assertThrows(IllegalStateException.class,()->QuestDbStrategyInputs.canonicalStockRows(List.of(old),aliasScope()));
        var different=aliasRow("920729.BJ");different.put("price",2.47);
        assertThrows(IllegalStateException.class,()->QuestDbStrategyInputs.canonicalStockRows(List.of(old,different),aliasScope()));
    }
    @Test void anUnprovenOrphanRemainsSubjectToTheNormalMissingBasicGuard(){
        var unknown=aliasRow("430000.BJ");unknown.put("basic_present",null);
        var result=QuestDbStrategyInputs.canonicalStockRows(List.of(unknown),aliasScope());
        assertEquals(1,result.rows().size());assertEquals(0,result.excluded());
        assertThrows(IllegalStateException.class,()->QuestDbStrategyInputs.validateStockRows(result.rows()));
    }
    private Map<String,Object> clocks(String... extra){var fields=new ArrayList<Map<String,String>>();for(String name:List.of("ts","symbol","clean_amount","q2_count","q2_accumulation","q3_count","q4_count","main_funds_buy_amount"))fields.add(Map.of("column",name,"type","DOUBLE"));for(String name:extra)fields.add(Map.of("column",name,"type","DOUBLE"));return Map.of("l2_daily_features",Map.of("schema",fields));}
    @Test void pinnedProjectionKeepsAvailableOptionalSignalsAndOmitsUnrelatedWideFields(){
        String columns=QuestDbStrategyInputs.l2Projection(clocks("amount","turnover_rate","q2_accumulation_ratio","huge_unused_feature"));
        assertTrue(columns.contains("amount"));assertTrue(columns.contains("turnover_rate"));assertTrue(columns.contains("q2_accumulation_ratio"));assertFalse(columns.contains("huge_unused_feature"));assertFalse(columns.contains("active_buy_ratio"));
    }
    @Test void absentRequiredScoringColumnsFailBeforeSqlRead(){assertThrows(IllegalStateException.class,()->QuestDbStrategyInputs.l2Projection(Map.of("l2_daily_features",Map.of("schema",List.of(Map.of("column","symbol"))))));}
    @Test void ordinaryReferenceRequiresFreshCommittedReaderAndContentInsteadOfOptionalWriterMetrics(){
        var card=new HashMap<String,Object>(Map.of("id",17,"directoryName","stock_detail_info~17","walEnabled",false,"table_suspended",false,
                "readerTxn",29,"referenceRows",5914,"referenceSha256","a".repeat(64),"referenceTransactionPolicy","fresh_committed_sql_reader_and_content_v1"));
        assertDoesNotThrow(()->QuestDbStrategyInputs.verifyClock("stock_detail_info",card));
        card.put("table_txn",null);assertDoesNotThrow(()->QuestDbStrategyInputs.verifyClock("stock_detail_info",card));
        card.put("readerTxn",-1);assertThrows(IllegalStateException.class,()->QuestDbStrategyInputs.verifyClock("stock_detail_info",card));
        card.put("readerTxn",29);card.remove("referenceSha256");assertThrows(IllegalStateException.class,()->QuestDbStrategyInputs.verifyClock("stock_detail_info",card));
    }
    @Test void cachedWriterMetricsCannotReplaceAnOrdinaryReferenceReadProof(){
        var card=new HashMap<String,Object>(Map.of("id",17,"directoryName","stock_detail_info~17","walEnabled",false,"table_suspended",false,"table_txn",29));
        assertThrows(IllegalStateException.class,()->QuestDbStrategyInputs.verifyClock("stock_detail_info",card));
    }
    @Test void walSourcesKeepStrictPendingSuspensionAndFrontierChecks(){
        var card=new HashMap<String,Object>(Map.of("id",1,"directoryName","etf_daily~1","walEnabled",true,"suspended",false,"writerTxn",7,"sequencerTxn",7,"bufferedTxnSize",0));
        assertDoesNotThrow(()->QuestDbStrategyInputs.verifyClock("etf_daily",card));
        card.put("sequencerTxn",8);assertThrows(IllegalStateException.class,()->QuestDbStrategyInputs.verifyClock("etf_daily",card));
        card.put("sequencerTxn",7);card.put("bufferedTxnSize",1);assertThrows(IllegalStateException.class,()->QuestDbStrategyInputs.verifyClock("etf_daily",card));
        card.put("bufferedTxnSize",0);card.put("suspended",true);assertThrows(IllegalStateException.class,()->QuestDbStrategyInputs.verifyClock("etf_daily",card));
    }
}
