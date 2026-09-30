package com.zoutrankil.data.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zoutrankil.data.domain.DatasetReadQuery;
import com.zoutrankil.data.domain.L2DatasetManifest;
import com.zoutrankil.data.domain.L2DatasetManifestDataset;
import com.zoutrankil.data.mapper.L2DatasetManifestMapper;
import com.zoutrankil.data.repository.QuestDbBoundedReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.Assumptions;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import static org.junit.jupiter.api.Assertions.*;

/** Opt-in, real QuestDB typed-read comparison against a receipt from the Python Parquet reader. */
class L2DatasetManifestLiveAcceptanceTest {
    private static final String TABLE = "java_d085_l2_dataset_manifest_acceptance_20260930";
    private static final String SYMBOL = "000001.SZ";
    private static final LocalDate FROM = LocalDate.of(2026, 9, 21);
    private static final LocalDate TO_EXCLUSIVE = LocalDate.of(2026, 9, 25);
    private static final Path SOURCE_RECEIPT = Path.of(
            "artifacts/java-migration/D085/source-manifest-20260921-24.jsonl");

    @Test
    @EnabledIfEnvironmentVariable(named = "D085_LIVE_ACCEPTANCE", matches = "true")
    void fullTypedReadMatchesEveryParquetSourceFieldOnTheIsolatedQuestDbTable() throws Exception {
        String username = System.getenv("APP_QUESTDB_USERNAME");
        String password = System.getenv("APP_QUESTDB_PASSWORD");
        Assumptions.assumeTrue(username != null && !username.isBlank() && password != null,
                "Local QuestDB test credentials are required");
        assertTrue(TABLE.startsWith("java_d085_l2_dataset_manifest_"));
        assertTrue(Files.isRegularFile(SOURCE_RECEIPT));

        var json = new ObjectMapper();
        var mapper = new L2DatasetManifestMapper();
        var sourceRows = new ArrayList<L2DatasetManifest>();
        JsonNode header = null, completion = null;
        for (String line : Files.readAllLines(SOURCE_RECEIPT)) {
            JsonNode item = json.readTree(line);
            switch (item.path("kind").asText()) {
                case "header" -> header = item;
                case "page" -> {
                    JsonNode rows = item.path("rows");
                    assertTrue(rows.isArray() && !rows.isEmpty() && rows.size() <= 200);
                    for (JsonNode row : rows) sourceRows.add(mapper.fromParquet(row));
                }
                case "completion" -> completion = item;
                default -> fail("Unexpected Parquet reader evidence record");
            }
        }
        assertNotNull(header);
        assertNotNull(completion);
        assertTrue(completion.path("complete").asBoolean());
        assertEquals(4, completion.path("pages").asInt());
        assertEquals(sourceRows.size(), completion.path("returnedRows").asInt());
        assertEquals(4, sourceRows.size());

        var dataSource = new DriverManagerDataSource(
                "jdbc:postgresql://localhost:8812/qdb?sslmode=disable", username, password);
        var jdbc = new JdbcTemplate(dataSource);
        jdbc.setQueryTimeout(20);
        var definition = L2DatasetManifestDataset.definition(TABLE);
        var query = new DatasetReadQuery(L2DatasetManifestMapper.columns(), Map.of("symbol", SYMBOL),
                "trade_date", FROM, TO_EXCLUSIVE, 10, null);
        var actual = new QuestDbBoundedReader(jdbc).read(definition, query, null, mapper::fromValues).rows();

        var sort = Comparator.comparing(L2DatasetManifest::tradeDate).thenComparing(L2DatasetManifest::symbol)
                .thenComparingLong(L2DatasetManifest::batchId);
        assertEquals(sourceRows.stream().sorted(sort).toList(), actual.stream().sorted(sort).toList());
        assertEquals(sourceRows.size(), new HashSet<>(actual.stream().map(L2DatasetManifest::key).toList()).size());
        Long count = jdbc.queryForObject("SELECT count() FROM \"" + TABLE
                + "\" WHERE symbol=? AND trade_date>=? AND trade_date<?",
                Long.class, SYMBOL, "20260921", "20260925");
        assertEquals((long) sourceRows.size(), count);
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "D085_LIVE_ACCEPTANCE", matches = "true")
    void cancellationStopsTheBoundedParquetReaderBeforeItEmitsAWriteablePage() throws Exception {
        var expected = new L2DatasetManifestParquetSource.Inspection(FROM, TO_EXCLUSIVE.minusDays(1),
                List.of(FROM, FROM.plusDays(1), FROM.plusDays(2), FROM.plusDays(3)),
                31_731, 4, 1_589, 4, 21_945_806L,
                "63dc7538453e9c69c0057e45202a2dc132e300dd2a37ada784af37bec1a7a5e5",
                "253a804be11f8ab50c2a8facdcd7d9ad6775e9b6043cdcf12244abd12a3c20e5",
                "l2-dataset-manifest-parquet-v1", true, false);
        var source = new L2DatasetManifestParquetSource(
                Path.of("D:/work/fund_2/back-monitor/artifacts/level2_t0_dataset"),
                Path.of("tools/read_l2_dataset_manifest.py"),
                "D:/work/fund_2/back-monitor/.venv/Scripts/python.exe");
        assertThrows(CancellationException.class, () -> source.stream(expected, List.of(SYMBOL),
                300_000, 25_000, page -> fail("Cancelled D085 source must not emit a page"),
                () -> true, Duration.ofMinutes(2)));
    }
}
