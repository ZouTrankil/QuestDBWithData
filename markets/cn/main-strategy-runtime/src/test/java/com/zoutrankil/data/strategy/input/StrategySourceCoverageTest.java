package com.zoutrankil.data.strategy.input;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import javax.sql.DataSource;

class StrategySourceCoverageTest {
    @TempDir Path root;
    final LocalDate day = LocalDate.of(2026, 10, 16), warmup = day.minusDays(460);
    final Map<String, Object> clocks = new TreeMap<>(Map.of("version", 17));
    final ObjectMapper json = new ObjectMapper();

    Map<String, List<Map<String, Object>>> tables() {
        var tables = new TreeMap<String, List<Map<String, Object>>>();
        StrategySourceCoverage.KEYS.forEach(
                (name, keys) -> {
                    var row = new TreeMap<String, Object>();
                    keys.forEach(
                            key ->
                                    row.put(
                                            key,
                                            key.contains("date")
                                                            || key.equals("ts")
                                                            || key.equals("timestamp")
                                                    ? day.toString()
                                                    : "000001.SZ"));
                    tables.put(name, List.of(row));
                });
        var extra = new TreeMap<String, Object>(tables.get("stock_daily").getFirst());
        extra.put("ts_code", "000002.SZ");
        tables.put("stock_daily", List.of(tables.get("stock_daily").getFirst(), extra));
        return tables;
    }

    StrategySourceCoverage authority(Map<String, List<Map<String, Object>>> tables)
            throws Exception {
        return authority(tables, StrategyInputSchema.V1);
    }

    StrategySourceCoverage authority(
            Map<String, List<Map<String, Object>>> tables, StrategyInputSchema schema)
            throws Exception {
        var receipt = root.resolve("certified-source.json");
        Files.writeString(receipt, "已核验的独立来源样本");
        var sources = new TreeMap<String, Object>();
        for (var entry : schema.keys().entrySet()) {
            var item = new TreeMap<String, Object>();
            item.put("expectedRows", tables.get(entry.getKey()).size());
            item.put(
                    "expectedKeysSha256",
                    schema.keySha(entry.getKey(), tables.get(entry.getKey())));
            item.put("certificate", receipt.getFileName().toString());
            item.put("certificateSha256", StrategySourceCoverage.sha(Files.readAllBytes(receipt)));
            if (schema == StrategyInputSchema.V2) item.put("businessKeys", entry.getValue());
            sources.put(entry.getKey(), item);
        }
        var document = new TreeMap<String, Object>();
        document.put("schema", schema.coverageSchema());
        document.put("start", day.toString());
        document.put("end", day.toString());
        document.put("warmupStart", warmup.toString());
        document.put("sourceClocksSha256", StrategySourceCoverage.objectSha(clocks));
        document.put("sources", sources);
        if (schema == StrategyInputSchema.V2) {
            document.put("inputSchema", schema.inputSchema());
            document.put("keyEncoding", schema.keyEncoding());
        }
        byte[] bytes = json.writeValueAsBytes(document);
        Path manifest =
                Files.write(
                        root.resolve(
                                schema == StrategyInputSchema.V1
                                        ? "coverage.json"
                                        : "coverage-v2.json"),
                        bytes);
        return new StrategySourceCoverage(manifest.toString(), StrategySourceCoverage.sha(bytes));
    }

    @Test
    void missingStockFailsEvenWhenDateRemainsAndSparseL2IsValid() throws Exception {
        var tables = tables();
        var authority = authority(tables);
        assertDoesNotThrow(() -> authority.verify(day, day, warmup, tables, clocks));
        tables.put("stock_daily", tables.get("stock_daily").subList(0, 1));
        assertThrows(
                IllegalStateException.class,
                () -> authority.verify(day, day, warmup, tables, clocks));
    }

