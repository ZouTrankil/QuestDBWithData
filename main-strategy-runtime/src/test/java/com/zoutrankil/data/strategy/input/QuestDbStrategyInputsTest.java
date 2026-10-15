package com.zoutrankil.data.strategy.input;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class QuestDbStrategyInputsTest {
    private Map<String,Object> clocks(String... extra){var fields=new ArrayList<Map<String,String>>();for(String name:List.of("ts","symbol","clean_amount","q2_count","q2_accumulation","q3_count","q4_count","main_funds_buy_amount"))fields.add(Map.of("column",name,"type","DOUBLE"));for(String name:extra)fields.add(Map.of("column",name,"type","DOUBLE"));return Map.of("l2_daily_features",Map.of("schema",fields));}
    @Test void pinnedProjectionKeepsAvailableOptionalSignalsAndOmitsUnrelatedWideFields(){
        String columns=QuestDbStrategyInputs.l2Projection(clocks("amount","turnover_rate","q2_accumulation_ratio","huge_unused_feature"));
        assertTrue(columns.contains("amount"));assertTrue(columns.contains("turnover_rate"));assertTrue(columns.contains("q2_accumulation_ratio"));assertFalse(columns.contains("huge_unused_feature"));assertFalse(columns.contains("active_buy_ratio"));
    }
    @Test void absentRequiredScoringColumnsFailBeforeSqlRead(){assertThrows(IllegalStateException.class,()->QuestDbStrategyInputs.l2Projection(Map.of("l2_daily_features",Map.of("schema",List.of(Map.of("column","symbol"))))));}
    @Test void ordinaryReferenceTableRequiresCommittedIdentityButDoesNotRequireWal(){
        var card=new HashMap<String,Object>(Map.of("id",17,"directoryName","stock_detail_info~17","walEnabled",false,"table_txn",8));
        assertDoesNotThrow(()->QuestDbStrategyInputs.verifyClock("stock_detail_info",card));
        card.remove("table_txn");assertThrows(IllegalStateException.class,()->QuestDbStrategyInputs.verifyClock("stock_detail_info",card));
    }
    @Test void walSourcesKeepStrictPendingSuspensionAndFrontierChecks(){
        var card=new HashMap<String,Object>(Map.of("id",1,"directoryName","etf_daily~1","walEnabled",true,"suspended",false,"writerTxn",7,"sequencerTxn",7,"bufferedTxnSize",0));
        assertDoesNotThrow(()->QuestDbStrategyInputs.verifyClock("etf_daily",card));
        card.put("sequencerTxn",8);assertThrows(IllegalStateException.class,()->QuestDbStrategyInputs.verifyClock("etf_daily",card));
        card.put("sequencerTxn",7);card.put("bufferedTxnSize",1);assertThrows(IllegalStateException.class,()->QuestDbStrategyInputs.verifyClock("etf_daily",card));
        card.put("bufferedTxnSize",0);card.put("suspended",true);assertThrows(IllegalStateException.class,()->QuestDbStrategyInputs.verifyClock("etf_daily",card));
    }
}
