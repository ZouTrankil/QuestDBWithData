package com.zoutrankil.data.domain.policy;

import com.zoutrankil.data.stock.domain.StockDetailState;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class StaticTargetIdentityFacadeContractTest {
    private static String identify(String url, String table, StockDetailState.Identity identity) {
        return StaticTargetIdentity.identify(url, table, identity.id(), identity.directory());
    }
    private final StockDetailState.Identity physical=new StockDetailState.Identity(41,"stock_detail_info~41");
    @Test void endpointDatabaseAndPhysicalIdentityCannotCollideButCredentialRotationIsStable() {
        var baseline=identify("jdbc:postgresql://server-a:8812/qdb","stock_detail_info",physical);
        for(String url:new String[]{"jdbc:postgresql://server-b:8812/qdb",
                "jdbc:postgresql://server-a:8813/qdb","jdbc:postgresql://server-a:8812/other"})
            assertNotEquals(baseline,identify(url,"stock_detail_info",physical));
        assertNotEquals(baseline,identify("jdbc:postgresql://server-a:8812/qdb",
                "stock_detail_info",new StockDetailState.Identity(42,"stock_detail_info~42")));
        assertEquals(baseline,identify(
                "jdbc:postgresql://SERVER-A:8812/qdb?user=fixture&password=changed&sslmode=require",
                "stock_detail_info",physical));
        assertTrue(baseline.matches("static-v2-[0-9a-f]{64}"));
    }
    @Test void ambiguousEndpointIsRejectedWithoutEchoingCredentials() {
        var failure=assertThrows(IllegalArgumentException.class,()->identify(
                "jdbc:postgresql://bad host/qdb?password=private-fixture","stock_detail_info",physical));
        assertFalse(failure.getMessage().contains("private-fixture"));assertNull(failure.getCause());
    }
}
