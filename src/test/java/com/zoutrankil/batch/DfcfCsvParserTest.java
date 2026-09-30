package com.zoutrankil.batch;

import org.junit.jupiter.api.Test;
import java.io.*;
import java.math.BigDecimal;
import java.nio.charset.Charset;
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
        assertEquals(100L,quotes.getFirst().levels().getFirst().bidVolume());
        assertEquals(0,quotes.getLast().tickTimeDiff());
    }

    @Test void wrongDateAndFractionalIntegerFieldsAreRejected() {
        String deal="自然日,时间,成交编号,成交价格,成交数量,BS标志,叫买序号,叫卖序号\n20260901,93000000,1,100000,1,B,11,21\n";
        assertThrows(IOException.class,()->DfcfCsvParser.scanDeals(stream(deal),"000001.SZ",DATE,4096,ignored->{}));
        String fractional=deal.replace("20260901","20260902").replace(",1,B,11,21",",1.5,B,11,21");
        assertThrows(IOException.class,()->DfcfCsvParser.scanDeals(stream(fractional),"000001.SZ",DATE,4096,ignored->{}));
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