    @Test
    void explicitlyApprovedEmptyL2RangeIsValid() throws Exception {
        var tables = tables();
        tables.put("l2_raw", List.of());
        var authority = authority(tables);
        assertDoesNotThrow(() -> authority.verify(day, day, warmup, tables, clocks));
        var row = Map.<String, Object>of("ts", day, "symbol", "000001.SZ");
        tables.put("l2_raw", List.of(row));
        assertThrows(
                IllegalStateException.class,
                () -> authority.verify(day, day, warmup, tables, clocks));
    }

    @Test
    void sameRowCountWithDifferentOrDuplicateKeysCannotPass() throws Exception {
        var tables = tables();
        var authority = authority(tables);
        var row = tables.get("stock_daily").getFirst();
        tables.put("stock_daily", List.of(row, row));
        assertThrows(
                IllegalStateException.class,
                () -> authority.verify(day, day, warmup, tables, clocks));
        var wrong = new TreeMap<String, Object>(row);
        wrong.put("ts_code", "600000.SH");
        tables.put("stock_daily", List.of(row, wrong));
        assertThrows(
                IllegalStateException.class,
                () -> authority.verify(day, day, warmup, tables, clocks));
    }

    @Test
    void changedSourceReceiptDatabaseOrWindowBlocksPublication() throws Exception {
        var tables = tables();
        var authority = authority(tables);
        assertThrows(
                IllegalStateException.class,
                () -> authority.verify(day, day, warmup, tables, Map.of("version", 18)));
        assertThrows(
                IllegalStateException.class,
                () -> authority.verify(day, day.plusDays(1), warmup, tables, clocks));
        Files.writeString(root.resolve("certified-source.json"), "changed");
        assertThrows(IllegalStateException.class, authority::revalidate);
    }

    @Test
    void missingUnpinnedOrAlteredManifestCannotAuthorizeScoring() throws Exception {
        assertThrows(IllegalStateException.class, () -> new StrategySourceCoverage("", ""));
        authority(tables());
        assertThrows(
                IllegalStateException.class,
                () ->
                        new StrategySourceCoverage(
                                root.resolve("coverage.json").toString(), "0".repeat(64)));
        var reader =
                new QuestDbStrategyInputs(new JdbcTemplate(Mockito.mock(DataSource.class)), 10000);
        assertThrows(IllegalStateException.class, () -> reader.read(day, day, 460));
    }

    Map<String, List<Map<String, Object>>> standardTables() {
        var result = new TreeMap<String, List<Map<String, Object>>>();
        var legacy = tables();
        for (var entry : StrategyInputSchema.V1.keys().entrySet()) {
            var fields = StrategyInputSchema.V2.keys().get(entry.getKey());
            result.put(
                    entry.getKey(),
                    legacy.get(entry.getKey()).stream()
                            .map(
                                    row -> {
                                        var normalized = new TreeMap<String, Object>();
                                        for (int i = 0; i < fields.size(); i++)
                                            normalized.put(
                                                    fields.get(i),
                                                    row.get(entry.getValue().get(i)));
                                        return (Map<String, Object>) normalized;
                                    })
                            .toList());
        }
        return result;
    }

    @Test
    void versionTwoVerifiesCanonicalKeysAndCannotAcceptALegacyDigest() throws Exception {
        var tables = standardTables();
        var authority = authority(tables, StrategyInputSchema.V2);
        assertEquals(StrategyInputSchema.V2, authority.inputSchema());
        assertDoesNotThrow(() -> authority.verify(day, day, warmup, tables, clocks));
        Path path = root.resolve("coverage-v2.json");
        var document = json.readTree(Files.readAllBytes(path));
        ((ObjectNode) document.path("sources").path("etf_daily"))
                .put(
                        "expectedKeysSha256",
                        StrategySourceCoverage.keySha(
                                tables.get("etf_daily"),
                                StrategyInputSchema.V2.keys().get("etf_daily")));
        byte[] bytes = json.writeValueAsBytes(document);
        Path changed = Files.write(root.resolve("renamed-v1-digest.json"), bytes);
        var relabelled =
                new StrategySourceCoverage(changed.toString(), StrategySourceCoverage.sha(bytes));
        var failure =
                assertThrows(
                        IllegalStateException.class,
                        () -> relabelled.verify(day, day, warmup, tables, clocks));
        assertTrue(failure.getMessage().contains("etf_daily"));
    }

