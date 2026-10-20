package com.zoutrankil.data.strategy.publication;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.zoutrankil.data.strategy.score.FrozenScalarValues;

import org.apache.parquet.hadoop.ParquetFileReader;
import org.apache.parquet.io.LocalInputFile;
import org.apache.parquet.schema.PrimitiveType.PrimitiveTypeName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.sql.DriverManager;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

class ScoreArtifactPublisherTest {
    @TempDir Path root;
    private static final LocalDate D = LocalDate.of(2026, 9, 30);
    private static final String RECIPE = "a".repeat(64);

    private List<Map<String, Object>> panel(LocalDate day) {
        return List.of(
                new LinkedHashMap<>(
                        Map.of(
                                "trade_date",
                                day,
                                "ts_code",
                                "510300.SH",
                                "etf_factor_score_base",
                                .625,
                                "self_overheat_flag",
                                false)));
    }

    private List<Map<String, Object>> coverage(LocalDate day) {
        return List.of(Map.of("trade_date", day, "etf_scored_n", 1, "component_hit_rate", .90));
    }

    private Map<String, Object> proof(LocalDate day) {
        return Map.of(
                "sourceComplete",
                true,
                "end",
                day.toString(),
                "recipeHashes",
                Map.of("recipe", RECIPE));
    }

    private ScoreArtifactPublisher.Publication publish(String run, LocalDate day) throws Exception {
        return new ScoreArtifactPublisher(root)
                .publish(run, panel(day), coverage(day), List.of(day), RECIPE, proof(day));
    }

    @Test
    void parquetAndCatalogPublishOneFrozenPairAndRerunIsImmutable() throws Exception {
        var first = publish("java_scores_one", D);
        var second = publish("java_scores_one", D);
        assertEquals(first.panelRef().contentHash(), second.panelRef().contentHash());
        assertEquals(first.coverageRef().contentHash(), second.coverageRef().contentHash());
        ScoreArtifactPublisher.validateFrame(first.panelRef());
        ScoreArtifactPublisher.validateFrame(first.coverageRef());
        assertEquals(1, first.panelRef().rowCount());
        assertEquals(D, first.resolvedEndDate());
        try (var c =
                        DriverManager.getConnection(
                                "jdbc:sqlite:"
                                        + root.resolve("_catalog/resource_catalog.sqlite3"));
                var q = c.createStatement();
                var r =
                        q.executeQuery(
                                "SELECT count(DISTINCT m.run_id),count() FROM resource_aliases a JOIN resource_metadata m ON a.target_uri=m.uri AND a.target_version=m.version")) {
            assertTrue(r.next());
            assertEquals(1, r.getInt(1));
            assertEquals(2, r.getInt(2));
        }
    }

    @Test
    void incompleteCoverageCannotAdvanceEitherAlias() throws Exception {
        var old = publish("java_scores_old", D);
        var publisher = new ScoreArtifactPublisher(root);
        assertThrows(
                IllegalStateException.class,
                () ->
                        publisher.publish(
                                "java_scores_gap",
                                panel(D.plusDays(1)),
                                coverage(D),
                                List.of(D.plusDays(1)),
                                RECIPE,
                                proof(D.plusDays(1))));
        assertEquals(old, publisher.latest(D));
    }

    @Test
    void failedReceiptCollisionRollsBackBothAliases() throws Exception {
        var old = publish("java_scores_old", D);
        var publisher = new ScoreArtifactPublisher(root);
        assertThrows(
                IllegalStateException.class,
                () ->
                        publisher.publish(
                                "java_scores_old",
                                panel(D.plusDays(1)),
                                coverage(D.plusDays(1)),
                                List.of(D.plusDays(1)),
                                RECIPE,
                                proof(D.plusDays(1))));
        assertEquals(old, publisher.latest(D));
    }

    @Test
    void footerGuardsDetectCatalogOnlyRowCountOrDateTampering() throws Exception {
        var p = publish("java_scores_one", D);
        try (var c =
                        DriverManager.getConnection(
                                "jdbc:sqlite:"
                                        + root.resolve("_catalog/resource_catalog.sqlite3"));
                var q = c.createStatement()) {
            q.executeUpdate("UPDATE resource_metadata SET row_count=2 WHERE role='score_panel'");
        }
        assertThrows(IllegalStateException.class, () -> new ScoreArtifactPublisher(root).latest(D));
        assertTrue(Files.isRegularFile(p.panelRef().path()));
    }

