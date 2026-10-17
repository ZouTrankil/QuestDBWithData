package com.zoutrankil.batch.writer;

import com.zoutrankil.batch.domain.BusinessState;
import com.zoutrankil.batch.domain.RunRequest;
import com.zoutrankil.batch.support.Json;
import com.zoutrankil.batch.writer.QuestDbTablePort;
import static com.zoutrankil.batch.testsupport.NativeSourceFixtures.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.zoutrankil.data.service.PageExecutor;
import com.zoutrankil.data.domain.PageContract;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import com.zoutrankil.batch.source.SourceCollector;
import com.zoutrankil.batch.source.SourceContract;

class QuestDbRowComparisonTest {
    @TempDir Path archive;
    @Test void portfolioSeparatesPublicationPeriodAndStableTechnicalTimestamp() throws Exception {
        var contract=SourceContract.load("etf_portfolio");var collector=new SourceCollector(archive);
        var first=row("etf_portfolio","510300.SH");first.put("end_date",Json.MAPPER.valueToTree("20260630"));
        first.put("symbol",Json.MAPPER.valueToTree("600000.SH"));
        var second=new LinkedHashMap<>(first);second.put("end_date",Json.MAPPER.valueToTree("20260331"));
        var request=request("etf_portfolio",Set.of("510300.SH"));
        var result=collector.collect(request,p -> new PageExecutor.Page(List.of(first,second),null,false,null));
        assertEquals(2,result.rows());assertEquals(BusinessState.VERIFYING,result.state());
        var repeated=collector.collect(request,p -> new PageExecutor.Page(List.of(second,first),null,false,null));
        assertEquals(result.fingerprint(),repeated.fingerprint());
        var rows=collector.read(result.fingerprint()).rows();
        assertFalse(rows.getFirst().containsKey("update_time"));
        assertNotEquals(contract.key(rows.getFirst()),contract.key(rows.getLast()));
        Instant created=Instant.parse("2026-09-29T00:00:00.123456789Z");
        var physical=rows.stream().map(r -> contract.physicalRow(r,created)).toList();
        assertEquals("2026-09-29T00:00:00.123456Z",physical.getFirst().get("update_time"));
        var encoded=QuestDbTablePort.encode(contract,"jdb_test_portfolio_fixture",physical);
        assertArrayEquals(encoded,QuestDbTablePort.encode(contract,"jdb_test_portfolio_fixture",rows.stream().map(r -> contract.physicalRow(r,created)).toList()));
        assertTrue(new String(encoded,java.nio.charset.StandardCharsets.UTF_8).contains("update_time=1790640000123456t"));
        var actual=Json.MAPPER.createArrayNode();
        for(var r:physical) {
            var values=actual.addArray();
            for(var c:contract.columns()) {
                Object value=r.get(c.target());
                if(c.type().startsWith("TIMESTAMP") && value.toString().length()==10) value=value+"T00:00:00Z";
                values.add(Json.MAPPER.valueToTree(value));
            }
        }
        assertTrue(QuestDbTablePort.matches(contract,physical,actual));
        actual.set(1,actual.get(0));assertFalse(QuestDbTablePort.matches(contract,physical,actual));
        first.put("end_date",Json.MAPPER.valueToTree("20260930"));
        assertThrows(PageExecutor.Incomplete.class,() -> collector.collect(request,p -> new PageExecutor.Page(List.of(first),null,false,null)));
        assertTrue(contract.validCode("001753.OF"));assertFalse(SourceContract.load("daily").validCode("001753.OF"));
    }
    @Test void physicalMatchingUsesDateKeyAndChecksAllAggregateValues() {
        var contract=SourceContract.load("moneyflow_hsgt");
        var normalized=contract.normalize(row("moneyflow_hsgt",null),LocalDate.of(2026,9,28),Set.of());
        var actual=Json.MAPPER.createArrayNode();var values=actual.addArray();
        for(var column:contract.columns()) {
            if(column.key()) values.add("2026-09-28T00:00:00.000000Z");else values.add(12.25);
        }
        assertTrue(QuestDbTablePort.matches(contract,List.of(normalized),actual));
        values.set(0,Json.MAPPER.valueToTree("2026-09-27T00:00:00.000000Z"));
        assertFalse(QuestDbTablePort.matches(contract,List.of(normalized),actual));
        values.set(0,Json.MAPPER.valueToTree("2026-09-28T00:00:00.000000Z"));
        values.set(6,Json.MAPPER.valueToTree(12.26));
        assertFalse(QuestDbTablePort.matches(contract,List.of(normalized),actual));
    }
}