    @Test
    void versionTwoHashBindsRoleFieldNamesAndIsoDateEncoding() throws Exception {
        var row = Map.<String, Object>of("trade_date", day, "instrument_code", "000001.SZ");
        var holding =
                Map.<String, Object>of(
                        "etf_code",
                        "510300.SH",
                        "announcement_date",
                        day,
                        "report_period_end_date",
                        day.minusDays(15),
                        "constituent_code",
                        "000001.SZ");
        var other = new TreeMap<String, Object>(holding);
        other.put("constituent_code", "000002.SZ");
        assertNotEquals(
                StrategyInputSchema.V2.keySha("etf_portfolio", List.of(holding)),
                StrategyInputSchema.V2.keySha("etf_portfolio", List.of(other)));
        other.remove("constituent_code");
        assertThrows(
                IllegalStateException.class,
                () -> StrategyInputSchema.V2.keySha("etf_portfolio", List.of(other)));
        String hash = StrategyInputSchema.V2.keySha("etf_daily", List.of(row));
        assertEquals(
                "56bb34e420aaba31ff2a04b992be10022e493fcaec9f6a54459f4baaea3ea24b",
                StrategyInputSchema.V1.keySha(
                        "etf_daily", List.of(Map.of("timestamp", day, "ts_code", "000001.SZ"))));
        // 字节向量由独立 Python json/struct/hashlib 核对，仅作编码样本，不是来源凭证。
        assertEquals("79dfd373fac5898d552a43d424fdff84ff29e85783b036abe0aad593f66817a0", hash);
        assertNotEquals(
                StrategyInputSchema.V2.keySha("l2_raw", List.of()),
                StrategyInputSchema.V2.keySha("etf_portfolio", List.of()));
        assertNotEquals(hash, StrategyInputSchema.V2.keySha("stock_daily", List.of(row)));
        assertNotEquals(
                hash,
                StrategySourceCoverage.keySha(
                        List.of(row), List.of("trade_date", "instrument_code")));
        assertThrows(
                IllegalStateException.class,
                () ->
                        StrategyInputSchema.V2.keySha(
                                "etf_daily",
                                List.of(
                                        Map.of(
                                                "trade_date",
                                                "20261016",
                                                "instrument_code",
                                                "000001.SZ"))));
        assertThrows(
                IllegalStateException.class,
                () ->
                        StrategyInputSchema.V2.keySha(
                                "etf_daily",
                                List.of(Map.of("timestamp", day, "ts_code", "000001.SZ"))));
        assertThrows(
                IllegalStateException.class,
                () -> StrategyInputSchema.V2.keySha("etf_daily", List.of(row, row)));
    }

    @Test
    void changingOnlyTheManifestVersionCannotUpgradeOldEvidence() throws Exception {
        var legacy = authority(tables());
        Path original = root.resolve("coverage.json");
        byte[] before = Files.readAllBytes(original);
        var document = (ObjectNode) json.readTree(before);
        document.put("schema", StrategyInputSchema.V2.coverageSchema());
        byte[] bytes = json.writeValueAsBytes(document);
        Path relabelled = Files.write(root.resolve("header-only.json"), bytes);
        assertThrows(
                IllegalStateException.class,
                () ->
                        new StrategySourceCoverage(
                                relabelled.toString(), StrategySourceCoverage.sha(bytes)));
        assertDoesNotThrow(() -> legacy.verify(day, day, warmup, tables(), clocks));
        assertThrows(
                IllegalStateException.class,
                () -> StrategyInputSchema.fromCoverage("java.strategy.source_coverage.v3"));
    }
}
