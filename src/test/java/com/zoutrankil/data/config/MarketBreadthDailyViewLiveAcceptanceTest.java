package com.zoutrankil.data.config;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.repository.*;
import com.zoutrankil.data.mapper.MarketBreadthDailyViewMapper;
import com.zoutrankil.data.service.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** D096 actual alias SELECTs: private real source, configured typed group, and formal invalid rejection. */
class MarketBreadthDailyViewLiveAcceptanceTest {
    @Test void realAliasPagesAndConfiguredReadGroupMatchEverySourceAggregateField() throws Exception {
        assumeTrue("true".equalsIgnoreCase(System.getenv("D096_LIVE_READ")));
        var jdbc=jdbc("127.0.0.1",18812,"admin","quest");
        var properties=new QuestDbProperties(); properties.setHost("127.0.0.1");
        properties.setPgPort(18812); properties.setQwpPort(19000); properties.setDatabase("qdb");
        new MarketBreadthDailyV1MaterializationPort(jdbc,properties,true).verifyPrivateInstance();
        var source=new MarketBreadthDailyV1MaterializationPort(jdbc,properties,false);
        var before=source.snapshot(); assertTrue(before.valid() && before.caughtUp());
        var reader=new QuestDbBoundedReader(jdbc);
        var repository=new MarketBreadthDailyViewReadRepository(reader);
        var from=LocalDate.of(2026,9,17); var to=LocalDate.of(2026,9,22);
        var expected=source.expected(from,to.minusDays(1)); assertEquals(3,expected.size());
        var actual=new ArrayList<MarketBreadthDailyView>(); DatasetReadCursor cursor=null;
        String version=null; int pages=0;
        do {
            var page=repository.findRange(from,to,1,cursor);
            if(version==null) version=page.sourceVersion(); else assertEquals(version,page.sourceVersion());
            actual.addAll(page.rows()); cursor=page.nextCursor(); assertTrue(++pages<=3);
        } while(cursor!=null);
        assertEquals(3,pages); assertEquals(3,actual.size());
        assertEquals(3,new HashSet<>(actual.stream().map(MarketBreadthDailyView::tradeDate).toList()).size());
        assertNotNull(version); assertTrue(version.contains("base-id:") && version.contains("mv-txn:"));
        for(int i=0;i<expected.size();i++) assertTrue(MarketBreadthDailyV1MaterializationPort.equivalent(expected.get(i),mv(actual.get(i))));
        assertEquals(actual.getFirst(),repository.findForDate(from).rows().getFirst());
        assertTrue(repository.findForDate(LocalDate.of(2026,9,20)).rows().isEmpty());
        var registry=new DatasetRegistry(List.of(repository,new MarketBreadthDailyV1ReadRepository(reader),new StockFactorReadRepository(reader)));
        var group=new ReadGroupConfiguration().readGroupReader(registry,reader);
        var query=new DatasetReadQuery(repository.definition().storageColumns(),Map.of(),"trade_date",from,to,31,null);
        var request=group.readRequest(Path.of("artifacts/java-migration/D096/commands/read-group-request.json"));
        assertEquals(query,request.members().getFirst().query());
        var grouped=group.read(request,()->false); assertTrue(grouped.complete());
        assertEquals(actual,grouped.require("breadth").typedPage(MarketBreadthDailyView.class).rows());
        assertEquals(ReadGroupReader.Status.CANCELLED,group.read(request,()->true).require("breadth").status());
        assertEquals(before,source.snapshot(),"Read acceptance must not change source or materialized output");
        var formalJdbc=jdbc(required("APP_QUESTDB_HOST"),Integer.parseInt(System.getenv().getOrDefault("APP_QUESTDB_PGPORT","8812")),required("APP_QUESTDB_USERNAME"),required("APP_QUESTDB_PASSWORD"));
        var formalReader=new QuestDbBoundedReader(formalJdbc); var formalRepo=new MarketBreadthDailyViewReadRepository(formalReader);
        assertThrows(IllegalStateException.class,()->formalRepo.findForDate(from));
        var formalRegistry=new DatasetRegistry(List.of(formalRepo,new MarketBreadthDailyV1ReadRepository(formalReader),new StockFactorReadRepository(formalReader)));
        var refused=new ReadGroupConfiguration().readGroupReader(formalRegistry,formalReader).read(request,()->false);
        assertFalse(refused.complete()); assertEquals(ReadGroupReader.Status.FAILED,refused.require("breadth").status());
        assertNull(refused.require("breadth").page());
        var mapper=new MarketBreadthDailyViewMapper();
        var evidence=new LinkedHashMap<String,Object>(); evidence.put("taskId","D096");
        evidence.put("rangeFromInclusive",from); evidence.put("rangeToExclusive",to);
        evidence.put("sourceRawRows",source.sourceRawRows(from,to.minusDays(1)));
        evidence.put("rows",actual.size()); evidence.put("fieldComparisons",actual.size()*7); evidence.put("pages",pages);
        evidence.put("actualAliasRows",actual.stream().map(row->mapper.values(row).asMap()).toList());
        evidence.put("sourceVersion",version); evidence.put("sourceSnapshot",before);
        evidence.put("configuredReadGroupMatched",true); evidence.put("cancelledGroupRejected",true);
        evidence.put("formalInvalidMvRejected",true); evidence.put("formalFailedMemberHasNoPage",true); evidence.put("formalWrittenRows",0);
        evidence.put("readWrites",0); evidence.put("baseRefreshJobId",MarketBreadthDailyV1JobService.JOB_ID);
        var path=Path.of("artifacts/java-migration/D096/commands/java-alias-read-acceptance-20261006.json");
        Files.createDirectories(path.getParent()); Files.writeString(path,JobDefinitionJson.mapper().writerWithDefaultPrettyPrinter().writeValueAsString(evidence));
    }
    private static MarketBreadthDailyV1 mv(MarketBreadthDailyView row) {
        return new MarketBreadthDailyV1(row.tradeDate(),row.stockCount(),row.upCount(),row.downCount(),row.flatCount(),row.avgPctChange(),row.totalAmountYi());
    }
    private static JdbcTemplate jdbc(String host,int port,String user,String password) {
        var ds=new DriverManagerDataSource(); ds.setDriverClassName("org.postgresql.Driver");
        ds.setUrl("jdbc:postgresql://"+host+":"+port+"/qdb?sslmode=disable"); ds.setUsername(user); ds.setPassword(password);
        var jdbc=new JdbcTemplate(ds); jdbc.setQueryTimeout(20); return jdbc;
    }
    private static String required(String name) {
        var value=System.getenv(name); if(value==null || value.isBlank()) throw new IllegalStateException("Missing setting: "+name); return value;
    }
}
