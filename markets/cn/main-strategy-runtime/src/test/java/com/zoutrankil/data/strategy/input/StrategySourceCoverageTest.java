package com.zoutrankil.data.strategy.input;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.time.LocalDate;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class StrategySourceCoverageTest {
    @TempDir Path root;
    final LocalDate day=LocalDate.of(2026,10,16),warmup=day.minusDays(460);
    final Map<String,Object> clocks=new TreeMap<>(Map.of("version",17));
    final ObjectMapper json=new ObjectMapper();
    Map<String,List<Map<String,Object>>> tables() {
        var tables=new TreeMap<String,List<Map<String,Object>>>();
        StrategySourceCoverage.KEYS.forEach((name,keys)->{
            var row=new TreeMap<String,Object>();keys.forEach(key->row.put(key,key.contains("date")||key.equals("ts")||key.equals("timestamp")?day.toString():"000001.SZ"));
            tables.put(name,List.of(row));
        });
        var extra=new TreeMap<String,Object>(tables.get("stock_daily").getFirst());extra.put("ts_code","000002.SZ");
        tables.put("stock_daily",List.of(tables.get("stock_daily").getFirst(),extra));
        return tables;
    }
    StrategySourceCoverage authority(Map<String,List<Map<String,Object>>> tables)throws Exception {
        var receipt=root.resolve("certified-source.json");Files.writeString(receipt,"已核验的独立来源样本");
        var sources=new TreeMap<String,Object>();
        for(var entry:StrategySourceCoverage.KEYS.entrySet())sources.put(entry.getKey(),Map.of(
                "expectedRows",tables.get(entry.getKey()).size(),
                "expectedKeysSha256",StrategySourceCoverage.keySha(tables.get(entry.getKey()),entry.getValue()),
                "certificate",receipt.getFileName().toString(),"certificateSha256",StrategySourceCoverage.sha(Files.readAllBytes(receipt))));
        byte[] bytes=json.writeValueAsBytes(Map.of("schema","java.strategy.source_coverage.v1","start",day.toString(),
                "end",day.toString(),"warmupStart",warmup.toString(),"sourceClocksSha256",StrategySourceCoverage.objectSha(clocks),"sources",sources));
        Path manifest=Files.write(root.resolve("coverage.json"),bytes);
        return new StrategySourceCoverage(manifest.toString(),StrategySourceCoverage.sha(bytes));
    }
    @Test void missingStockFailsEvenWhenDateRemainsAndSparseL2IsValid()throws Exception {
        var tables=tables();var authority=authority(tables);
        assertDoesNotThrow(()->authority.verify(day,day,warmup,tables,clocks));
        tables.put("stock_daily",tables.get("stock_daily").subList(0,1));
        assertThrows(IllegalStateException.class,()->authority.verify(day,day,warmup,tables,clocks));
    }
    @Test void explicitlyApprovedEmptyL2RangeIsValid()throws Exception {
        var tables=tables();tables.put("l2_raw",List.of());
        var authority=authority(tables);
        assertDoesNotThrow(()->authority.verify(day,day,warmup,tables,clocks));
        var row=Map.<String,Object>of("ts",day,"symbol","000001.SZ");
        tables.put("l2_raw",List.of(row));
        assertThrows(IllegalStateException.class,()->authority.verify(day,day,warmup,tables,clocks));
    }
    @Test void sameRowCountWithDifferentOrDuplicateKeysCannotPass()throws Exception {
        var tables=tables();var authority=authority(tables);var row=tables.get("stock_daily").getFirst();
        tables.put("stock_daily",List.of(row,row));
        assertThrows(IllegalStateException.class,()->authority.verify(day,day,warmup,tables,clocks));
        var wrong=new TreeMap<String,Object>(row);wrong.put("ts_code","600000.SH");
        tables.put("stock_daily",List.of(row,wrong));
        assertThrows(IllegalStateException.class,()->authority.verify(day,day,warmup,tables,clocks));
    }
    @Test void changedSourceReceiptDatabaseOrWindowBlocksPublication()throws Exception {
        var tables=tables();var authority=authority(tables);
        assertThrows(IllegalStateException.class,()->authority.verify(day,day,warmup,tables,Map.of("version",18)));
        assertThrows(IllegalStateException.class,()->authority.verify(day,day.plusDays(1),warmup,tables,clocks));
        Files.writeString(root.resolve("certified-source.json"),"changed");
        assertThrows(IllegalStateException.class,authority::revalidate);
    }
    @Test void missingUnpinnedOrAlteredManifestCannotAuthorizeScoring()throws Exception {
        assertThrows(IllegalStateException.class,()->new StrategySourceCoverage("",""));
        authority(tables());
        assertThrows(IllegalStateException.class,()->new StrategySourceCoverage(root.resolve("coverage.json").toString(),"0".repeat(64)));
        var reader=new QuestDbStrategyInputs(new org.springframework.jdbc.core.JdbcTemplate(
                org.mockito.Mockito.mock(javax.sql.DataSource.class)),10000);
        assertThrows(IllegalStateException.class,()->reader.read(day,day,460));
    }
}
