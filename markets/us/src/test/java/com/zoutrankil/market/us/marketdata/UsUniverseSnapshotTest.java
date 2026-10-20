package com.zoutrankil.market.us.marketdata;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class UsUniverseSnapshotTest {
    @Test void rightsAreNotCommonSharesButNamesAndAdrDescriptionsMustRemainEligible() {
        var file=new StringBuilder("Symbol|Security Name|Market Category|Test Issue|Financial Status|Round Lot Size|ETF|NextShares\n");
        var names=new LinkedHashMap<String,String>();names.put("AFJKR","Aimei Health Technology Co., Ltd - Right");names.put("AMPGZ","Amplitech Group, Inc. - Series B Right");names.put("MULTI","Example - Rights");names.put("BHF","Brighthouse Financial, Inc. - Common Stock");names.put("CW","Curtiss-Wright Corporation Common Stock");names.put("ADR","Example American Depositary Shares each representing the right to receive ten shares");
        names.forEach((symbol,name)->file.append(symbol).append('|').append(name).append("|S|N|N|100|N|N\n"));
        for(int i=0;i<1000;i++)file.append("X").append(i).append("|Example - Common Stock|S|N|N|100|N|N\n");file.append("File Creation Time: 101920261600\n");
        var rows=UsUniverseSnapshot.rows(file.toString().getBytes(StandardCharsets.UTF_8),true,"a".repeat(64),Instant.EPOCH);var by=new HashMap<String,Map<String,Object>>();rows.forEach(r->by.put(r.get("symbol").toString(),r));
        for(String symbol:List.of("AFJKR","AMPGZ","MULTI")){assertEquals(false,by.get(symbol).get("daily_candidate"));assertEquals("NON_COMMON_SECURITY",by.get(symbol).get("scope_reason"));assertEquals(names.get(symbol),by.get(symbol).get("security_name"));}
        for(String symbol:List.of("BHF","CW","ADR"))assertEquals(true,by.get(symbol).get("daily_candidate"));assertEquals(1006,rows.size());
    }
}
