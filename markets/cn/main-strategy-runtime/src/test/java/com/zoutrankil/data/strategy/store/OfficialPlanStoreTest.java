package com.zoutrankil.data.strategy.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

class OfficialPlanStoreTest {
    @TempDir Path temporary;
    private static final LocalDate SIGNAL = LocalDate.of(2026, 9, 25);
    private static final String SOURCE =
            """
            {"schema_version":"v1","kind":"official_weekly_plan","available":true,
             "status":"official_weekly_targets_only","strategy":"ETF_ONLY_TOP2",
             "signal_date":"2026-09-25","execution_date":"2026-09-28","valuation_date":"2026-09-30",
             "targets":[{"symbol":" 510300.sh ","name":"沪深","target_weight":0.5,
               "signal_date":"2026-09-25","execution_date":"2026-09-28"}],
             "diagnostic_top_candidates_excluded":true,"diagnostic_payload_path":"diagnostics/latest.json"}
            """;

    @Test
    void missingAuthorityReadDoesNotCreateDirectoriesOrDatabase() {
        var store =
                new OfficialPlanStore(
                        temporary.resolve("missing/root/" + OfficialPlanStore.DATABASE_NAME));
        assertTrue(store.latest("ETF_ONLY_TOP2").isEmpty());
        assertTrue(store.latestOfficialWeekly().isEmpty());
        assertEquals("missing", store.inspect("ETF_ONLY_TOP2").integrityCheck());
        assertThrows(
                PlanStoreException.class,
                () -> store.requireLatest("ETF_ONLY_TOP2", SIGNAL, "run"));
        assertFalse(Files.exists(temporary.resolve("missing")));
    }

    @Test
    void planRootAuthorityRetainsCanonicalRecipeStrategyAndChecksItsLatestAlias() throws Exception {
        var store = store();
        Path older =
                snapshot(
                        "old-name.json",
                        SOURCE.replace("2026-09-25", "2026-09-18")
                                .replace("2026-09-28", "2026-09-21"));
        store.importSnapshot(older, sha(older), provenance("old", SIGNAL.minusDays(7)));
        Path current =
                snapshot(
                        "canonical-name.json",
                        SOURCE.replace(
                                "ETF_ONLY_TOP2",
                                "fusion_dynamic_overlay_10_l2_weight_optimized_top2_50cap"));
        var expected = store.importSnapshot(current, sha(current), provenance("current", SIGNAL));
        assertEquals(expected.planId(), store.latestOfficialWeekly().orElseThrow().planId());
        assertEquals(
                "fusion_dynamic_overlay_10_l2_weight_optimized_top2_50cap",
                store.latestOfficialWeekly().orElseThrow().plan().strategy());
        execute(
                store,
                "DELETE FROM strategy_plan_aliases WHERE strategy='fusion_dynamic_overlay_10_l2_weight_optimized_top2_50cap'");
        assertThrows(PlanStoreException.class, store::latestOfficialWeekly);
    }

    @Test
    void differentStrategiesAtTheSamePlanRootFrontierRequireExplicitReconciliation()
            throws Exception {
        var store = store();
        Path first = snapshot("first-name.json", SOURCE);
        store.importSnapshot(first, sha(first), provenance("one", SIGNAL));
        Path second =
                snapshot("second-name.json", SOURCE.replace("ETF_ONLY_TOP2", "promoted_recipe"));
        store.importSnapshot(second, sha(second), provenance("two", SIGNAL));
        assertThrows(PlanStoreException.class, store::latestOfficialWeekly);
    }

