package com.zoutrankil.data.service;

import com.zoutrankil.data.repository.StockDetailInfoStorage;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class StaticTargetIdentityTest {
    private final StockDetailInfoStorage.Identity physical=new StockDetailInfoStorage.Identity(41,"stock_detail_info~41");
    @Test void endpointDatabaseAndPhysicalIdentityCannotCollideButCredentialRotationIsStable() {
        var baseline=StaticTargetIdentity.identify("jdbc:postgresql://server-a:8812/qdb","stock_detail_info",physical);
        for(String url:new String[]{"jdbc:postgresql://server-b:8812/qdb",
                "jdbc:postgresql://server-a:8813/qdb","jdbc:postgresql://server-a:8812/other"})
            assertNotEquals(baseline,StaticTargetIdentity.identify(url,"stock_detail_info",physical));
        assertNotEquals(baseline,StaticTargetIdentity.identify("jdbc:postgresql://server-a:8812/qdb",
                "stock_detail_info",new StockDetailInfoStorage.Identity(42,"stock_detail_info~42")));
        assertEquals(baseline,StaticTargetIdentity.identify(
                "jdbc:postgresql://SERVER-A:8812/qdb?user=fixture&password=changed&sslmode=require",
                "stock_detail_info",physical));
        assertTrue(baseline.matches("static-v2-[0-9a-f]{64}"));
    }
    @Test void ambiguousEndpointIsRejectedWithoutEchoingCredentials() {
        var failure=assertThrows(IllegalArgumentException.class,()->StaticTargetIdentity.identify(
                "jdbc:postgresql://bad host/qdb?password=private-fixture","stock_detail_info",physical));
        assertFalse(failure.getMessage().contains("private-fixture"));assertNull(failure.getCause());
    }
}
