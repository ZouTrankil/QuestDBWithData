package com.zoutrankil.market.us.marketdata;

import com.fasterxml.jackson.databind.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class UsSecurityIdentifiersTest {
    private static final ObjectMapper JSON=new ObjectMapper();
    private static final String SHA="a".repeat(64);
    private Map<String,Object> record(String id,String symbol,boolean active) {
        var row=new LinkedHashMap<String,Object>();row.put("provider","TIINGO");row.put("security_id",id);row.put("price_symbol",symbol);row.put("source_ticker","AMTD");row.put("security_name",active?"AMTD IDEA Group":"TD Ameritrade Holding Corp");row.put("catalog_exchange",active?"NYSE":"NASDAQ");row.put("asset_type","Stock");row.put("is_active",active);row.put("openfigi_composite",active?"BBG00PH297Y9":"BBG000BMFPK4");row.put("history_source_row",active?11843:11844);row.put("history_source_sha256",SHA);row.put("metadata_source_sha256",SHA);row.put("price_from","2019-08-05");row.put("price_through","2020-10-05");row.put("price_source_sha256",SHA);row.put("price_run_id","market-00000000-0000-0000-0000-000000000001");return row;
    }
    private Map<String,Object> document(List<Map<String,Object>> records) {
        return new LinkedHashMap<>(Map.of("version",1,"records",records,"sources",List.of(Map.of("kind","TIINGO_SEARCH","path","search.json","sha256",SHA),Map.of("kind","HISTORY_CATALOG","path","history.zip","sha256",SHA),Map.of("kind","PRICE","path","price.json","sha256",SHA))));
    }
    private List<Map<String,Object>> rows(Map<String,Object> document)throws Exception{return UsSecurityIdentifiers.rows(JSON.writeValueAsBytes(document),SHA,Instant.EPOCH);}
    @Test void twoDifferentIssuersKeepTheSameMarketTickerButUseSeparatePriceKeys()throws Exception {
        var rows=rows(document(List.of(record("US000000000721","US000000000721",false),record("US000000080416","AMTD",true))));assertEquals(2,rows.size());assertEquals(rows.getFirst().get("source_ticker"),rows.getLast().get("source_ticker"));assertNotEquals(rows.getFirst().get("security_id"),rows.getLast().get("security_id"));assertNotEquals(rows.getFirst().get("price_symbol"),rows.getLast().get("price_symbol"));assertEquals("2019-08-05T00:00:00Z",rows.getFirst().get("price_from"));assertEquals(18,rows.getFirst().size());
        assertEquals(List.of("observed_at","provider","security_id"),UsSecurityIdentifiers.IDENTIFIERS.businessKey());
    }
    @Test void cannotCollapseDifferentIssuersIntoTheSamePriceSymbolOrRepeatOneIdentity()throws Exception {
        assertThrows(IllegalArgumentException.class,()->rows(document(List.of(record("US000000000721","AMTD",false),record("US000000080416","AMTD",true)))));
        assertThrows(IllegalArgumentException.class,()->rows(document(List.of(record("US000000000721","US000000000721",false),record("US000000000721","AMTD",true)))));
    }
    @Test void missingProvenanceAndPartialOrInventedMappingFieldsAreRejected()throws Exception {
        var row=record("US000000000721","US000000000721",false);row.put("metadata_source_sha256","b".repeat(64));assertThrows(IllegalArgumentException.class,()->rows(document(List.of(row))));
        row.put("metadata_source_sha256",SHA);row.put("history_source_row",1.5);assertThrows(IllegalArgumentException.class,()->rows(document(List.of(row))));
        row.put("history_source_row",11844);row.put("assume_same_issuer",true);assertThrows(IllegalArgumentException.class,()->rows(document(List.of(row))));
    }
    @Test void reversedWindowsAndNonPermanentIdsCannotDescribeAResolvedIdentity()throws Exception {
        var row=record("AMTD","AMTD",false);assertThrows(IllegalArgumentException.class,()->rows(document(List.of(row))));
        row.put("security_id","US000000000721");row.put("price_from","2021-01-01");assertThrows(IllegalArgumentException.class,()->rows(document(List.of(row))));
    }
    @Test void onlyVersionTwoCanPreserveAnExplicitNullFigiWithoutInventingAnIdentifier()throws Exception {
        var row=record("US000000000721","US000000000721",false);row.put("openfigi_composite",null);var document=document(List.of(row));
        assertThrows(IllegalArgumentException.class,()->rows(document));document.put("version",2);var actual=rows(document).getFirst();assertTrue(actual.containsKey("openfigi_composite"));assertNull(actual.get("openfigi_composite"));assertEquals(18,actual.size());
        assertEquals(2,UsSecurityIdentifiers.IDENTIFIERS.schemaVersion());assertTrue(UsSecurityIdentifiers.IDENTIFIERS.columns().stream().filter(c->c.storageName().equals("openfigi_composite")).findFirst().orElseThrow().nullable());
        row.remove("openfigi_composite");assertThrows(IllegalArgumentException.class,()->rows(document));
    }
    @Test void nullableFigiDoesNotAcceptWrongTypesOrAnAmbiguousManifestVersion() {
        var row=record("US000000000721","US000000000721",false);var document=document(List.of(row));document.put("version",2);
        for(Object value:List.of(123," ","null","NULL",List.of("BBG000BMFPK4"))){row.put("openfigi_composite",value);assertThrows(IllegalArgumentException.class,()->rows(document));}
        row.put("openfigi_composite",null);for(Object version:List.of(3,2.5,"2",4294967298L)){document.put("version",version);assertThrows(IllegalArgumentException.class,()->rows(document));}
    }
}