    @Test
    void importHasPythonSemanticIdentityAndPreservesTargetValuationNull() throws Exception {
        var store = store();
        Path source = snapshot("official.json", SOURCE);
        var stored = store.importSnapshot(source, sha(source), provenance("run-1", SIGNAL));
        assertEquals(
                "ca992d9b81aee18470171ebaef9b9492117698b79ca43cf1a6ef9f3be751c36a",
                stored.contentSha256());
        assertEquals("msp_ca992d9b81aee18470171eba", stored.planId());
        assertTrue(stored.created());
        assertTrue(stored.semanticHashVerified());
        assertEquals(List.of("510300.SH"), stored.plan().targetSymbols());
        assertTrue(stored.plan().payload().path("targets").get(0).path("valuation_date").isNull());
        assertEquals(LocalDate.of(2026, 9, 30), stored.plan().valuationDate());
        assertEquals(sha(source), stored.sourceSha256());
        assertNotEquals(source.toString(), stored.sourceSnapshotPath());
        assertEquals(
                stored.planId(),
                store.requireLatest("ETF_ONLY_TOP2", SIGNAL, provenance("run-1", SIGNAL)).planId());
        assertEquals(3, store.inspect("ETF_ONLY_TOP2").schemaVersion());
        assertEquals("ok", store.inspect("ETF_ONLY_TOP2").integrityCheck());
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + store.path());
                var statement = connection.createStatement();
                var rows =
                        statement.executeQuery(
                                "SELECT COUNT(*) FROM strategy_plan_schema_versions")) {
            assertTrue(rows.next());
            assertEquals(3, rows.getInt(1));
        }
    }

    @Test
    void officialAuthoritySerializesAsCanonicalPayloadInCliAndHttpRecords() throws Exception {
        var store = store();
        Path source = snapshot("official.json", SOURCE);
        var stored = store.importSnapshot(source, sha(source), provenance("run", SIGNAL));
        var mapper = new ObjectMapper().findAndRegisterModules();
        var response = mapper.readTree(mapper.writeValueAsBytes(stored));
        assertEquals(stored.plan().payload(), response.path("plan"));
        assertEquals("2026-09-25", response.path("plan").path("signal_date").asText());
        assertTrue(response.path("plan").path("targets").get(0).path("valuation_date").isNull());
        assertEquals(
                stored.plan().payload(),
                mapper.readTree(mapper.writeValueAsBytes(store.inspect("ETF_ONLY_TOP2")))
                        .path("latest")
                        .path("plan"));
        response.withObject("/plan").put("strategy", "mutated-response");
        assertEquals("ETF_ONLY_TOP2", stored.plan().strategy());
        var httpMapper = JsonMapper.builder().build();
        var httpResponse = httpMapper.readTree(httpMapper.writeValueAsBytes(stored));
        assertEquals(
                stored.plan().jsonValue(),
                httpMapper.convertValue(httpResponse.path("plan"), Map.class));
        assertEquals("2026-09-25", httpResponse.path("plan").path("signal_date").asText());
        assertTrue(
                httpResponse.path("plan").path("targets").get(0).path("valuation_date").isNull());
        assertEquals("run", httpResponse.path("provenance").path("scoreRunId").asText());
        assertEquals(
                "ETF_ONLY_TOP2",
                httpMapper
                        .readTree(httpMapper.writeValueAsBytes(store.inspect("ETF_ONLY_TOP2")))
                        .path("latest")
                        .path("plan")
                        .path("strategy")
                        .asText());
    }

    @Test
    void sameContentIsIdempotentAndDiagnosticPathIsExcludedFromIdentity() throws Exception {
        var store = store();
        Path first = snapshot("first.json", SOURCE),
                second =
                        snapshot(
                                "second.json",
                                SOURCE.replace("diagnostics/latest.json", "another/path.json"));
        var imported = store.importSnapshot(first, sha(first), provenance("run", SIGNAL));
        var repeated = store.importSnapshot(second, sha(second), provenance("run", SIGNAL));
        assertFalse(repeated.created());
        assertEquals(imported.planId(), repeated.planId());
        assertEquals(imported.publishedAt(), repeated.publishedAt());
        assertEquals(
                "another/path.json",
                repeated.plan().payload().path("diagnostic_payload_path").asText());
        assertEquals(1, count(store, "strategy_plans"));
    }

    @Test
    void weightRevisionMaintainsOneCurrentBusinessIdentity() throws Exception {
        var store = store();
        Path first = snapshot("old.json", SOURCE),
                second = snapshot("new.json", SOURCE.replace("0.5", "0.4"));
        var old = store.importSnapshot(first, sha(first), provenance("run", SIGNAL));
        var revision = store.importSnapshot(second, sha(second), provenance("run", SIGNAL));
        assertNotEquals(old.planId(), revision.planId());
        assertEquals(2, count(store, "strategy_plans"));
        assertEquals(1, store.inspect("ETF_ONLY_TOP2").currentPlans());
        assertEquals(revision.planId(), store.latest("ETF_ONLY_TOP2").orElseThrow().planId());
    }

    @Test
    void symbolRevisionRollsBackAndDoesNotAdvanceAlias() throws Exception {
        var store = store();
        Path first = snapshot("first.json", SOURCE),
                drift = snapshot("drift.json", SOURCE.replace("510300.sh", "159915.sz"));
        var original = store.importSnapshot(first, sha(first), provenance("run", SIGNAL));
        assertThrows(
                PlanStoreException.class,
                () -> store.importSnapshot(drift, sha(drift), provenance("run", SIGNAL)));
        assertEquals(1, count(store, "strategy_plans"));
        assertEquals(original.planId(), store.latest("ETF_ONLY_TOP2").orElseThrow().planId());
    }

    @Test
    void historicalBackfillCannotRegressLatestAlias() throws Exception {
        var store = store();
        Path latest = snapshot("latest.json", SOURCE);
        var stored = store.importSnapshot(latest, sha(latest), provenance("latest-run", SIGNAL));
        Path older =
                snapshot(
                        "older.json",
                        SOURCE.replace("2026-09-25", "2026-09-18")
                                .replace("2026-09-28", "2026-09-21"));
        store.importSnapshot(older, sha(older), provenance("old-run", LocalDate.of(2026, 9, 18)));
        assertEquals(2, store.inspect("ETF_ONLY_TOP2").currentPlans());
        assertEquals(stored.planId(), store.latest("ETF_ONLY_TOP2").orElseThrow().planId());
    }

    @Test
    void scoresAndRecipeCannotBeReboundOnAnExistingOfficialIdentity() throws Exception {
        var store = store();
        Path source = snapshot("official.json", SOURCE);
        var stored = store.importSnapshot(source, sha(source), provenance("first-run", SIGNAL));
        assertThrows(
                PlanStoreException.class,
                () -> store.importSnapshot(source, sha(source), provenance("new-run", SIGNAL)));
        assertEquals(
                "first-run",
                store.latest("ETF_ONLY_TOP2")
                        .orElseThrow()
                        .provenance()
                        .orElseThrow()
                        .scoreRunId());
        assertThrows(
                PlanStoreException.class,
                () -> store.requireLatest("ETF_ONLY_TOP2", SIGNAL, "new-run"));
        var differentHashes =
                new PlanInputProvenance(
                        "first-run", "d".repeat(64), "b".repeat(64), "c".repeat(64), SIGNAL);
        assertThrows(
                PlanStoreException.class,
                () -> store.requireLatest("ETF_ONLY_TOP2", SIGNAL, differentHashes));
        assertEquals(
                stored.planId(),
                store.requireLatest("ETF_ONLY_TOP2", SIGNAL, "first-run").planId());
    }

    @Test
    void invalidSourceHashMissingRecipeAndFutureInputsFailBeforeCreatingAuthority()
            throws Exception {
        var store = store();
        Path source = snapshot("official.json", SOURCE);
        assertThrows(
                PlanStoreException.class,
                () -> store.importSnapshot(source, "0".repeat(64), provenance("run", SIGNAL)));
        assertThrows(
                PlanStoreException.class, () -> store.importSnapshot(source, sha(source), null));
        assertThrows(
                PlanStoreException.class,
                () -> new PlanInputProvenance("run", "a".repeat(64), "b".repeat(64), "", SIGNAL));
        assertThrows(
                PlanStoreException.class,
                () ->
                        store.importSnapshot(
                                source, sha(source), provenance("run", SIGNAL.plusDays(1))));
        assertThrows(
                PlanStoreException.class,
                () ->
                        store.importSnapshot(
                                source, sha(source), provenance("run", SIGNAL.minusDays(1))));
        assertFalse(Files.exists(store.path()));
        assertFalse(Files.exists(store.path().getParent()));
    }

    @Test
    void sourceFreezingSurvivesCompatibilitySnapshotOverwriteButRejectsFrozenMutation()
            throws Exception {
        var store = store();
        Path source = snapshot("latest.json", SOURCE);
        var stored = store.importSnapshot(source, sha(source), provenance("run", SIGNAL));
        Files.writeString(source, "compatibility file replaced");
        assertEquals(stored.planId(), store.requireLatest("ETF_ONLY_TOP2", SIGNAL, "run").planId());
        Files.writeString(Path.of(stored.sourceSnapshotPath()), SOURCE.replace("0.5", "0.9"));
        assertThrows(PlanStoreException.class, () -> store.latest("ETF_ONLY_TOP2"));
        assertThrows(PlanStoreException.class, () -> store.inspect("ETF_ONLY_TOP2"));
    }

    @Test
    void typedRowAndCanonicalReceiptTamperingAreRejected() throws Exception {
        var store = store();
        Path source = snapshot("official.json", SOURCE);
        store.importSnapshot(source, sha(source), provenance("run", SIGNAL));
        execute(store, "UPDATE strategy_plan_targets SET target_weight=0.9");
        assertThrows(
                PlanStoreException.class,
                () -> store.requireLatest("ETF_ONLY_TOP2", SIGNAL, "run"));
        execute(store, "UPDATE strategy_plan_targets SET target_weight=0.5");
        execute(store, "UPDATE strategy_plan_import_receipts SET canonical_payload='{}'");
        assertThrows(PlanStoreException.class, () -> store.latest("ETF_ONLY_TOP2"));
    }

    @Test
    void deletingReceiptLeavesInspectableLegacyRowsButDoesNotAuthorizeExecution() throws Exception {
        var store = store();
        Path source = snapshot("official.json", SOURCE);
        var stored = store.importSnapshot(source, sha(source), provenance("run", SIGNAL));
        execute(store, "DROP TABLE strategy_plan_import_receipts");
        var legacy = store.latest("ETF_ONLY_TOP2").orElseThrow();
        assertEquals(stored.contentSha256(), legacy.contentSha256());
        assertTrue(legacy.provenance().isEmpty());
        assertFalse(legacy.semanticHashVerified());
        assertEquals("ok", store.inspect("ETF_ONLY_TOP2").integrityCheck());
        assertThrows(
                PlanStoreException.class,
                () -> store.requireLatest("ETF_ONLY_TOP2", SIGNAL, "run"));
        // Only an explicit official JSON with its original semantic SHA and typed rows can bind a
        // legacy authority.
        assertFalse(store.importSnapshot(source, sha(source), provenance("run", SIGNAL)).created());
        assertTrue(store.requireLatest("ETF_ONLY_TOP2", SIGNAL, "run").semanticHashVerified());
    }

    @Test
    void staleAliasAndMultipleCurrentRevisionsAreRejected() throws Exception {
        var store = store();
        Path first = snapshot("first.json", SOURCE),
                second = snapshot("second.json", SOURCE.replace("0.5", "0.4"));
        var original = store.importSnapshot(first, sha(first), provenance("run", SIGNAL));
        store.importSnapshot(second, sha(second), provenance("run", SIGNAL));
        execute(store, "UPDATE strategy_plan_aliases SET plan_id='" + original.planId() + "'");
        assertThrows(PlanStoreException.class, () -> store.latest("ETF_ONLY_TOP2"));
        execute(store, "UPDATE strategy_plans SET is_current=1");
        assertThrows(PlanStoreException.class, () -> store.inspect("ETF_ONLY_TOP2"));
    }

    @Test
    void schemaInspectionDoesNotUpgradeUnsupportedStorage() throws Exception {
        var store = store();
        Path source = snapshot("official.json", SOURCE);
        store.importSnapshot(source, sha(source), provenance("run", SIGNAL));
        execute(store, "PRAGMA user_version=2");
        assertThrows(PlanStoreException.class, () -> store.inspect("ETF_ONLY_TOP2"));
        assertThrows(
                PlanStoreException.class,
                () -> store.importSnapshot(source, sha(source), provenance("run", SIGNAL)));
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + store.path());
                var statement = connection.createStatement();
                var rows = statement.executeQuery("PRAGMA user_version")) {
            assertTrue(rows.next());
            assertEquals(2, rows.getInt(1));
        }
    }

    @Test
    void canonicalPlanIsDefensiveAndValidatesSnapshotSymbols() throws Exception {
        var source = (ObjectNode) CanonicalPlanJson.JSON.readTree(SOURCE);
        source.putArray("target_symbols").add("510300.SH");
        var plan = CanonicalPlanJson.read(CanonicalPlanJson.JSON.writeValueAsBytes(source));
        plan.payload().put("strategy", "tampered");
        assertEquals("ETF_ONLY_TOP2", plan.strategy());
        source.putArray("target_symbols").add("159915.SZ");
        assertThrows(
                PlanStoreException.class,
                () -> CanonicalPlanJson.read(CanonicalPlanJson.JSON.writeValueAsBytes(source)));
    }

    @Test
    void researchCandidatesMissingMarkersAndInvalidExecutableDatesAreRejected() throws Exception {
        var source = (ObjectNode) CanonicalPlanJson.JSON.readTree(SOURCE);
        source.putArray("top_candidates").addObject().put("symbol", "159915.SZ");
        assertThrows(
                PlanStoreException.class,
                () -> CanonicalPlanJson.read(CanonicalPlanJson.JSON.writeValueAsBytes(source)));
        source.remove("top_candidates");
        source.remove("kind");
        assertThrows(
                PlanStoreException.class,
                () -> CanonicalPlanJson.read(CanonicalPlanJson.JSON.writeValueAsBytes(source)));
        assertThrows(
                PlanStoreException.class,
                () ->
                        CanonicalPlanJson.read(
                                SOURCE.replace("2026-09-28", "2026-09-24")
                                        .getBytes(StandardCharsets.UTF_8)));
        assertThrows(
                PlanStoreException.class,
                () ->
                        CanonicalPlanJson.read(
                                SOURCE.replace("0.5", "1.1").getBytes(StandardCharsets.UTF_8)));
        assertThrows(
                PlanStoreException.class,
                () -> CanonicalPlanJson.read((SOURCE + "{}").getBytes(StandardCharsets.UTF_8)));
        assertThrows(
                PlanStoreException.class,
                () ->
                        CanonicalPlanJson.read(
                                SOURCE.replace(
                                                "\"target_weight\":0.5",
                                                "\"target_weight\":0.5,\"target_weight\":0.4")
                                        .getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void canonicalDoubleMatchesPythonPresentationIncludingSubnormalAndSignedZero() {
        assertEquals("0.0", CanonicalPlanJson.pythonDouble(0));
        assertEquals("-0.0", CanonicalPlanJson.pythonDouble(-0.0));
        assertEquals("1.0", CanonicalPlanJson.pythonDouble(1));
        assertEquals("0.0001", CanonicalPlanJson.pythonDouble(1e-4));
        assertEquals("1e-05", CanonicalPlanJson.pythonDouble(1e-5));
        assertEquals("1e+16", CanonicalPlanJson.pythonDouble(1e16));
        assertEquals("5e-324", CanonicalPlanJson.pythonDouble(Double.MIN_VALUE));
        assertEquals("1e+23", CanonicalPlanJson.pythonDouble(1e23));
        assertEquals("1.7976931348623157e+308", CanonicalPlanJson.pythonDouble(Double.MAX_VALUE));
        assertThrows(PlanStoreException.class, () -> CanonicalPlanJson.pythonDouble(Double.NaN));
        assertThrows(
                PlanStoreException.class,
                () -> CanonicalPlanJson.pythonDouble(Double.POSITIVE_INFINITY));
    }

    @Test
    void signedZeroIsPreservedByCanonicalHashEvenWhenSqliteNormalizesReal() throws Exception {
        var store = store();
        Path source = snapshot("official.json", SOURCE.replace("0.5", "-0.0"));
        var imported = store.importSnapshot(source, sha(source), provenance("run", SIGNAL));
        var restored = store.requireLatest("ETF_ONLY_TOP2", SIGNAL, "run");
        assertEquals(imported.contentSha256(), restored.contentSha256());
        assertEquals(
                Double.doubleToRawLongBits(-0.0),
                Double.doubleToRawLongBits(
                        restored.plan()
                                .payload()
                                .path("targets")
                                .get(0)
                                .path("target_weight")
                                .doubleValue()));
    }

    private OfficialPlanStore store() {
        return new OfficialPlanStore(
                temporary.resolve("authority/" + OfficialPlanStore.DATABASE_NAME));
    }

    private Path snapshot(String name, String content) throws Exception {
        return Files.writeString(temporary.resolve(name), content, StandardCharsets.UTF_8);
    }

    private static String sha(Path path) {
        try {
            return CanonicalPlanJson.sha256(Files.readAllBytes(path));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static PlanInputProvenance provenance(String run, LocalDate date) {
        return new PlanInputProvenance(run, "a".repeat(64), "b".repeat(64), "c".repeat(64), date);
    }

    private static void execute(OfficialPlanStore store, String sql) throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + store.path());
                var statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static int count(OfficialPlanStore store, String table) throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + store.path());
                var statement = connection.createStatement();
                var rows = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
            rows.next();
            return rows.getInt(1);
        }
    }
}
