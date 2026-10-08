package com.zoutrankil.data.l2.application;

import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.l2.domain.L2IntradayBarFeaturesRows;
import com.zoutrankil.data.l2.port.L2IntradayBarFeaturesWriteSession;
import com.zoutrankil.data.l2.storage.L2IntradayBarFeaturesWritePort;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static com.zoutrankil.data.l2.application.L2IntradayBarFeaturesFixtures.*;

class L2IntradayBarFeaturesCodecContractTest {
    @Test void exactBytesKeepAllSixtyFieldsNullSignedZeroLongAndUtf8()throws Exception{
        var rows=rows();var expected=List.of(CANONICAL_1,CANONICAL_2);var lengths=List.of(1046,1062);
        var transport=List.of(8880,9008);
        for(int i=0;i<rows.size();i++){
            byte[] bytes=expected.get(i).getBytes(StandardCharsets.UTF_8);
            assertEquals(lengths.get(i),bytes.length);assertArrayEquals(bytes,L2IntradayBarFeaturesRows.canonicalBytes(rows.get(i)));
            assertArrayEquals(bytes,L2IntradayBarFeaturesWriteSession.CODEC.canonicalBytes(rows.get(i)));
            assertArrayEquals(bytes,L2IntradayBarFeaturesWritePort.CODEC.canonicalBytes(rows.get(i)));
            assertEquals(transport.get(i),L2IntradayBarFeaturesRows.estimatedTransportBytes(rows.get(i),bytes));
            assertEquals(rows.get(i).key(),L2IntradayBarFeaturesRows.key(rows.get(i)));
            var parsed=JSON.readTree(bytes);assertEquals(60,parsed.size());assertTrue(parsed.has("open"));assertTrue(parsed.get("open").isNull());
        }
        assertEquals(Double.doubleToRawLongBits(-0.0),Double.doubleToRawLongBits((Double)rows.getFirst().feature("volume")));
        assertEquals(9007199254740993L,rows.getLast().feature("tick_count"));assertEquals("主板",rows.getLast().board());
    }

    @Test void pageFingerprintUsesIndependentLengthFramedGolden()throws Exception{
        var method=L2IntradayBarFeaturesParquetSource.class.getDeclaredMethod("pageFingerprint",String.class,String.class,List.class);
        method.setAccessible(true);
        assertEquals(PAGE_HASH,method.invoke(null,FINGERPRINT,CURSOR,rows()));
        var independentlyFramed=new java.io.ByteArrayOutputStream();
        independentlyFramed.write("d087-page-v1".getBytes(StandardCharsets.UTF_8));independentlyFramed.write(0);
        independentlyFramed.write(FINGERPRINT.getBytes(StandardCharsets.US_ASCII));independentlyFramed.write(0);
        independentlyFramed.write(CURSOR.getBytes(StandardCharsets.UTF_8));
        var output=new java.io.DataOutputStream(independentlyFramed);
        for(var row:List.of(CANONICAL_1,CANONICAL_2)){
            byte[] bytes=row.getBytes(StandardCharsets.UTF_8);output.writeInt(bytes.length);output.write(bytes);
        }
        assertEquals(2206,independentlyFramed.size());
        assertEquals(PAGE_HASH,HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(independentlyFramed.toByteArray())));
    }

    @Test void admissionAndMinuteKeyDoNotChange(){
        assertDoesNotThrow(()->L2IntradayBarFeaturesRows.requireIsolatedTableName("java_d087_l2_intraday_bar_features_case"));
        assertThrows(IllegalStateException.class,()->L2IntradayBarFeaturesRows.requireIsolatedTableName("l2_intraday_bar_features"));
        assertThrows(IllegalStateException.class,()->L2IntradayBarFeaturesRows.requireIsolatedTableName("java_d087_l2_intraday_bar_features_"));
        assertThrows(IllegalArgumentException.class,()->new L2IntradayBarFeaturesKey("000001.SZ",java.time.Instant.parse("2026-09-21T01:15:01Z")));
    }
}