    @Test
    void sourceProofMustBeCompleteBeforePublishingAndArtifactTamperingIsRejected()
            throws Exception {
        var publisher = new ScoreArtifactPublisher(root);
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        publisher.publish(
                                "java_scores_bad",
                                panel(D),
                                coverage(D),
                                List.of(D),
                                RECIPE,
                                Map.of("sourceComplete", false)));
        assertFalse(Files.exists(root.resolve("_catalog/resource_catalog.sqlite3")));
        var p = publish("java_scores_good", D);
        Files.writeString(
                p.coverageRef().path(), "bad bytes", StandardOpenOption.TRUNCATE_EXISTING);
        assertThrows(IllegalStateException.class, () -> publisher.latest(D));
    }

    @Test
    void olderPublicationCannotRegressCompleteFrontier() throws Exception {
        publish("java_scores_new", D);
        assertThrows(IllegalStateException.class, () -> publish("java_scores_old", D.minusDays(1)));
        assertEquals("java_scores_new", new ScoreArtifactPublisher(root).latest(D).producerRunId());
    }

    @Test
    void weeklyPlanCanReadItsOriginalPairAfterDailyAliasesAdvance() throws Exception {
        var first = publish("java_scores_weekly", D);
        publish("java_scores_daily", D.plusDays(1));
        var p = new ScoreArtifactPublisher(root);
        assertEquals(first, p.byRun("java_scores_weekly"));
        assertEquals(first, p.forDate(D));
        assertThrows(IllegalStateException.class, () -> p.forDate(D.minusDays(1)));
        assertEquals("java_scores_daily", p.latest(D).producerRunId());
    }

    @Test
    void changedSourceBeforeCommitRollsBackBothAliases() throws Exception {
        var old = publish("java_scores_settled", D);
        var p = new ScoreArtifactPublisher(root);
        assertThrows(
                IllegalStateException.class,
                () ->
                        p.publish(
                                "java_scores_changed",
                                panel(D.plusDays(1)),
                                coverage(D.plusDays(1)),
                                List.of(D.plusDays(1)),
                                RECIPE,
                                proof(D.plusDays(1)),
                                () -> {
                                    throw new IllegalStateException("WAL advanced");
                                }));
        assertEquals(old, p.latest(D));
        assertThrows(IllegalStateException.class, () -> p.byRun("java_scores_changed"));
    }

    @Test
    void frameRejectsRootTraversalBeforeCreatingFiles() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new ScoreArtifactPublisher(root)
                                .frame("../escape/", "score_panel", panel(D)));
    }

    @Test
    void migratedCatalogBeforeFirstJavaPublicationIsUnavailableAndUnchanged() throws Exception {
        Path database = root.resolve("_catalog/resource_catalog.sqlite3");
        Files.createDirectories(database.getParent());
        try (var c = DriverManager.getConnection("jdbc:sqlite:" + database);
                var s = c.createStatement()) {
            s.execute(
                    "CREATE TABLE resource_metadata(uri TEXT,run_id TEXT,role TEXT,producer_capability TEXT,created_at TEXT)");
            s.execute(
                    "INSERT INTO resource_metadata VALUES('legacy-resource','legacy-run','score_panel','python.producer','2026-09-30')");
        }
        byte[] before = Files.readAllBytes(database);
        var error =
                assertThrows(
                        IllegalStateException.class,
                        () -> new ScoreArtifactPublisher(root).forDate(D));
        assertTrue(error.getMessage().contains("No verified Java score publication"));
        assertArrayEquals(before, Files.readAllBytes(database));
        try (var c = DriverManager.getConnection("jdbc:sqlite:" + database);
                var s = c.createStatement();
                var r =
                        s.executeQuery(
                                "SELECT count(*) FROM sqlite_master WHERE name='java_score_publications'")) {
            assertTrue(r.next());
            assertEquals(0, r.getInt(1));
        }
    }

    @Test
    void laterColumnsAndValuesRemainValidatedAfterColumnNameCaching() {
        var publisher = new ScoreArtifactPublisher(root);
        var first = new LinkedHashMap<String, Object>();
        first.put("trade_date", D);
        first.put("value", null);
        var invalid = new LinkedHashMap<String, Object>(first);
        invalid.put("invalid-name", 1.0);
        assertThrows(
                IllegalArgumentException.class,
                () -> publisher.frame("invalid/", "score_panel", List.of(first, invalid)));
        var numeric = new LinkedHashMap<String, Object>(first);
        numeric.put("value", 1.0);
        var text = new LinkedHashMap<String, Object>(first);
        text.put("value", "changed type");
        assertThrows(
                IllegalArgumentException.class,
                () -> publisher.frame("mixed/", "score_panel", List.of(first, numeric, text)));
        var infinite = new LinkedHashMap<String, Object>(first);
        infinite.put("value", Double.POSITIVE_INFINITY);
        assertThrows(
                IllegalArgumentException.class,
                () -> publisher.frame("infinite/", "score_panel", List.of(numeric, infinite)));
    }

    @Test
    void compactFramesPreserveParquetBytesSchemaTypesNullsAndAllRows() throws Exception {
        var rows = new ArrayList<Map<String, Object>>();
        for (int i = 0; i < 3; i++) {
            var row = new LinkedHashMap<String, Object>();
            row.put("trade_date", D.plusDays(i));
            row.put("ts_code", "510300.SH");
            row.put("mixed_numeric", i == 1 ? (Object) 2.5 : (Object) (long) i);
            row.put("nullable", i == 0 ? null : Double.NaN);
            row.put("eligible", i % 2 == 0);
            row.put("name", "ETF");
            rows.add(row);
        }
        var compact = rows.stream().map(FrozenScalarValues::copyOf).toList();
        var publisher = new ScoreArtifactPublisher(root);
        var original = publisher.frame("original/", "score_panel", rows);
        var dense = publisher.frame("compact/", "score_panel", compact);
        assertEquals(original.contentHash(), dense.contentHash());
        assertEquals(original.schemaDigest(), dense.schemaDigest());
        assertEquals(3, dense.rowCount());
        assertEquals(D, dense.start());
        assertEquals(D.plusDays(2), dense.end());
        ScoreArtifactPublisher.validateFrame(dense);
        try (var reader = ParquetFileReader.open(new LocalInputFile(dense.path()))) {
            assertEquals(
                    PrimitiveTypeName.DOUBLE,
                    reader.getFooter()
                            .getFileMetaData()
                            .getSchema()
                            .getType("mixed_numeric")
                            .asPrimitiveType()
                            .getPrimitiveTypeName());
        }
    }
}
