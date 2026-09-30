package com.zoutrankil.questdbwithdata.service;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.zoutrankil.questdbwithdata.domain.JobDefinitionJson;
import com.zoutrankil.questdbwithdata.domain.SyncJobDefinition;
import com.zoutrankil.questdbwithdata.domain.SyncRequestIdentity;
import com.zoutrankil.questdbwithdata.domain.SyncRunState;
import com.zoutrankil.questdbwithdata.repository.SyncRunLedger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.nio.file.Files;
import java.time.LocalDate;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class DailyBasicCheckpointTest {
    @TempDir Path temp;

    @Test void mergesOnlyContiguousIncrementalRunsForTheLatestMatchingAnchorAndTarget() throws Exception {
        Path ledgerPath = temp.resolve("sync-ledger.sqlite3");
        var ledger = new SyncRunLedger(ledgerPath);
        String target = "questdb-d008-target";
        LocalDate firstAnchor = LocalDate.of(2025, 1, 1);
        LocalDate latestAnchor = LocalDate.of(2026, 3, 1);
        addRun(ledger, "old-bootstrap", target, SyncJobDefinition.Mode.INCREMENTAL,
                firstAnchor, firstAnchor, firstAnchor, null);
        addRun(ledger, "old-adjacent", target, SyncJobDefinition.Mode.INCREMENTAL,
                firstAnchor, firstAnchor.plusDays(1), firstAnchor.plusDays(10), null);
        addRun(ledger, "old-gap", target, SyncJobDefinition.Mode.INCREMENTAL,
                firstAnchor, firstAnchor.plusDays(20), firstAnchor.plusDays(30), null);
        addRun(ledger, "backfill-long", target, SyncJobDefinition.Mode.BACKFILL,
                null, firstAnchor, firstAnchor.plusDays(90), null);
        addRun(ledger, "other-target", "questdb-other-target", SyncJobDefinition.Mode.INCREMENTAL,
                latestAnchor, latestAnchor, latestAnchor.plusDays(90), null);
        addRun(ledger, "wrong-route", target, SyncJobDefinition.Mode.INCREMENTAL,
                latestAnchor, latestAnchor, latestAnchor.plusDays(100), "different-owner");
        addRun(ledger, "latest-bootstrap", target, SyncJobDefinition.Mode.INCREMENTAL,
                latestAnchor, latestAnchor, latestAnchor.plusDays(1), null);
        addRun(ledger, "latest-overlap", target, SyncJobDefinition.Mode.INCREMENTAL,
                latestAnchor, latestAnchor.minusDays(5), latestAnchor.plusDays(5), null);

        var coverage = DailyBasicCoverage.checkpoint(ledgerPath, target).orElseThrow();
        assertEquals(latestAnchor, coverage.anchor());
        assertEquals(latestAnchor.minusDays(5), coverage.from());
        assertEquals(latestAnchor.plusDays(5), coverage.through());
    }

    @Test void ignoresRunsWithAChangedDatasetSchemaVersion() throws Exception {
        Path ledgerPath = temp.resolve("schema-ledger.sqlite3");
        var ledger = new SyncRunLedger(ledgerPath);
        LocalDate anchor = LocalDate.of(2026, 1, 5);
        addRun(ledger, "wrong-schema", "questdb-d008-target", SyncJobDefinition.Mode.INCREMENTAL,
                anchor, anchor, anchor.plusDays(40), null, 2);
        assertTrue(DailyBasicCoverage.checkpoint(ledgerPath, "questdb-d008-target").isEmpty());
    }

    @Test void checkpointRowEndpointsComeFromFingerprintedReceiptsAndAllowAnEmptyTradingDate() throws Exception {
        Path ledgerPath = temp.resolve("receipt-ledger.sqlite3");
        var ledger = new SyncRunLedger(ledgerPath);
        LocalDate anchor = LocalDate.of(2026, 8, 3);
        LocalDate emptyTradeDate = LocalDate.of(2026, 8, 4);
        addReceiptRun(ledger, ledgerPath, "receipt-backed", "questdb-d008-target", anchor,
                List.of(anchor, emptyTradeDate));

        var coverage = DailyBasicCoverage.checkpoint(ledgerPath, "questdb-d008-target").orElseThrow();
        assertTrue(coverage.includesRows());
        assertEquals(anchor, coverage.firstRowDate());
        assertEquals(anchor, coverage.lastRowDate());
        assertEquals(emptyTradeDate, coverage.through());

        Path receipt = temp.resolve("sync-evidence/receipt-backed/source-1.json");
        var changed = (ObjectNode) JobDefinitionJson.mapper().readTree(receipt.toFile());
        ((ObjectNode) changed.path("rows").get(0)).put("ts_code", "600000.SH");
        Files.write(receipt, JobDefinitionJson.mapper().writeValueAsBytes(changed));
        assertThrows(IllegalStateException.class,
                () -> DailyBasicCoverage.checkpoint(ledgerPath, "questdb-d008-target"));
    }

    private static void addRun(SyncRunLedger ledger, String id, String target, SyncJobDefinition.Mode mode,
            LocalDate anchor, LocalDate from, LocalDate to, String ownerOverride) throws Exception {
        addRun(ledger, id, target, mode, anchor, from, to, ownerOverride, 1);
    }

    private static void addRun(SyncRunLedger ledger, String id, String target, SyncJobDefinition.Mode mode,
            LocalDate anchor, LocalDate from, LocalDate to, String ownerOverride, int datasetVersion) throws Exception {
        var definition = DailyBasicSyncAdapter.definition(true);
        if (datasetVersion != 1 || ownerOverride != null) {
            definition = new SyncJobDefinition(definition.jobId(), definition.version(), definition.datasetId(), datasetVersion,
                    ownerOverride == null ? definition.owner() : ownerOverride, definition.supportedModes(), definition.defaultMode(),
                    definition.parameters(), definition.ratePolicyRef(), definition.slicePolicyRef(), definition.verificationPolicyRef(),
                    definition.retry(), definition.timeout(), definition.budget(), definition.revisionDays(), definition.dependencies(),
                    definition.frequency(), definition.zone(), definition.enabled(), definition.dailyEligible());
        }
        var parameters = new LinkedHashMap<String, Object>();
        parameters.put("trade_dates", "NONE");
        if (mode == SyncJobDefinition.Mode.INCREMENTAL) parameters.put("checkpointAnchor", anchor);
        var request = definition.freeze(mode, parameters, from, to, to);
        String frozen = SyncRequestIdentity.snapshotJson(request);
        if (ownerOverride != null || datasetVersion != 1) {
            var json = JobDefinitionJson.mapper();
            ObjectNode root = (ObjectNode) json.readTree(frozen);
            ((ObjectNode) root.path("definition")).put("datasetVersion", datasetVersion);
            if (ownerOverride != null) ((ObjectNode) root.path("definition")).put("owner", ownerOverride);
            frozen = json.writeValueAsString(root);
        }
        ledger.createRun(new SyncRunLedger.Run(id, null, "data.daily_basic", 1, to.toString(), target, frozen));
        ledger.transition(id, 0, SyncRunState.RUNNING, "{}");
        ledger.transition(id, 1, SyncRunState.VERIFIED_EMPTY,
                "{\"sourceComplete\":true,\"returnedRows\":0,\"submittedRows\":0,\"responseEvidence\":\"offline-test-evidence\"}");
    }

    private static void addReceiptRun(SyncRunLedger ledger, Path ledgerPath, String id, String target,
            LocalDate anchor, List<LocalDate> dates) throws Exception {
        var parameters = new LinkedHashMap<String, Object>();
        parameters.put("trade_dates", DailyBasicSyncAdapter.encodeTradeDates(dates));
        parameters.put("checkpointAnchor", anchor);
        var request = DailyBasicJobService.definition().freeze(SyncJobDefinition.Mode.INCREMENTAL,
                parameters, anchor, dates.getLast(), dates.getLast());
        ledger.createRun(id, null, target, request);
        ledger.transition(id, 0, SyncRunState.RUNNING, "{}");
        String attempt = "attempt-receipt-backed";
        ledger.createChild(attempt, SyncRunLedger.Kind.ATTEMPT, id, id);
        ledger.transition(attempt, 0, SyncRunState.RUNNING, "{}");
        int totalRows = 0;
        int index = 0;
        for (LocalDate tradeDate : dates) {
            index++;
            var rows = index == 1 ? List.of(sourceRow(tradeDate)) : List.<ObjectNode>of();
            totalRows += rows.size();
            Path evidenceRoot = ledgerPath.getParent().resolve("sync-evidence").resolve(id);
            Files.createDirectories(evidenceRoot);
            Path sourcePath = evidenceRoot.resolve("source-" + index + ".json");
            var evidence = JobDefinitionJson.mapper().createObjectNode();
            evidence.put("endpoint", "daily_basic");
            evidence.putObject("parameters").put("trade_date", tradeDate.toString().replace("-", ""));
            evidence.set("fields", JobDefinitionJson.mapper().valueToTree(DailyBasicSource.FIELDS));
            evidence.put("tradeDate", tradeDate.toString());
            evidence.set("rows", JobDefinitionJson.mapper().valueToTree(rows));
            evidence.put("sourceComplete", true);
            evidence.put("apiMaximumRows", DailyBasicSource.API_ROW_CAP);
            Files.write(sourcePath, JobDefinitionJson.mapper().configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true)
                    .writeValueAsBytes(evidence));
            String fingerprint = fingerprint(tradeDate, rows);
            String slice = "slice-receipt-" + index;
            ledger.createChild(slice, SyncRunLedger.Kind.SLICE, id, attempt);
            ledger.transition(slice, 0, SyncRunState.RUNNING, "{}");
            var fetched = JobDefinitionJson.mapper().createObjectNode();
            fetched.put("returnedRows", rows.size()); fetched.put("sourceFingerprint", fingerprint);
            fetched.put("responseEvidence", sourcePath.toString()); fetched.putNull("cursor");
            ledger.transition(slice, 1, SyncRunState.FETCHED,
                    JobDefinitionJson.mapper().writeValueAsString(fetched));
            if (rows.isEmpty()) {
                ledger.transition(slice, 2, SyncRunState.VERIFIED_EMPTY,
                        "{\"sourceComplete\":true,\"returnedRows\":0,\"submittedRows\":0,\"responseEvidence\":\"offline-empty-date-receipt\"}");
            } else {
                ledger.transition(slice, 2, SyncRunState.VALIDATED, "{}");
                ledger.transition(slice, 3, SyncRunState.SUBMITTED, "{}");
                ledger.transition(slice, 4, SyncRunState.ACKNOWLEDGED, "{}");
                String proof = "{\"verification\":{\"passed\":true,\"expectedRows\":1,\"matchedRows\":1,"
                        + "\"actualRows\":1,\"mismatchedRows\":0,\"duplicateKeys\":0,\"missingKeys\":0,"
                        + "\"sourceFingerprint\":\"" + fingerprint + "\",\"readbackEvidence\":\"test-readback\",\"writerStopped\":false}}";
                ledger.transition(slice, 5, SyncRunState.VERIFIED, proof);
            }
        }
        String proof = "{\"verification\":{\"passed\":true,\"expectedRows\":" + totalRows
                + ",\"matchedRows\":" + totalRows + ",\"actualRows\":" + totalRows
                + ",\"mismatchedRows\":0,\"duplicateKeys\":0,\"missingKeys\":0,"
                + "\"sourceFingerprint\":\"test-run-fingerprint\",\"readbackEvidence\":\"test-run-readback\",\"writerStopped\":false}}";
        ledger.transition(attempt, 1, SyncRunState.VERIFIED, proof);
        ledger.transition(id, 1, SyncRunState.VERIFIED, proof);
    }

    private static ObjectNode sourceRow(LocalDate tradeDate) {
        var row = JobDefinitionJson.mapper().createObjectNode();
        for (String field : DailyBasicSource.FIELDS) {
            if (field.equals("ts_code")) row.put(field, "000001.SZ");
            else if (field.equals("trade_date")) row.put(field, tradeDate.toString().replace("-", ""));
            else row.putNull(field);
        }
        return row;
    }

    private static String fingerprint(LocalDate tradeDate, List<ObjectNode> rows) throws Exception {
        var body = new LinkedHashMap<String, Object>();
        body.put("endpoint", "daily_basic");
        body.put("parameters", Map.of("trade_date", tradeDate.toString().replace("-", "")));
        body.put("fields", DailyBasicSource.FIELDS);
        body.put("tradeDate", tradeDate);
        var rowMaps = new ArrayList<Map<String, com.fasterxml.jackson.databind.JsonNode>>();
        for (ObjectNode row : rows) {
            var values = new LinkedHashMap<String, com.fasterxml.jackson.databind.JsonNode>();
            row.fields().forEachRemaining(field -> values.put(field.getKey(), field.getValue()));
            rowMaps.add(values);
        }
        body.put("rows", rowMaps);
        byte[] bytes = JobDefinitionJson.mapper().configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true)
                .writeValueAsBytes(body);
        return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
