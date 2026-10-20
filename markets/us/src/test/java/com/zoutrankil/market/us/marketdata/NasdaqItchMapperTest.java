package com.zoutrankil.market.us.marketdata;

import java.io.*;
import java.nio.ByteBuffer;
import java.time.*;
import java.util.zip.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NasdaqItchMapperTest {
    private byte[] sample()throws Exception {
        var bytes=new ByteArrayOutputStream();try(var gzip=new GZIPOutputStream(bytes);var out=new DataOutputStream(gzip)) {
            byte[] directory=new byte[39];directory[0]='R';directory[2]=1;System.arraycopy("AAPL    ".getBytes(),0,directory,11,8);out.writeShort(39);out.write(directory);
            byte[] add=new byte[36];add[0]='A';add[2]=1;add[10]=123;ByteBuffer.wrap(add,11,8).putLong(-1L);add[19]='B';ByteBuffer.wrap(add,20,4).putInt(100);System.arraycopy("AAPL    ".getBytes(),0,add,24,8);ByteBuffer.wrap(add,32,4).putInt(1234500);out.writeShort(36);out.write(add);
        }return bytes.toByteArray();
    }
    @Test void preservesNanosecondsUnsignedOrderIdAndOriginalMessageOffset()throws Exception {
        var rows=NasdaqItchMapper.messages(new ByteArrayInputStream(sample()),LocalDate.of(2026,6,12),2,1,"sha",Instant.EPOCH);
        var row=rows.getFirst();assertEquals("2026-06-12T04:00:00.000000123Z",row.get("ts"));assertEquals("18446744073709551615",row.get("order_ref"));assertEquals(41L,row.get("decompressed_offset"));assertEquals("AAPL",row.get("symbol"));assertEquals(100L,row.get("shares"));assertEquals(1234500L,row.get("price_4"));assertEquals(false,row.get("source_complete"));
        assertThrows(EOFException.class,()->NasdaqItchMapper.messages(new ByteArrayInputStream(sample()),LocalDate.of(2026,6,12),2,2,"sha",Instant.EPOCH));
    }
}
