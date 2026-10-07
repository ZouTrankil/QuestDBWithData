package com.zoutrankil.batch;

import org.junit.jupiter.api.Test;
import java.io.*;
import java.math.BigDecimal;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.time.LocalDate;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class DfcfCsvParserTest {
    private static final Charset GB18030=Charset.forName("GB18030");
    private static final LocalDate DATE=LocalDate.of(2026,9,2);

    @Test void dealMappingScalesPriceAndRetainsCancelsAndUnclassifiedRows() throws Exception {
        String csv="自然日,时间,成交编号,成交价格,成交数量,BS标志,叫买序号,叫卖序号\n"
                +"20260902,93000000,1,100000,100,B,11,21\n"
                +"20260902,93000001,2,0,50,,12,0\n"
                +"20260902,93000002,3,100000,5,N,13,23\n";
        var deals=new ArrayList<DfcfCsvParser.Deal>();
        assertEquals(3,DfcfCsvParser.scanDeals(stream(csv),"000001.SZ",DATE,4096,deals::add));
        assertEquals(new BigDecimal("10.0000"),deals.getFirst().priceCny());
        assertEquals(DfcfCsvParser.DealSide.BUY,deals.getFirst().side());
        assertEquals(DfcfCsvParser.DealSide.BUY_CANCEL,deals.get(1).side());
        assertEquals(DfcfCsvParser.DealSide.UNKNOWN,deals.get(2).side());
        assertEquals("3",deals.get(2).dealId());
        assertThrows(IOException.class,()->DfcfCsvParser.scanDeals(stream(csv),"000001.SZ",DATE,16,ignored->{}));
    }

    @Test void shanghaiEtfWithWrongVendorSuffixUsesShanghaiOrderCodes() throws Exception {
        String csv="自然日,时间,交易所委托号,委托类型,委托代码,委托价格,委托数量\n"
                +"20260902,93000000,1,A,B,17050,100\n"
                +"20260902,93000001,2,A,S,17060,200\n"
                +"20260902,93000002,3,D,B,17050,50\n"
                +"20260902,93000003,4,D,S,17060,60\n";
        var orders=new ArrayList<DfcfCsvParser.Order>();
        assertEquals(4,DfcfCsvParser.scanOrders(stream(csv),"588220.SZ",DATE,4096,orders::add));
        assertEquals("588220.SH",orders.getFirst().symbol());
        assertEquals(List.of(0,10,-1,-11),orders.stream().map(DfcfCsvParser.Order::kuakeOrderType).toList());
        assertEquals(DfcfCsvParser.OrderKind.CANCEL_SELL,orders.getLast().kind());
        assertFalse(orders.getLast().submission());
        assertEquals(new BigDecimal("1.7050"),orders.getFirst().priceCny());
    }

    @Test void shenzhenOrderTypesAndSubmissionCodesMatchTheExistingSourceContract() throws Exception {
        String csv="自然日,时间,交易所委托号,委托类型,委托代码,委托价格,委托数量\n"
                +"20260902,93000000,101,0,B,100000,100\n"
                +"20260902,93000001,202,U,S,101000,200\n"
                +"20260902,93000002,303,X,B,102000,300\n";
        var orders=new ArrayList<DfcfCsvParser.Order>();
        assertEquals(3,DfcfCsvParser.scanOrders(stream(csv),"000001.SZ",DATE,4096,orders::add));
        assertEquals(Arrays.asList(1,13,null),orders.stream().map(DfcfCsvParser.Order::kuakeOrderType).toList());
        assertEquals(List.of(true,true,false),orders.stream().map(DfcfCsvParser.Order::submission).toList());
        assertEquals(DfcfCsvParser.OrderKind.UNKNOWN,orders.getLast().kind());
    }

    @Test void quoteMappingPreservesTenLevelsAndClampsOutOfOrderTickDelta() throws Exception {
        var headers=new ArrayList<>(List.of("自然日","时间","成交价","成交量","成交额","当日累计成交量","当日成交额","叫买总量","叫卖总量","加权平均叫买价","加权平均叫卖价"));
        for(int i=1;i<=10;i++)headers.add("申买价"+i);
        for(int i=1;i<=10;i++)headers.add("申卖价"+i);
        for(int i=1;i<=10;i++)headers.add("申买量"+i);
        for(int i=1;i<=10;i++)headers.add("申卖量"+i);
        String first=quoteRow(headers,"93000000"),second=quoteRow(headers,"92999999");
        String csv=String.join(",",headers)+"\n"+first+"\n"+second+"\n";
        var quotes=new ArrayList<DfcfCsvParser.Quote>();
        assertEquals(2,DfcfCsvParser.scanQuotes(stream(csv),"000001.SZ",DATE,8192,quotes::add));
        assertEquals(new BigDecimal("10.0000"),quotes.getFirst().priceCny());
        assertEquals(new BigDecimal("123.45"),quotes.getFirst().sourceTurnover());
        assertEquals(10,quotes.getFirst().levels().size());
        assertEquals(new BigDecimal("9.8999"),quotes.getFirst().levels().getFirst().bidPriceCny());
        assertEquals(new BigDecimal("100"),quotes.getFirst().levels().getFirst().bidVolume());
        assertEquals(0,quotes.getLast().tickTimeDiff());
    }

    @Test void wrongDateAndFractionalIntegerFieldsAreRejected() {
        String deal="自然日,时间,成交编号,成交价格,成交数量,BS标志,叫买序号,叫卖序号\n20260901,93000000,1,100000,1,B,11,21\n";
        assertThrows(IOException.class,()->DfcfCsvParser.scanDeals(stream(deal),"000001.SZ",DATE,4096,ignored->{}));
        String fractional=deal.replace("20260901","20260902").replace(",1,B,11,21",",1.5,B,11,21");
        assertThrows(IOException.class,()->DfcfCsvParser.scanDeals(stream(fractional),"000001.SZ",DATE,4096,ignored->{}));
    }

    @Test void productionProjectionFiltersUnknownsAndReconstructsShenzhenCancelPriceWithoutLookingAhead() throws Exception {
        String orderCsv="自然日,时间,交易所委托号,委托类型,委托代码,委托价格,委托数量\n"
                +"20260902,93000005,11,0,B,120000,100\n"
                +"20260902,93000001,11,0,B,100000,100\n"
                +"20260902,93000002,11,0,S,990000,100\n"
                +"20260902,93000004,11,0,B,0,100\n"
                +"20260902,93000000,90,X,B,100000,100\n";
        String dealCsv="自然日,时间,成交编号,成交价格,成交数量,BS标志,叫买序号,叫卖序号\n"
                +"20260902,93000003,1,0,30,,11,0\n"
                +"20260902,93000000,2,0,20,,11,0\n"
                +"20260902,93000005,3,0,40,,11,0\n"
                +"20260902,93000003,4,100000,1,N,11,21\n";
        var deals=new ArrayList<DfcfCsvParser.Deal>();var orders=new ArrayList<DfcfCsvParser.Order>();
        DfcfCsvParser.scanDeals(stream(dealCsv),"000001.SZ",DATE,4096,deals::add);
        DfcfCsvParser.scanOrders(stream(orderCsv),"000001.SZ",DATE,4096,orders::add);
        var day=DfcfCsvParser.normalizeProductionDay("000001.SZ",DATE,deals,orders,List.of());
        assertEquals(4,day.rawDealRows());assertEquals(5,day.rawOrderRows());
        assertEquals(3,day.deals().size());assertEquals(7,day.orders().size());
        var cancels=day.orders().stream().filter(row->!row.submission()).toList();
        assertEquals(List.of(93000000,93000003,93000005),cancels.stream().map(DfcfCsvParser.Order::time).toList());
        assertEquals(List.of(new BigDecimal("0.0000"),new BigDecimal("10.0000"),new BigDecimal("12.0000")),
                cancels.stream().map(DfcfCsvParser.Order::priceCny).toList());
        assertEquals(DfcfCsvParser.OrderKind.ADD_BUY,day.orders().get(day.orders().size()-2).kind());
        assertEquals(DfcfCsvParser.OrderKind.CANCEL_BUY,day.orders().getLast().kind());
    }

    @Test void cancellationPrecedenceAndShanghaiProductionProjectionMatchPython() throws Exception {
        String csv="自然日,时间,成交编号,成交价格,成交数量,BS标志,叫买序号,叫卖序号\n"
                +"20260902,93000000,1,0,1,,11,21\n";
        var deals=new ArrayList<DfcfCsvParser.Deal>();
        DfcfCsvParser.scanDeals(stream(csv),"600000.SH",DATE,4096,deals::add);
        assertEquals(DfcfCsvParser.DealSide.SELL_CANCEL,deals.getFirst().side());
        var day=DfcfCsvParser.normalizeProductionDay("600000.SH",DATE,deals,List.of(),List.of());
        assertEquals(1,day.deals().size());assertTrue(day.orders().isEmpty());
    }

    @Test void productionTimeSortingIsStableAndKeepsOriginalQuoteDelta() throws Exception {
        var first=new DfcfCsvParser.Deal("000001.SZ",DATE,93000000,"1","B","11","21",BigDecimal.ONE,1,DfcfCsvParser.DealSide.BUY);
        var tied=new DfcfCsvParser.Deal("000001.SZ",DATE,93000000,"2","S","11","21",BigDecimal.ONE,1,DfcfCsvParser.DealSide.SELL);
        var invalid=new DfcfCsvParser.Deal("000001.SZ",DATE,99999999,"3","B","11","21",BigDecimal.ONE,1,DfcfCsvParser.DealSide.BUY);
        var day=DfcfCsvParser.normalizeProductionDay("000001.SZ",DATE,List.of(invalid,first,tied),List.of(),List.of());
        assertEquals(List.of("1","2","3"),day.deals().stream().map(DfcfCsvParser.Deal::dealId).toList());
        assertNull(DfcfCsvParser.timestamp(DATE,99999999));
        assertEquals(DATE.atTime(9,31,0),DfcfCsvParser.timestamp(DATE,93060000));
    }

    @Test void productionNumericCoercionMatchesPandasWhileAuditScannersStayStrict() throws Exception {
        String deals="自然日,时间,成交编号,成交价格,成交数量,BS标志,叫买序号,叫卖序号\n"
                +"20260902,,1,garbage,NA,,11,garbage\n"
                +"20260902,93000000.0,2,100000,,B,bad,21\n"
                +"20260902,93000001,3,100000,2,N,11,21\n";
        String orders="自然日,时间,交易所委托号,委托类型,委托代码,委托价格,委托数量\n"
                +"20260902,0,11,0,B,130000,100\n"
                +"20260902,,bad,U,S,garbage,NA\n"
                +"20260902,93000000,12,0,B,,bad\n";
        var headers=new ArrayList<>(List.of("自然日","时间","成交价","成交量","成交额","当日累计成交量","当日成交额","叫买总量","叫卖总量","加权平均叫买价","加权平均叫卖价"));
        for(int i=1;i<=10;i++)headers.add("申买价"+i);for(int i=1;i<=10;i++)headers.add("申卖价"+i);
        for(int i=1;i<=10;i++)headers.add("申买量"+i);for(int i=1;i<=10;i++)headers.add("申卖量"+i);
        String invalid=headers.stream().map(name->name.equals("自然日")?"20260902":name.equals("时间")?"":"bad").collect(java.util.stream.Collectors.joining(","));
        String quotes=String.join(",",headers)+"\n"+quoteRow(headers,"93000000")+"\n"+invalid+"\n"+quoteRow(headers,"93000010")+"\n";
        var folder=Files.createTempDirectory("dfcf-production-coercion-");
        try {
            Files.writeString(folder.resolve("逐笔成交.csv"),deals,GB18030);
            Files.writeString(folder.resolve("逐笔委托.csv"),orders,GB18030);
            Files.writeString(folder.resolve("行情.csv"),quotes,GB18030);
            var day=DfcfCsvParser.readProductionDay(folder,"000001.SZ",DATE,8192);
            assertEquals(3,day.rawDealRows());assertEquals(2,day.deals().size());assertEquals(4,day.orders().size());
            var cancel=day.deals().getFirst();assertEquals(0,cancel.time());assertEquals(BigDecimal.ZERO,cancel.volume());
            assertEquals("0",cancel.sellOrderId());assertEquals(DfcfCsvParser.DealSide.BUY_CANCEL,cancel.side());
            assertEquals("0",day.deals().getLast().buyOrderId());assertEquals(BigDecimal.ZERO,day.deals().getLast().volume());
            var missingOrder=day.orders().stream().filter(row->row.orderId().equals("0")).findFirst().orElseThrow();
            assertNull(missingOrder.priceCny());assertEquals(BigDecimal.ZERO,missingOrder.volume());assertEquals(0,missingOrder.time());
            var synthesized=day.orders().stream().filter(row->row.kind()==DfcfCsvParser.OrderKind.CANCEL_BUY).findFirst().orElseThrow();
            assertEquals(new BigDecimal("0.0000"),synthesized.priceCny());
            var missingQuote=day.quotes().getFirst();assertNull(missingQuote.priceCny());assertEquals(BigDecimal.ZERO,missingQuote.volume());
            assertEquals(BigDecimal.ZERO,missingQuote.sourceTurnover());assertNull(missingQuote.levels().getFirst().bidPriceCny());
            assertEquals(BigDecimal.ZERO,missingQuote.levels().getFirst().bidVolume());assertEquals(0,day.quotes().getLast().tickTimeDiff());
            Files.writeString(folder.resolve("逐笔成交.csv"),deals.replace(",NA,,",",1.5,,"),GB18030);
            assertEquals(new BigDecimal("1.5"),DfcfCsvParser.readProductionDay(folder,"000001.SZ",DATE,8192).deals().getFirst().volume());
        } finally {
            Files.deleteIfExists(folder.resolve("逐笔成交.csv"));Files.deleteIfExists(folder.resolve("逐笔委托.csv"));
            Files.deleteIfExists(folder.resolve("行情.csv"));Files.deleteIfExists(folder);
        }
        assertThrows(IOException.class,()->DfcfCsvParser.scanDeals(stream(deals),"000001.SZ",DATE,8192,ignored->{}));
        assertThrows(IOException.class,()->DfcfCsvParser.scanOrders(stream(orders),"000001.SZ",DATE,8192,ignored->{}));
        assertThrows(IOException.class,()->DfcfCsvParser.scanQuotes(stream(quotes),"000001.SZ",DATE,8192,ignored->{}));
    }

    @Test void productionPreservesFractionalQuantitiesAcrossAllThreeTablesAndSynthesizedCancels() throws Exception {
        String deals="自然日,时间,成交编号,成交价格,成交数量,BS标志,叫买序号,叫卖序号\n"
                +"20260902,93000000,1,100000,100.5,B,11,21\n"
                +"20260902,93000010,2,0,20.25,,11,0\n";
        String orders="自然日,时间,交易所委托号,委托类型,委托代码,委托价格,委托数量\n"
                +"20260902,92500000,11,0,B,100000,200.75\n";
        var headers=new ArrayList<>(List.of("自然日","时间","成交价","成交量","成交额","当日累计成交量","当日成交额","叫买总量","叫卖总量","加权平均叫买价","加权平均叫卖价"));
        for(int i=1;i<=10;i++)headers.add("申买价"+i);for(int i=1;i<=10;i++)headers.add("申卖价"+i);
        for(int i=1;i<=10;i++)headers.add("申买量"+i);for(int i=1;i<=10;i++)headers.add("申卖量"+i);
        var values=new ArrayList<>(Arrays.asList(quoteRow(headers,"93000000").split(",",-1)));
        for(String name:List.of("成交量","当日累计成交量","叫买总量","叫卖总量","申买量1","申卖量1"))values.set(headers.indexOf(name),"123.125");
        String quotes=String.join(",",headers)+"\n"+String.join(",",values)+"\n";
        var folder=Files.createTempDirectory("dfcf-fractional-quantity-");
        try {
            Files.writeString(folder.resolve("逐笔成交.csv"),deals,GB18030);Files.writeString(folder.resolve("逐笔委托.csv"),orders,GB18030);
            Files.writeString(folder.resolve("行情.csv"),quotes,GB18030);
            var day=DfcfCsvParser.readProductionDay(folder,"000001.SZ",DATE,8192);
            assertEquals(new BigDecimal("100.5"),day.deals().getFirst().volume());
            assertEquals(new BigDecimal("200.75"),day.orders().getFirst().volume());
            assertEquals(new BigDecimal("20.25"),day.orders().getLast().volume());
            var quote=day.quotes().getFirst();var expected=new BigDecimal("123.125");
            assertEquals(expected,quote.volume());assertEquals(expected,quote.totalVolume());
            assertEquals(expected,quote.totalBidVolume());assertEquals(expected,quote.totalAskVolume());
            assertEquals(expected,quote.levels().getFirst().bidVolume());assertEquals(expected,quote.levels().getFirst().askVolume());
        } finally {
            Files.deleteIfExists(folder.resolve("逐笔成交.csv"));Files.deleteIfExists(folder.resolve("逐笔委托.csv"));
            Files.deleteIfExists(folder.resolve("行情.csv"));Files.deleteIfExists(folder);
        }
        assertThrows(IOException.class,()->DfcfCsvParser.scanDeals(stream(deals),"000001.SZ",DATE,8192,ignored->{}));
        assertThrows(IOException.class,()->DfcfCsvParser.scanOrders(stream(orders),"000001.SZ",DATE,8192,ignored->{}));
        assertThrows(IOException.class,()->DfcfCsvParser.scanQuotes(stream(quotes),"000001.SZ",DATE,8192,ignored->{}));
    }

    @Test void productionTextualDealIdentifiersAndMissingRawTimesMatchCancellationPriceLineage() throws Exception {
        String deals="自然日,时间,成交编号,成交价格,成交数量,BS标志,叫买序号,叫卖序号\n"
                +"20260902,93000000,abc,0,20.5,,7,0\n"
                +"20260902,broken,1.5,0,10.25,,8,0\n";
        String orders="自然日,时间,交易所委托号,委托类型,委托代码,委托价格,委托数量\n"
                +"20260902,broken,7,0,B,500000,100.5\n"
                +"20260902,0,8,U,B,300000,100\n";
        var folder=Files.createTempDirectory("dfcf-missing-time-lineage-");
        try {
            Files.writeString(folder.resolve("逐笔成交.csv"),deals,GB18030);Files.writeString(folder.resolve("逐笔委托.csv"),orders,GB18030);
            var day=DfcfCsvParser.readProductionDay(folder,"000001.SZ",DATE,8192);
            assertEquals(List.of("1.5","abc"),day.deals().stream().map(DfcfCsvParser.Deal::dealId).toList());
            assertFalse(day.deals().getFirst().sourceTimePresent());
            var cancels=day.orders().stream().filter(row->!row.submission()).toList();assertEquals(2,cancels.size());
            assertEquals(0,cancels.getFirst().priceCny().signum());assertEquals(0,cancels.getLast().priceCny().signum());
            assertFalse(cancels.getFirst().sourceTimePresent());assertTrue(cancels.getLast().sourceTimePresent());
        } finally {
            Files.deleteIfExists(folder.resolve("逐笔成交.csv"));Files.deleteIfExists(folder.resolve("逐笔委托.csv"));Files.deleteIfExists(folder);
        }
        assertThrows(IOException.class,()->DfcfCsvParser.scanDeals(stream(deals),"000001.SZ",DATE,8192,ignored->{}));
    }

    @Test void productionNonfiniteSourceNumericsAreRejectedExplicitly() throws Exception {
        var folder=Files.createTempDirectory("dfcf-nonfinite-admission-");
        try {
            for(String value:List.of("inf","-Infinity","+INF","1e309")) {
                String csv="自然日,时间,成交编号,成交价格,成交数量,BS标志,叫买序号,叫卖序号\n20260902,93000000,1,"+value+",100,B,11,21\n";
                Files.writeString(folder.resolve("逐笔成交.csv"),csv,GB18030);
                var failure=assertThrows(IOException.class,()->DfcfCsvParser.readProductionDay(folder,"000001.SZ",DATE,8192));
                assertTrue(failure.getMessage().contains("Nonfinite"));
            }
        } finally {Files.deleteIfExists(folder.resolve("逐笔成交.csv"));Files.deleteIfExists(folder);}
    }

    @Test void rawQuantityJsonStaysIntegralAndProductionTimeProvenanceIsNotSerialized() throws Exception {
        var mapper=new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules();
        var raw=new DfcfCsvParser.Deal("000001.SZ",DATE,93000000,"1","B","11","21",BigDecimal.TEN,Long.MAX_VALUE,DfcfCsvParser.DealSide.BUY);
        String json=mapper.writeValueAsString(raw);var node=mapper.readTree(json);
        assertFalse(node.has("sourceTimePresent"));assertTrue(node.path("volume").isIntegralNumber());
        assertEquals(Long.MAX_VALUE,node.path("volume").longValue());assertTrue(json.contains("\"volume\":9223372036854775807"));
    }

    private static InputStream stream(String value) { return new ByteArrayInputStream(value.getBytes(GB18030)); }
    private static String quoteRow(List<String> headers,String time) {
        var values=new HashMap<String,String>();values.put("自然日","20260902");values.put("时间",time);values.put("成交价","100000");
        values.put("成交量","1");values.put("成交额","123.45");values.put("当日累计成交量","10");values.put("当日成交额","1234.50");
        values.put("叫买总量","500");values.put("叫卖总量","600");values.put("加权平均叫买价","99000");values.put("加权平均叫卖价","101000");
        for(int i=1;i<=10;i++){values.put("申买价"+i,Integer.toString(99000-i));values.put("申卖价"+i,Integer.toString(101000+i));
            values.put("申买量"+i,Integer.toString(i*100));values.put("申卖量"+i,Integer.toString(i*200));}
        return String.join(",",headers.stream().map(values::get).toList());
    }
}
