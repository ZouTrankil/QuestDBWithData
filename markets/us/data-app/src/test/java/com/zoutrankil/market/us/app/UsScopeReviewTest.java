package com.zoutrankil.market.us.app;

import java.nio.file.Path;
import java.sql.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class UsScopeReviewTest {
    @TempDir Path root;
    @Test void onlyUnpublishedCandidatesAreHeldAndOriginalSourceEvidenceIsPreserved()throws Exception {
        try(var db=DriverManager.getConnection("jdbc:sqlite:"+root.resolve("scope.sqlite3"));var s=db.createStatement()) {
            s.execute("CREATE TABLE broad_tasks(id TEXT,kind TEXT,symbol TEXT,phase TEXT,bundle_id TEXT,proof TEXT,updated_at TEXT,raw TEXT,sha TEXT)");
            for(String phase:List.of("PLANNED","FETCHED","FAILED_SOURCE","VERIFIED","PUBLISHING"))try(var insert=db.prepareStatement("INSERT INTO broad_tasks VALUES(?,'DAILY','AFJKR',?,NULL,'original-proof',NULL,'original-raw','original-sha')")){insert.setString(1,phase);insert.setString(2,phase);insert.executeUpdate();}
            s.execute("INSERT INTO broad_tasks VALUES('common','DAILY','BHF','PLANNED',NULL,NULL,NULL,NULL,NULL)");
            var official=Map.<String,Object>of("symbol","AFJKR","security_name","Aimei Health Technology Co., Ltd - Right","daily_candidate",false,"scope_reason","NON_COMMON_SECURITY","source_sha256","a".repeat(64));UsBroadDailyCollector.holdNonCommonCandidates(db,List.of(official));
            try(var rows=s.executeQuery("SELECT id,phase,proof,raw,sha FROM broad_tasks WHERE symbol='AFJKR'")){int held=0;while(rows.next()){String old=rows.getString(1);if(List.of("VERIFIED","PUBLISHING").contains(old))assertEquals(old,rows.getString(2));else {held++;assertEquals("SCOPE_REVIEW_REQUIRED",rows.getString(2));assertTrue(rows.getString(3).contains("original-proof"));assertTrue(rows.getString(3).contains("NON_COMMON_SECURITY"));}assertEquals("original-raw",rows.getString(4));assertEquals("original-sha",rows.getString(5));}assertEquals(3,held);}
            try(var rows=s.executeQuery("SELECT phase FROM broad_tasks WHERE id='common'")){assertTrue(rows.next());assertEquals("PLANNED",rows.getString(1));}
            assertThrows(IllegalArgumentException.class,()->UsBroadDailyCollector.holdNonCommonCandidates(db,List.of(Map.of("daily_candidate",true,"scope_reason","EQUITY_CANDIDATE"))));
        }
    }
    @Test void currentEtfCannotCompleteAnOlderStockWindowAndVerifiedOrBundledEvidenceIsRetained()throws Exception {
        try(var db=DriverManager.getConnection("jdbc:sqlite:"+root.resolve("etf.sqlite3"));var s=db.createStatement()) {
            s.execute("CREATE TABLE broad_tasks(id TEXT,kind TEXT,symbol TEXT,phase TEXT,bundle_id TEXT,proof TEXT,updated_at TEXT,raw TEXT,sha TEXT)");
            for(String phase:List.of("PLANNED","FETCHED","FAILED_SOURCE","VERIFIED","PUBLISHING"))try(var insert=db.prepareStatement("INSERT INTO broad_tasks VALUES(?,'DAILY','AAAP',?,NULL,'old-stock-window-2015-2018',NULL,'raw','sha')")){insert.setString(1,phase);insert.setString(2,phase);insert.executeUpdate();}
            s.execute("INSERT INTO broad_tasks VALUES('bundled','DAILY','AAAP','FETCHED','original-bundle','bundle-proof',NULL,'bundle-raw','bundle-sha')");
            var official=Map.<String,Object>of("symbol","AAAP","security_name","Pacer Barings CLO Market Flex ETF","daily_candidate",false,"is_etf",true,"scope_reason","ETF_SEPARATE_SCOPE","source_sha256","b".repeat(64));
            UsBroadDailyCollector.holdNonCommonCandidates(db,List.of(official));
            try(var r=s.executeQuery("SELECT id,phase,proof,raw,sha FROM broad_tasks")) {int held=0;while(r.next()) {
                String id=r.getString(1);if(List.of("VERIFIED","PUBLISHING").contains(id))assertEquals(id,r.getString(2));else if(id.equals("bundled")){assertEquals("FETCHED",r.getString(2));assertEquals("bundle-proof",r.getString(3));}else{held++;assertEquals("SCOPE_REVIEW_REQUIRED",r.getString(2));assertTrue(r.getString(3).contains("CURRENT_ETF_REQUIRES_HISTORY_REVIEW"));assertTrue(r.getString(3).contains("old-stock-window-2015-2018"));assertTrue(r.getString(3).contains("ETF_SEPARATE_SCOPE"));assertEquals("raw",r.getString(4));assertEquals("sha",r.getString(5));}
            }assertEquals(3,held);}
            assertThrows(IllegalArgumentException.class,()->UsBroadDailyCollector.holdNonCommonCandidates(db,List.of(Map.of("symbol","AAAP","daily_candidate",false,"is_etf",false,"scope_reason","ETF_SEPARATE_SCOPE"))));
        }
    }
}
