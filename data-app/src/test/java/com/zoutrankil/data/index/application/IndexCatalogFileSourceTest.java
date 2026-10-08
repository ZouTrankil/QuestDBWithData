package com.zoutrankil.data.index.application;

import com.zoutrankil.data.service.*;

import com.zoutrankil.data.index.mapper.IndexCatalogMapper;
import com.zoutrankil.data.domain.IndexCatalogDataset;
import com.zoutrankil.data.repository.DatasetWritePreparation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.time.Instant;
import static org.junit.jupiter.api.Assertions.*;

class IndexCatalogFileSourceTest {
    @TempDir Path folder;
    private String header=String.join(",",IndexCatalogFileSource.HEADERS)+"\r\n";
    private String row(String code,String count,String price) {
        return code+",\"name,quoted\",\"full\"\"name\",2004-12-31,1000,series,"+count+","+price
                +",-6.95,stock,,CNY,no,yes,IOSCO,size,2005-04-08\r\n";
    }
    private IndexCatalogFileSource.Input read(String text) throws Exception {
        var path=folder.resolve("source.csv");Files.writeString(path,text);
        return new IndexCatalogFileSource().read(path,Instant.EPOCH,()->false);
    }
    @Test void preservesCodesDatesPercentAndCompleteStorageRoundTrip() throws Exception {
        var input=read("\uFEFF"+header+row("300","300","4598.32")+row("H00999","",""));
        assertEquals(2,input.rows().size());var first=input.rows().getFirst();
        assertEquals("000300",first.indexCode());assertEquals("H00999",input.rows().getLast().indexCode());
        assertEquals("name,quoted",first.shortName());assertEquals("full\"name",first.fullName());
        assertEquals(-6.95,first.return1m());assertEquals(300.0,first.sampleCount());
        assertNull(input.rows().getLast().latestClose());
        var mapper=new IndexCatalogMapper();
        for(var value:input.rows()) {
            assertEquals(value,mapper.fromStorage(mapper.toStorage(value)));
            assertEquals(value,mapper.fromValues(mapper.values(value)));
        }
        assertEquals(64,input.sha256().length());
    }
    @Test void rejectsAmbiguityBadNumbersInvalidDatesAndCancellation() throws Exception {
        assertThrows(IllegalArgumentException.class,()->read(header+row("300","300","1")+row("000300","300","1")));
        for(String value:new String[]{"NaN","Infinity","-","0x1.0p2","1e999"})
            assertThrows(IllegalArgumentException.class,()->read(header+row("000300","300",value)));
        assertThrows(IllegalArgumentException.class,()->read(header+row("000300","300.5","1")));
        assertThrows(java.time.DateTimeException.class,()->read(header+row("000300","300","1").replace("2004-12-31","2004-02-30")));
        assertThrows(IllegalArgumentException.class,()->read(header+"\"unclosed"));
        assertThrows(java.util.concurrent.CancellationException.class,()->new IndexCatalogFileSource()
                .read(folder.resolve("absent.csv"),Instant.EPOCH,()->true));
    }
    @Test void actualUserFileHasAllFieldsAndNoSilentRowsDropped() throws Exception {
        var path=Path.of("artifacts/java-migration/D003/source-catalog.csv");
        var input=new IndexCatalogFileSource().read(path,Instant.EPOCH,()->false);
        assertEquals(2343,input.rows().size());
        var mapper=new IndexCatalogMapper();
        for(var row:input.rows()) assertEquals(row,mapper.fromStorage(mapper.toStorage(row)));
        var prepared=DatasetWritePreparation.prepareWalReplace(IndexCatalogDataset.DEFINITION,input.rows(),
                mapper::values,new DatasetWritePreparation.Limits(5000,8*1024*1024));
        assertEquals(2343,prepared.rows().size());
        assertThrows(IllegalArgumentException.class,()->DatasetWritePreparation.prepareWalReplace(
                IndexCatalogDataset.DEFINITION,java.util.List.of(input.rows().getFirst(),input.rows().getFirst()),
                mapper::values,new DatasetWritePreparation.Limits(5000,8*1024*1024)));
    }
    @Test void boundsAndMalformedUtf8FailBeforeRowsAreAdmitted() throws Exception {
        var rowLimit=assertThrows(IllegalArgumentException.class,()->read(header+row("000300","300","1")
                .repeat(IndexCatalogFileSource.MAX_ROWS+1)));
        assertTrue(rowLimit.getMessage().contains("row bound"));
        var bytes=folder.resolve("oversized.csv");
        Files.write(bytes,new byte[IndexCatalogFileSource.MAX_BYTES+1]);
        var byteLimit=assertThrows(IllegalArgumentException.class,()->new IndexCatalogFileSource().read(bytes,Instant.EPOCH,()->false));
        assertTrue(byteLimit.getMessage().contains("byte bound"));
        Files.write(bytes,new byte[]{(byte)0xc3,(byte)0x28});
        assertThrows(java.nio.charset.CharacterCodingException.class,()->new IndexCatalogFileSource().read(bytes,Instant.EPOCH,()->false));
    }
}
