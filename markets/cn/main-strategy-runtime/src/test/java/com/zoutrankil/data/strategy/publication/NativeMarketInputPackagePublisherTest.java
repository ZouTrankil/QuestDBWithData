package com.zoutrankil.data.strategy.publication;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.DriverManager;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class NativeMarketInputPackagePublisherTest {
    @TempDir Path root;
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final LocalDate START = LocalDate.of(2026, 9, 24), END = LocalDate.of(2026, 9, 25);
    private static final String RECIPE = "a".repeat(64);

    @Test void publishesExactThreeFrozenRolesAndCompatibleManifestWithDeclaredPit() throws Exception {
        var inputs = inputs(); var scores = scores("run1", inputs);
        var publisher = new NativeMarketInputPackagePublisher(root);
        var product = publish(publisher, "run1", inputs, scores);
        var document = JSON.readTree(Files.readAllBytes(product.manifestRef().path()));
        assertEquals(Set.of("package_id", "product_id", "product_version", "as_of", "created_at", "schema_digest", "input_digest",
                "resolved_parameters", "max_complete_date", "row_count", "components"), names(document));
        assertEquals(NativeMarketInputPackagePublisher.PRODUCT_ID, document.path("product_id").asText());
        assertEquals("20260925", document.path("max_complete_date").asText());
        assertEquals("pkg_" + product.inputDigest().substring(0, 20), product.packageId());
        assertEquals(3, document.path("components").size());
        var roles = new HashSet<String>();
        for (var component : document.path("components")) {
            roles.add(component.path("role").asText()); var snapshot = component.path("snapshot");
            assertEquals("frozen", snapshot.path("materialization_mode").asText());
            assertEquals("complete", snapshot.path("completeness_status").asText());
            assertEquals("declared", snapshot.path("temporal").path("point_in_time").asText());
            assertEquals("latest_only", snapshot.path("temporal").path("revision_visibility").asText());
            assertEquals("application/vnd.apache.parquet", snapshot.path("artifact").path("media_type").asText());
            assertEquals(1, snapshot.path("parts").size());
        }
        assertEquals(Set.of("calendar", "etf_market", "market_context"), roles);
        assertEquals(inputs.calendar.size() + inputs.market.size() + inputs.context.size(), document.path("row_count").asLong());
        assertEquals("run1", document.path("resolved_parameters").path("score_run_id").asText());
        assertEquals(scores.panelRef().contentHash(), document.path("resolved_parameters").path("score_panel_sha256").asText());
        assertEquals(product.manifestRef().contentHash(), publisher.requireLatest(scores).manifestRef().contentHash());
        assertEquals(5, publisher.evidence(product).size());
        assertEquals(scores, new ScoreArtifactPublisher(root).latest(END));
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + root.resolve("_catalog/resource_catalog.sqlite3"));
                var statement = connection.createStatement(); var rows = statement.executeQuery("SELECT COUNT(*) FROM resource_parents p JOIN resource_metadata m ON m.resource_id=p.resource_id WHERE m.role='data_product_package'")) {
            rows.next(); assertEquals(6, rows.getInt(1));
        }
    }

    @Test void newListingDoesNotRequirePricesBeforeItsFirstFrozenScoreCandidateDate() throws Exception {
        var inputs = inputs();
        var scoreRows = new ArrayList<>(inputs.panel); scoreRows.add(Map.of("trade_date", END, "ts_code", "159915.SZ", "score", 0.8));
        var market = new ArrayList<>(inputs.market); market.add(marketRow(END, "159915.SZ"));
        inputs = new Inputs(inputs.calendar, market, inputs.context, scoreRows);
        var scores = scores("newlisting", inputs);
        var product = publish(new NativeMarketInputPackagePublisher(root), "newlisting", inputs, scores);
        assertEquals(3, product.components().get(1).rowCount());
    }

    @Test void calendarLookaheadAndCandidatePriceGapsRejectPackageWithoutChangingScoreAliases() throws Exception {
        var original = inputs(); var truncated = new Inputs(original.calendar.subList(0, original.calendar.size() - 1), original.market, original.context, original.panel);
        var scores = scores("shortcalendar", truncated); var publisher = new NativeMarketInputPackagePublisher(root);
        var failedCalendar = truncated;
        assertThrows(IllegalStateException.class, () -> publish(publisher, "shortcalendar", failedCalendar, scores));
        assertEquals(scores, new ScoreArtifactPublisher(root).latest(END));
        assertEquals(0, packageAliasCount());
        var missingPrice = new Inputs(original.calendar, original.market.subList(0, 1), original.context, original.panel);
        var next = scores("missingprice", missingPrice);
        assertThrows(IllegalStateException.class, () -> publish(publisher, "missingprice", missingPrice, next));
        assertEquals(0, packageAliasCount());
    }

    @Test void sentimentRowsCannotMasqueradeAsRegimeContext() throws Exception {
        var original = inputs();
        var wrong = new Inputs(original.calendar, original.market, List.of(Map.of("trade_date", START, "pct_positive_ratio", 0.6),
                Map.of("trade_date", END, "pct_positive_ratio", 0.7)), original.panel);
        var scores = scores("wrongcontext", wrong);
        assertThrows(IllegalStateException.class, () -> publish(new NativeMarketInputPackagePublisher(root), "wrongcontext", wrong, scores));
        assertEquals(0, packageAliasCount());
    }

    @Test void mutableSourceOrScoreCandidateClaimCannotBorrowAnEarlierSourceCertificate() throws Exception {
        var original = inputs(); var scores = scores("run1", original); var publisher = new NativeMarketInputPackagePublisher(root);
        var changed = new ArrayList<>(original.market); var changedRow = new LinkedHashMap<>(changed.getFirst()); changedRow.put("close", 99.0); changed.set(0, changedRow);
        assertThrows(IllegalStateException.class, () -> publisher.publish("run1", START, END, original.calendar, changed, original.context, original.panel, scores, proof(original)));
        var wrongCandidate = new ArrayList<>(original.panel); var row = new LinkedHashMap<>(wrongCandidate.getFirst()); row.put("ts_code", "159915.SZ"); wrongCandidate.set(0, row);
        assertThrows(IllegalStateException.class, () -> publisher.publish("run1", START, END, original.calendar, original.market, original.context, wrongCandidate, scores, proof(original)));
        assertEquals(0, packageAliasCount());
    }

    @Test void unsettledWalProofCannotPublishACompleteMarketPackage() throws Exception {
        var original = inputs(); var proof = proof(original);
        @SuppressWarnings("unchecked") var clocks = (Map<String,Object>) proof.get("sourceClocks");
        var altered = new LinkedHashMap<String,Object>(clocks);
        @SuppressWarnings("unchecked") var old = (Map<String,Object>) altered.get("etf_daily");
        var badClock = new LinkedHashMap<String,Object>(old); badClock.put("bufferedTxnSize", 1L); altered.put("etf_daily", badClock); proof.put("sourceClocks", altered);
        var scores = scorePublisher().publish("unsettled", original.panel, coverage(), List.of(START, END), RECIPE, proof);
        assertThrows(IllegalStateException.class, () -> new NativeMarketInputPackagePublisher(root).publish("unsettled", START, END,
                original.calendar, original.market, original.context, original.panel, scores, proof));
        assertEquals(0, packageAliasCount());
    }
    @Test void liveSourceChangeBeforeCommitCannotAdvanceThePackageAlias()throws Exception {
        var input=inputs();var scores=scores("livechange",input);
        assertThrows(IllegalStateException.class,()->new NativeMarketInputPackagePublisher(root).publish("livechange",START,END,input.calendar,input.market,input.context,input.panel,scores,proof(input),()->{throw new IllegalStateException("Native source changed before commit");}));
        assertEquals(0,packageAliasCount());
        assertEquals(scores,new ScoreArtifactPublisher(root).latest(END));
    }

    @Test void incompleteRegimeQualityCannotClaimACompleteWatermark() throws Exception {
        var original = inputs(); var context = new ArrayList<>(original.context);
        var bad = new LinkedHashMap<String,Object>(context.getFirst()); bad.put("data_quality_flag", "missing_data"); context.set(0, bad);
        var invalid = new Inputs(original.calendar, original.market, context, original.panel);
        var scores = scores("badquality", invalid);
        assertThrows(IllegalStateException.class, () -> publish(new NativeMarketInputPackagePublisher(root), "badquality", invalid, scores));
        assertEquals(0, packageAliasCount());
    }

    @Test void actualSixDigitRegimeMonthPublishesAsCanonicalStringAndMismatchedMonthFailsClosed() throws Exception {
        var original = inputs(); var context = new ArrayList<Map<String,Object>>();
        for (var source : original.context) { var row = new LinkedHashMap<>(source); row.put("month", "202609"); context.add(row); }
        var canonical = new Inputs(original.calendar, original.market, context, original.panel);
        var scores = scores("canonicalmonth", canonical);
        var product = publish(new NativeMarketInputPackagePublisher(root), "canonicalmonth", canonical, scores);
        try (var reader = org.apache.parquet.hadoop.ParquetFileReader.open(new org.apache.parquet.io.LocalInputFile(product.components().get(2).path()))) {
            var schema = reader.getFileMetaData().getSchema();
            assertEquals(org.apache.parquet.schema.PrimitiveType.PrimitiveTypeName.BINARY, schema.getType("month").asPrimitiveType().getPrimitiveTypeName());
            var pages = reader.readNextRowGroup(); var rows = new org.apache.parquet.io.ColumnIOFactory().getColumnIO(schema)
                    .getRecordReader(pages, new org.apache.parquet.example.data.simple.convert.GroupRecordConverter(schema));
            assertEquals("202609", rows.read().getString("month", 0));
        }
        var bad = new ArrayList<>(context); var wrong = new LinkedHashMap<>(bad.getFirst()); wrong.put("month", "202608"); bad.set(0, wrong);
        var mismatched = new Inputs(original.calendar, original.market, bad, original.panel);
        var next = scores("badmonth", mismatched);
        assertThrows(IllegalStateException.class, () -> publish(new NativeMarketInputPackagePublisher(root), "badmonth", mismatched, next));
    }

    @Test void frozenBytesAndScoreRunDriftAreRejectedOnReadonlyInspection() throws Exception {
        var original = inputs(); var scores = scores("run1", original); var publisher = new NativeMarketInputPackagePublisher(root);
        var published = publish(publisher, "run1", original, scores);
        var next = scores("run2", original);
        assertThrows(IllegalStateException.class, () -> publisher.requireLatest(scores));
        assertThrows(IllegalStateException.class, () -> publisher.requireLatest(next));
        var current = publish(publisher, "run2", original, next);
        Files.writeString(current.components().get(1).path(), "corrupt frozen parquet");
        assertThrows(IllegalStateException.class, () -> publisher.requireLatest(next));
        assertTrue(Files.isRegularFile(published.manifestRef().path()));
    }

    @Test void catalogFailureRollsBackAllNewPackageCardsAndPreservesOldPackageAlias() throws Exception {
        var original = inputs(); var publisher = new NativeMarketInputPackagePublisher(root);
        var first = publish(publisher, "run1", original, scores("run1", original));
        var next = scores("run2", original); var artifacts = scorePublisher();
        String prefix = "data/product_packages/market/etf/rotation/input_package/java/run2/";
        List<Map<String,Object>> calendarRows = original.calendar.stream().map(row -> {
            var projected = new LinkedHashMap<String,Object>();
            for (String column : List.of("exchange", "cal_date", "is_open", "pretrade_date")) projected.put(column, row.get(column));
            return (Map<String,Object>) projected;
        }).toList();
        var calendarRef = artifacts.frame(prefix, "calendar", calendarRows, "cal_date");
        try (var connection = artifacts.openCatalogWriter()) {
            ScoreArtifactPublisher.registerMetadata(connection, "calendar", "wrong-owner-run", calendarRef,
                    "quant_platform.data.market.calendar.daily", "java.data.product.build", List.of()); connection.commit();
        }
        assertThrows(IllegalStateException.class, () -> publish(publisher, "run2", original, next));
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + root.resolve("_catalog/resource_catalog.sqlite3")); var statement = connection.createStatement()) {
            try (var rows = statement.executeQuery("SELECT target_content_hash FROM resource_aliases WHERE alias='data.product.market.etf.rotation.input_package@latest'")) {
                rows.next(); assertEquals(first.manifestRef().contentHash(), rows.getString(1));
            }
            try (var rows = statement.executeQuery("SELECT COUNT(*) FROM resource_metadata WHERE run_id='run2' AND role IN ('etf_market','market_context')")) {
                rows.next(); assertEquals(0, rows.getInt(1));
            }
        }
    }

    @Test void historicalPackageRemainsReadableAfterBothLatestAliasesAdvance() throws Exception {
        var original = inputs(); var publisher = new NativeMarketInputPackagePublisher(root);
        var firstScores = scores("run1", original); var first = publish(publisher, "run1", original, firstScores);
        var nextScores = scores("run2", original); var next = publish(publisher, "run2", original, nextScores);
        assertEquals(first, publisher.requireByRun(firstScores));
        assertEquals(next, publisher.requireByRun(nextScores));
        assertThrows(IllegalStateException.class, () -> publisher.requireLatest(firstScores));
        try (var connection = scorePublisher().openCatalogWriter(); var statement = connection.createStatement()) {
            statement.executeUpdate("DELETE FROM resource_aliases"); connection.commit();
        }
        assertEquals(first, publisher.requireByRun(firstScores));
        assertEquals(next, publisher.requireByRun(nextScores));
    }

    @Test void scorePairWithoutACommittedThreeRolePackageCannotBeUsedHistorically() throws Exception {
        var original = inputs(); var scores = scores("partial", original); var publisher = new NativeMarketInputPackagePublisher(root);
        assertThrows(IllegalStateException.class, () -> publisher.requireByRun(scores));
        assertThrows(IllegalStateException.class, () -> publisher.publish("partial", START, END, original.calendar, original.market,
                original.context, original.panel, scores, proof(original), () -> { throw new IllegalStateException("Source changed"); }));
        assertEquals(scores, scorePublisher().byRun("partial"));
        assertThrows(IllegalStateException.class, () -> publisher.requireByRun(scores));
    }

    @Test void historicalReceiptCannotBindAnotherScoreRunsManifest() throws Exception {
        var original = inputs(); var publisher = new NativeMarketInputPackagePublisher(root);
        var firstScores = scores("run1", original); publish(publisher, "run1", original, firstScores);
        var nextScores = scores("run2", original); var next = publish(publisher, "run2", original, nextScores);
        try (var connection = scorePublisher().openCatalogWriter(); var statement = connection.prepareStatement(
                "UPDATE java_market_package_publications SET manifest_uri=?,manifest_version=?,manifest_sha256=?,input_digest=? WHERE score_run_id='run1'")) {
            statement.setString(1, next.manifestRef().uri()); statement.setString(2, next.manifestRef().version());
            statement.setString(3, next.manifestRef().contentHash()); statement.setString(4, next.inputDigest());
            statement.executeUpdate(); connection.commit();
        }
        assertThrows(IllegalStateException.class, () -> publisher.requireByRun(firstScores));
        assertEquals(next, publisher.requireByRun(nextScores));
    }

    @Test void identicalProducerRetryRetainsItsFirstManifestAndLegacyEquivalentManifestsRemainReadable() throws Exception {
        var original = inputs(); var publisher = new NativeMarketInputPackagePublisher(root); var scores = scores("retry", original);
        var first = publish(publisher, "retry", original, scores);
        assertEquals(first, publish(publisher, "retry", original, scores));
        assertEquals(first, publisher.requireByRun(scores));
        var document = JSON.readTree(Files.readAllBytes(first.manifestRef().path()));
        ((com.fasterxml.jackson.databind.node.ObjectNode) document).put("created_at", "2026-10-11T12:00:00Z");
        var equivalent = scorePublisher().jsonArtifact("data/product_packages/market/etf/rotation/input_package/java/retry/", "manifest",
                JSON.writeValueAsBytes(document), START, END, first.manifestRef().rowCount(), first.manifestRef().schemaDigest());
        try (var connection = scorePublisher().openCatalogWriter(); var statement = connection.createStatement()) {
            ScoreArtifactPublisher.registerMetadata(connection, "data_product_package", first.inputDigest(), equivalent,
                    "quant_platform.data." + NativeMarketInputPackagePublisher.PRODUCT_ID, "java.data.product.build", List.of());
            statement.executeUpdate("DELETE FROM java_market_package_publications WHERE score_run_id='retry'");
            connection.commit();
        }
        var historical = publisher.requireByRun(scores);
        assertEquals(first.inputDigest(), historical.inputDigest());
        assertEquals(first.components(), historical.components());
        assertEquals(first.sourceProofRef(), historical.sourceProofRef());
    }

    private ScoreArtifactPublisher scorePublisher() { return new ScoreArtifactPublisher(root); }
    private ScoreArtifactPublisher.Publication scores(String run, Inputs inputs) throws Exception { return scorePublisher().publish(run, inputs.panel, coverage(), List.of(START, END), RECIPE, proof(inputs)); }
    private static NativeMarketInputPackagePublisher.Publication publish(NativeMarketInputPackagePublisher publisher, String run, Inputs inputs, ScoreArtifactPublisher.Publication scores) throws Exception {
        return publisher.publish(run, START, END, inputs.calendar, inputs.market, inputs.context, inputs.panel, scores, proof(inputs));
    }
    private static List<Map<String,Object>> coverage() { return List.of(Map.of("trade_date", START, "count", 1L), Map.of("trade_date", END, "count", 1L)); }
    private static Inputs inputs() {
        var calendar = new ArrayList<Map<String,Object>>(); LocalDate prior = START.minusDays(1);
        for (LocalDate day = START; !day.isAfter(END.plusDays(14)); day = day.plusDays(1)) {
            boolean open = day.getDayOfWeek() != DayOfWeek.SATURDAY && day.getDayOfWeek() != DayOfWeek.SUNDAY;
            calendar.add(Map.of("exchange", "SSE", "cal_date", day, "is_open", open ? 1L : 0L, "pretrade_date", prior));
            if (open) prior = day;
        }
        var market = List.of(marketRow(START, "510300.SH"), marketRow(END, "510300.SH"));
        var context = new ArrayList<Map<String,Object>>();
        for (LocalDate day : List.of(START, END)) context.add(Map.of("trade_date", day, "month", day.withDayOfMonth(1), "hs300_ret_mtd", 0.1,
                "zz500_ret_mtd", 0.2, "all_a_ret_mtd", 0.1, "small_large_ret_mtd", 0.0, "growth_value_ret_mtd", 0.0,
                "avg_up_down_ratio_5d", 1.0, "data_quality_flag", "ok", "updated_at", "2026-10-11T10:00:00Z"));
        var panel = List.<Map<String,Object>>of(Map.of("trade_date", START, "ts_code", "510300.SH", "score", 0.5), Map.of("trade_date", END, "ts_code", "510300.SH", "score", 0.6));
        return new Inputs(calendar, market, context, panel);
    }
    private static Map<String,Object> marketRow(LocalDate day, String code) { return Map.of("ts_code", code, "timestamp", day, "open", 1.0, "high", 1.2, "low", 0.9, "close", 1.1, "amount", 100.0); }
    private static Map<String,Object> proof(Inputs inputs) throws Exception {
        var sources = new LinkedHashMap<String,Object>();
        sources.put("calendar", Map.of("rows", inputs.calendar.size(), "sha256", hash(inputs.calendar)));
        sources.put("etf_daily", Map.of("rows", inputs.market.size(), "sha256", hash(inputs.market)));
        sources.put("market_context", Map.of("rows", inputs.context.size(), "sha256", hash(inputs.context)));
        var clocks = new LinkedHashMap<String,Object>();
        for (String table : List.of("exchange_calendar", "etf_daily", "regime_features_monitor_daily")) clocks.put(table,
                Map.of("id", 1L, "directoryName", table + "~1", "walEnabled", true, "writerTxn", 2L, "sequencerTxn", 2L,
                        "suspended", false, "bufferedTxnSize", 0L, "schema", List.of(Map.of("column", "date", "type", "TIMESTAMP"))));
        var result = new LinkedHashMap<String,Object>(); result.put("sourceComplete", true); result.put("sources", sources);
        result.put("sourceClocks", clocks); result.put("end", END.toString()); result.put("recipeHashes", Map.of("recipe", RECIPE)); return result;
    }
    private static String hash(List<Map<String,Object>> rows) throws Exception {
        var digest = MessageDigest.getInstance("SHA-256");
        for (var row : rows) { var sorted = new TreeMap<String,Object>(); row.forEach((key,value) -> sorted.put(key, value instanceof LocalDate date ? date.toString() : value));
            byte[] bytes = JSON.writeValueAsBytes(sorted); digest.update(ByteBuffer.allocate(4).putInt(bytes.length).array()); digest.update(bytes); }
        return HexFormat.of().formatHex(digest.digest());
    }
    private int packageAliasCount() throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + root.resolve("_catalog/resource_catalog.sqlite3")); var statement = connection.createStatement();
                var rows = statement.executeQuery("SELECT COUNT(*) FROM resource_aliases WHERE alias='data.product.market.etf.rotation.input_package@latest'")) { rows.next(); return rows.getInt(1); }
    }
    private static Set<String> names(com.fasterxml.jackson.databind.JsonNode node) { var names = new HashSet<String>(); node.fieldNames().forEachRemaining(names::add); return names; }
    private record Inputs(List<Map<String,Object>> calendar, List<Map<String,Object>> market, List<Map<String,Object>> context, List<Map<String,Object>> panel) { }
}
