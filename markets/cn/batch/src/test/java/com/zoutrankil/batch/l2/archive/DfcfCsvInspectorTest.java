package com.zoutrankil.batch.l2.archive;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.PushbackReader;
import java.io.StringReader;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Random;

class DfcfCsvInspectorTest {
    private final DfcfCsvInspector inspector = new DfcfCsvInspector();

    @Test
    void bufferedReaderMatchesOriginalForQuotesBoundariesAndMalformedRecords() {
        var cases = new ArrayList<>(java.util.List.of(
                "", "a,b\r\n", "\"a\"\"b\",c\r\nx,y", "\"a\nb\",c\rZ",
                "\"unterminated", "a\"b", "\"x\"z", ",,\n",
                "x".repeat(65535) + "\r\ny", "\"" + "a".repeat(65534) + "\"\"b\",c",
                "a,".repeat(256) + "z", "x".repeat(2_000_001)));
        var random = new Random(23);
        String alphabet = "a,\"\r\n中";
        for (int i = 0; i < 10000; i++) {
            var text = new StringBuilder();
            for (int n = random.nextInt(100); n > 0; n--)
                text.append(alphabet.charAt(random.nextInt(alphabet.length())));
            cases.add(text.toString());
        }
        for (String text : cases) assertEquals(parse(text, false), parse(text, true));
    }

    private static String parse(String text, boolean buffered) {
        try (PushbackReader reader = buffered
                ? new DfcfCsvInspector.BufferedPushbackReader(new StringReader(text))
                : new PushbackReader(new BufferedReader(new StringReader(text), 65536), 1)) {
            var rows = new ArrayList<java.util.List<String>>();
            java.util.List<String> row;
            while ((row = DfcfCsvInspector.readRecord(reader)) != null) rows.add(row);
            return "OK:" + rows;
        } catch (IOException failure) {
            return "ERROR:" + failure.getMessage();
        }
    }

    @Test
    void streamsGb18030CsvWithQuotedDelimiterAndMultilineField() throws Exception {
        String input =
                "自然日,时间,成交编号,成交价格,成交数量,BS标志,叫买序号,叫卖序号\r\n"
                        + "20260928,93000000,1,100,10,\"B,side\ncontinued\",1,2\r\n";
        byte[] bytes = input.getBytes(DfcfCsvInspector.ENCODING);
        var result =
                inspector.inspect(
                        "20260928\\000001.SZ\\逐笔成交.csv",
                        new ByteArrayInputStream(bytes),
                        LocalDate.of(2026, 9, 28),
                        bytes.length);
        assertEquals(1, result.rowCount());
        assertEquals(bytes.length, result.rawBytes());
        assertEquals(0, result.tradeDateMismatchRows());
        assertEquals(64, result.sha256().length());
    }

    @Test
    void recordsSourceDateMismatchInsteadOfTreatingItAsEmpty() throws Exception {
        byte[] bytes =
                ("自然日,时间,成交编号,成交价格,成交数量,BS标志,叫买序号,叫卖序号\n" + "20260927,93000000,1,100,10,B,1,2\n")
                        .getBytes(DfcfCsvInspector.ENCODING);
        var result =
                inspector.inspect(
                        "20260928/000001.SZ/逐笔成交.csv",
                        new ByteArrayInputStream(bytes),
                        LocalDate.of(2026, 9, 28),
                        1024);
        assertEquals(1, result.rowCount());
        assertEquals(1, result.tradeDateMismatchRows());
    }

    @Test
    void rejectsMissingFieldsMalformedQuotingAndWiderRows() {
        byte[] missing = "自然日,时间\n20260928,93000000\n".getBytes(DfcfCsvInspector.ENCODING);
        byte[] width =
                "自然日,时间,成交编号,成交价格,成交数量,BS标志,叫买序号,叫卖序号\n20260928,93000000\n"
                        .getBytes(DfcfCsvInspector.ENCODING);
        byte[] quote =
                "自然日,时间,成交编号,成交价格,成交数量,BS标志,叫买序号,叫卖序号\n20260928,93000000,1,100,10,\"B,1,2\n"
                        .getBytes(DfcfCsvInspector.ENCODING);
        assertThrows(
                IOException.class,
                () ->
                        inspector.inspect(
                                "20260928/000001.SZ/逐笔成交.csv",
                                new ByteArrayInputStream(missing),
                                LocalDate.of(2026, 9, 28),
                                1024));
        assertThrows(
                IOException.class,
                () ->
                        inspector.inspect(
                                "20260928/000001.SZ/逐笔成交.csv",
                                new ByteArrayInputStream(width),
                                LocalDate.of(2026, 9, 28),
                                1024));
        assertThrows(
                IOException.class,
                () ->
                        inspector.inspect(
                                "20260928/000001.SZ/逐笔成交.csv",
                                new ByteArrayInputStream(quote),
                                LocalDate.of(2026, 9, 28),
                                1024));
    }
}
