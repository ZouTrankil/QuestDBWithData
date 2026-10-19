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
}
