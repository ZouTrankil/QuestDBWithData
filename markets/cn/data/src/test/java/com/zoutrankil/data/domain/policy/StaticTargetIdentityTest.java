package com.zoutrankil.data.domain.policy;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class StaticTargetIdentityTest {
    private static final String PHYSICAL_ID =
            "static-v2-f2d552a7e3068bfc3a436f1169618e9c4fc018cf9dc402881bc2322e40b60b88";

    @Test void frozenEndpointTableAndPhysicalIdentityKeepTheirDigest() {
        assertEquals(PHYSICAL_ID, StaticTargetIdentity.identify(
                "jdbc:postgresql://server-a:8812/qdb", "stock_detail_info", 41, "stock_detail_info~41"));
        assertEquals(PHYSICAL_ID, StaticTargetIdentity.identify(
                "jdbc:postgresql://SERVER-A:8812/qdb?user=fixture&password=changed&sslmode=require",
                "stock_detail_info", 41, "stock_detail_info~41"));
    }

    @Test void defaultPortZeroLogicalIdentityAndNullDirectoryKeepTheirDigest() {
        String expected = "static-v2-9f134c9496426b7adf775a1741efc25c4cdc7f43baa3f9bfba95a639caaa547b";
        assertEquals(expected, StaticTargetIdentity.identify(
                "jdbc:postgresql://server-a/qdb", "stock_detail_info", 0, null));
        assertEquals(expected, StaticTargetIdentity.identify(
                "jdbc:postgresql://server-a:5432/qdb", "stock_detail_info", 0, null));
    }

    @Test void encodedDatabasePathUsesTheOriginalRawPath() {
        assertEquals("static-v2-f136973b1799f61ea5f1dd55a101788a348316e4f0c4d311426a6d94ae520b0d",
                StaticTargetIdentity.identify(
                        "jdbc:postgresql://server-a/data%2Fset", "stock_detail_info", 0, null));
        assertNotEquals(StaticTargetIdentity.identify(
                        "jdbc:postgresql://server-a/data/set", "stock_detail_info", 0, null),
                StaticTargetIdentity.identify(
                        "jdbc:postgresql://server-a/data%2Fset", "stock_detail_info", 0, null));
    }

    @Test void everyTargetComponentContributesToTheDigest() {
        for (String url : new String[]{"jdbc:postgresql://server-b:8812/qdb",
                "jdbc:postgresql://server-a:8813/qdb", "jdbc:postgresql://server-a:8812/other"}) {
            assertNotEquals(PHYSICAL_ID, StaticTargetIdentity.identify(
                    url, "stock_detail_info", 41, "stock_detail_info~41"));
        }
        assertNotEquals(PHYSICAL_ID, StaticTargetIdentity.identify(
                "jdbc:postgresql://server-a:8812/qdb", "another_table", 41, "stock_detail_info~41"));
        assertNotEquals(PHYSICAL_ID, StaticTargetIdentity.identify(
                "jdbc:postgresql://server-a:8812/qdb", "stock_detail_info", 42, "stock_detail_info~41"));
        assertNotEquals(PHYSICAL_ID, StaticTargetIdentity.identify(
                "jdbc:postgresql://server-a:8812/qdb", "stock_detail_info", 41, "stock_detail_info~42"));
    }

    @Test void invalidEndpointsKeepCredentialFreeMessagesWithoutCauses() {
        for (String url : new String[]{null, "jdbc:postgresql:qdb?password=private-fixture"}) {
            var failure = assertThrowsExactly(IllegalArgumentException.class,
                    () -> StaticTargetIdentity.identify(url, "stock_detail_info", 0, null));
            assertEquals("Explicit PGWire endpoint required for static target identity", failure.getMessage());
            assertNull(failure.getCause());
        }
        for (String url : new String[]{"jdbc:postgresql://bad host/qdb?password=private-fixture",
                "jdbc:postgresql://server-a/?password=private-fixture", "jdbc:postgresql:///qdb"}) {
            var failure = assertThrowsExactly(IllegalArgumentException.class,
                    () -> StaticTargetIdentity.identify(url, "stock_detail_info", 0, null));
            assertEquals("Cannot bind static target to an explicit PGWire endpoint", failure.getMessage());
            assertNull(failure.getCause());
        }
    }

    @Test void identifierValidationStillPrecedesEndpointValidation() {
        var failure = assertThrowsExactly(IllegalArgumentException.class,
                () -> StaticTargetIdentity.identify(null, "invalid table", 0, null));
        assertEquals("Invalid identifier", failure.getMessage());
        assertNull(failure.getCause());
    }
}
