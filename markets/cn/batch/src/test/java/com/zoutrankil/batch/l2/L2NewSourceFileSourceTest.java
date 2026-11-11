package com.zoutrankil.batch.l2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.zoutrankil.batch.l2.archive.NewSourceCsvParser;
import com.zoutrankil.batch.l2.archive.DfcfCsvParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class L2NewSourceFileSourceTest {
    @TempDir Path root;

    @Test
    void discoversOnlyTheExplicitUniqueDayMapping() throws Exception {
        Path day = Files.createDirectory(root.resolve("20261105"));
        Files.createDirectory(day.resolve("000001"));
        Files.createDirectory(day.resolve("600000"));
        Path mapping = root.resolve("mapping.json");
        Files.writeString(mapping, """
                {"date":"20261105","candidates":{"000001":["000001.SZ"],"600000":["600000.SH"]}}
                """, StandardCharsets.UTF_8);
        var source = new L2NewSourceFileSource(mapping);
        var jobs = source.discover(day, new ArrayList<>());
        assertEquals(2, jobs.size());
        assertEquals("000001.SZ", jobs.get(0).symbol());
        assertEquals("600000.SH", jobs.get(1).symbol());
        Files.writeString(mapping, "{}", StandardCharsets.UTF_8);
        assertThrows(IOException.class, () -> source.discover(day, new ArrayList<>()));
    }

    @Test
    void rejectsAmbiguousExchangeAndMissingDirectory() throws Exception {
        Path day = Files.createDirectory(root.resolve("20261105"));
        Path mapping = root.resolve("mapping.json");
        Files.writeString(mapping, """
                {"date":"20261105","candidates":{"000001":["000001.SZ","000001.SH"]}}
                """, StandardCharsets.UTF_8);
        assertThrows(IOException.class, () -> new L2NewSourceFileSource(mapping));
        Files.writeString(mapping, """
                {"date":"20261105","candidates":{"000001":["000001.SZ"]}}
                """, StandardCharsets.UTF_8);
        var source = new L2NewSourceFileSource(mapping);
        assertThrows(IOException.class, () -> source.discover(day, new ArrayList<>()));
    }

    @Test
    void rejectsNegativeDealVolumeWithChineseHeader() throws Exception {
        Path day = Files.createDirectory(root.resolve("20261105"));
        Path symbol = Files.createDirectory(day.resolve("600000"));
        Files.writeString(symbol.resolve("逐笔成交.csv"), """
                交易所代码,自然日,时间,成交编号,叫买序号,叫卖序号,成交价格,成交数量,方向,行情频道号,流水号
                600000,20261105,92500000,1,1,2,1009,-1,0,5,1
                """, StandardCharsets.UTF_8);
        Exception error = assertThrows(IOException.class,
                () -> NewSourceCsvParser.load(day, "600000", "SH", "20261105"));
        assertTrue(error.getMessage().contains("negative deal volume"));
    }

    @Test
    void readsApprovedSideAndShanghaiProcessedOrders() throws Exception {
        Path day = Files.createDirectory(root.resolve("20261105"));
        Path symbol = Files.createDirectory(day.resolve("600000"));
        Files.writeString(symbol.resolve("逐笔成交.csv"), """
                SecuCode,TradingDay,DealTime,DealID,BuyID,SellID,Price,Volume,Side,Channel,BizIndex
                600000,20261105,93000000,1,1,2,1009,100,0,5,1
                600000,20261105,93000001,2,1,2,1009,200,1,5,2
                """, StandardCharsets.UTF_8);
        Files.writeString(symbol.resolve("逐笔委托.csv"), """
                SecuCode,TradingDay,OrderTime,OrderID,DBOrderID,OrderType,Price,Volume,LastPrice,Channel,BizIndex
                600000,20261105,92900000,1,1,0,1009,100,1009,5,1
                """, StandardCharsets.UTF_8);
        writeEmptySnapshot(symbol);
        var parsed = NewSourceCsvParser.load(day, "600000", "SH", "20261105");
        assertEquals(DfcfCsvParser.DealSide.BUY, parsed.deals().get(0).side());
        assertEquals(DfcfCsvParser.DealSide.SELL, parsed.deals().get(1).side());
        assertEquals("10.09", parsed.deals().get(0).priceCny().toPlainString());
        assertEquals(DfcfCsvParser.OrderKind.ADD_BUY, parsed.orders().get(0).kind());
    }

    @Test
    void readsShenzhenRawOrdersAndSynthesizesOneCancellation() throws Exception {
        Path day = Files.createDirectory(root.resolve("20261105"));
        Path symbol = Files.createDirectory(day.resolve("000001"));
        Files.writeString(symbol.resolve("逐笔成交.csv"), """
                SecuCode,TradingDay,DealTime,DealID,BuyID,SellID,Price,Volume,Side,Channel,BizIndex
                000001,20261105,93000001,2,1,0,0,50,-1,5,2
                """, StandardCharsets.UTF_8);
        Files.writeString(symbol.resolve("逐笔委托原始.csv"), """
                SecuCode,TradingDay,OrderTime,OrderID,DBOrderID,OrderType,Price,Volume,Channel,BizIndex
                000001,20261105,92900000,1,1,1,1009,100,5,1
                """, StandardCharsets.UTF_8);
        Files.writeString(symbol.resolve("逐笔委托.csv"), "此文件不能被深市解析\n", StandardCharsets.UTF_8);
        writeEmptySnapshot(symbol);
        var parsed = NewSourceCsvParser.load(day, "000001", "SZ", "20261105");
        assertEquals(2, parsed.orders().size());
        assertEquals(DfcfCsvParser.OrderKind.ADD_BUY, parsed.orders().get(0).kind());
        assertEquals(DfcfCsvParser.OrderKind.CANCEL_BUY, parsed.orders().get(1).kind());
        assertEquals("10.09", parsed.orders().get(1).priceCny().toPlainString());
    }

    private static void writeEmptySnapshot(Path symbol) throws IOException {
        var fields = new ArrayList<>(java.util.List.of("SecuCode", "TradingDay", "TickTime", "Price", "Volume", "Turnover"));
        for (String prefix : java.util.List.of("AskPrice", "AskVolume", "BidPrice", "BidVolume", "AskOrder", "BidOrder"))
            for (int i = 1; i <= 10; i++) fields.add(prefix + i);
        fields.addAll(java.util.List.of("TotalBidVolume", "TotalAskVolume", "TotalVolume", "TotalTurnover", "DealNum", "TotalDealNum", "WeightAskPrice", "WeightBidPrice", "TickTimeDiff"));
        Files.writeString(symbol.resolve("行情快照.csv"), String.join(",", fields) + "\n", StandardCharsets.UTF_8);
    }
}
