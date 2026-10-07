package com.zoutrankil.data.config;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.zaxxer.hikari.HikariDataSource;
import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.mapper.MacroCoreMonthlyMapper;
import com.zoutrankil.data.mapper.MacroCoreMonthlyViewMapper;
import com.zoutrankil.data.repository.*;
import com.zoutrankil.data.service.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.sql.ResultSet;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/** D105 SELECT only. Schema/native/current-code admission is performed separately by the coordinator. */
class MacroCoreMonthlyViewLiveReadAcceptanceTest {
    private static final Path DIRECTORY = Path.of("artifacts/java-migration/D105/commands");
    private static final Path D104 = Path.of("artifacts/java-migration/D104");
    private static final String PREFLIGHT_SHA = "fc4b839c44254aee9f221e1246c5279738cf33704eb12749f2f70691347f530b";
    private static final String D104_GATE_SHA = "a5fd14527a068185f222abf54292c0f8532a50d8ae2eb49f14e4fc6b369d1bde";
    private static final String D104_FINAL_SHA = "b1aec9b83be584059cbe2aad5d18240a14167915b5c37ad9f02568e586c318ca";
    private static final YearMonth JUNE = YearMonth.of(2026, 6), JULY = JUNE.plusMonths(1),
            AUGUST = JUNE.plusMonths(2), SEPTEMBER = JUNE.plusMonths(3);
    private static final List<YearMonth> MONTHS = List.of(JUNE, JULY, AUGUST);
    private static final List<String> FIELDS = MacroCoreMonthlyViewDataset.STORAGE_COLUMNS;
    private static final List<String> SOURCES = List.of("cn_cpi", "cn_ppi", "cn_pmi", "cn_m", "cn_gdp", "sf_month");
    private static final MacroCoreMonthlyViewMapper MAPPER = new MacroCoreMonthlyViewMapper();

    @Test void actualBoundedAliasReadsMatchBaseAuditAndOriginalOracleWithoutPublication() throws Exception {
        String enabled = System.getenv("D105_LIVE_READ");
        assumeTrue(enabled != null && !enabled.isBlank());
        String scope = "true".equalsIgnoreCase(enabled) ? "private" : enabled.toLowerCase(Locale.ROOT);
        assertTrue(Set.of("private", "formal").contains(scope), "D105_LIVE_READ must be private or formal");
        boolean isolated = scope.equals("private");
        Path output = DIRECTORY.resolve("java-view-read-" + scope + "-acceptance-20261007.json");
        assertFalse(Files.exists(output), "Acceptance evidence is CREATE_NEW and must not be replayed");
        var evidence = new LinkedHashMap<String, Object>();
        evidence.put("task_id", "D105"); evidence.put("scope", scope); evidence.put("started_at", Instant.now());
        evidence.put("DDL", 0); evidence.put("DML", 0); evidence.put("ILP", 0); evidence.put("base_writes", 0);
        evidence.put("formal_writes", 0); evidence.put("ledger_initialized", false); evidence.put("owner_invocations", 0);
        evidence.put("automatic_retry", false);
        try {
            Path admissionPath = DIRECTORY.resolve("coordinator-java-" + scope + "-admission-20261007.json");
            String admissionSha = required("D105_JAVA_ADMISSION_SHA256");
            JsonNode admission = admission(admissionPath, admissionSha, scope);
            evidence.put("java_admission", Map.of("path", admissionPath.toAbsolutePath().toString(), "sha256", admissionSha));
            evidence.put("admitted_code_bindings", admission.required("code_bindings"));
            Path auditPath = DIRECTORY.resolve(isolated ? "view-isolated-acceptance-20261007.json"
                    : "view-formal-readonly-audit-20261007.json");
            assertEquals(auditPath.toAbsolutePath().normalize(), Path.of(admission.required("actual_view_audit").required("path").asText()).toAbsolutePath().normalize());
            String admittedAuditHash = admission.required("actual_view_audit").required("sha256").asText();
            assertTrue(admittedAuditHash.matches("[0-9a-f]{64}")); assertEquals(admittedAuditHash, sha(auditPath));
            JsonNode audit = JobDefinitionJson.mapper().readTree(auditPath.toFile());
            assertEquals("D105", audit.required("task_id").asText());
            assertEquals(isolated ? "VERIFIED_ISOLATED_VIEW_READ" : "VERIFIED_FORMAL_VIEW_READ",
                    audit.required("status").asText());
            if (isolated) {
                assertEquals(1, audit.required("attempted_DDL").asInt()); assertEquals(1, audit.required("acknowledged_DDL").asInt());
                assertFalse(audit.required("owner_invoked").asBoolean()); assertTrue(audit.required("alias_created").asBoolean());
                assertEquals("ACKNOWLEDGED", audit.required("create_operation").required("ack").asText());
                checkEvidence(admission.required("private_attestation"));
                JsonNode proof = JobDefinitionJson.mapper().readTree(Path.of(admission.required("private_attestation").required("path").asText()).toFile());
                assertEquals(1, proof.required("protocol_version").asInt()); assertEquals("D105", proof.required("task_id").asText());
                assertEquals("VERIFIED_CURRENT_PRIVATE_VIEW_READ_ADMISSION", proof.required("status").asText());
                assertEquals(audit.required("private_target_attestation_after"), proof.required("private_target_attestation"));
                assertFalse(proof.required("create_producer_absence").required("original_identity_present").asBoolean());
                Instant creatorBirth = Instant.parse(audit.required("producer_identity").required("birth_utc").asText());
                for (JsonNode match : proof.required("create_producer_absence").required("matches")) {
                    assertEquals(audit.required("producer_identity").required("pid").longValue(), match.required("pid").longValue());
                    assertTrue(Instant.parse(match.required("birth_utc").asText()).isAfter(creatorBirth),
                            "Any reused PID must have a strictly later native birth than the stopped creator");
                }
                assertFalse(proof.required("ledger_mutated").asBoolean());
                for (String name : List.of("DDL", "DML", "ILP")) assertEquals(0, proof.required(name).asInt());
                evidence.put("private_attestation", admission.required("private_attestation"));
            } else {
                assertEquals(scope, audit.required("scope").asText()); assertEquals(0, audit.required("DDL").asInt());
            }
            for (String name : List.of("DML", "ILP", "base_writes", "formal_writes"))
                assertEquals(0, audit.required(name).asInt(), name);
            assertFalse(audit.required("ledger_mutated").asBoolean());
            assertFalse(audit.required("actual_source_increment").asBoolean());
            assertFalse(audit.required("actual_source_revision").asBoolean());
            assertEquals(0, audit.required("double_tolerance").asInt());
            assertEquals(3, audit.required("rows").asInt());
            for (Iterator<JsonNode> bindings = audit.required("d104_evidence").elements(); bindings.hasNext();)
                checkEvidence(bindings.next());
            Path preflightPath = D104.resolve("commands/macro-core-readonly-preflight-20261007.json");
            assertEquals(PREFLIGHT_SHA, sha(preflightPath));
            assertEquals(D104_GATE_SHA, sha(D104.resolve("coordinator-review-20261007.json")));
            assertEquals(D104_FINAL_SHA, sha(D104.resolve("commands/java-increment-acceptance-20261007.json")));
            JsonNode preflight = JobDefinitionJson.mapper().readTree(preflightPath.toFile());
            JsonNode priorJava = JobDefinitionJson.mapper().readTree(D104.resolve("commands/java-increment-acceptance-20261007.json").toFile());
            Path priorLedger = Path.of(priorJava.required("ledger").asText());
            var ledgerBefore = ledgerFiles(priorLedger); evidence.put("D104_ledger_files_before", ledgerBefore);
            var expected = parseRows(preflight.required("expected_oracle_rows"));
            assertEquals(MONTHS, expected.stream().map(MacroCoreMonthlyView::month).toList());
            checkOracleCapture(preflight.required("oracle_capture"), expected);
            compare(expected, parseRows(audit.required("base_rows")));
            compare(expected, parseRows(audit.required("actual_rows")));
            assertEquals(bits(expected), audit.required("actual_double_bits"));
            evidence.put("audit", Map.of("path", auditPath.toAbsolutePath().toString(), "sha256", admittedAuditHash));
            evidence.put("D104_preflight_sha256", PREFLIGHT_SHA); evidence.put("D104_final_receipt_sha256", D104_FINAL_SHA);
            evidence.put("D104_gate_sha256", D104_GATE_SHA);
            evidence.put("original_oracle_capture", preflight.required("oracle_capture"));
            var jvm = saveJvmIdentity(scope);
            evidence.put("jvm_identity", jvm); evidence.put("jvm_pid", jvm.required("jvm_pid").longValue());
            evidence.put("jvm_birth_utc", jvm.required("jvm_birth_utc").asText());
            evidence.put("native_checks_by_java_harness", 1);
            String view = isolated ? MacroCoreMonthlyViewDataset.ISOLATED_OBJECT : MacroCoreMonthlyViewDataset.FORMAL_OBJECT;
            String base = isolated ? "java_d104_macro_core_monthly_acceptance" : "macro_core_monthly";
            String host = isolated ? "127.0.0.1" : System.getenv().getOrDefault("APP_QUESTDB_HOST", "127.0.0.1");
            assertEquals("127.0.0.1", host, "Only the explicit local formal/private pair is admitted");
            int port = isolated ? 18852 : formalPort();
            try (var pool = pool("d105-" + scope + "-read", host, port,
                    isolated ? "admin" : required("APP_QUESTDB_USERNAME"),
                    isolated ? "quest" : required("APP_QUESTDB_PASSWORD"))) {
                var jdbc = new JdbcTemplate(pool); jdbc.setQueryTimeout(20);
                evidence.put("readback", verify(jdbc, view, base, isolated, audit, expected));
            }
            assertEquals(admittedAuditHash, sha(auditPath), "Actual audit must remain immutable");
            assertEquals(admission, admission(admissionPath, admissionSha, scope));
            var ledgerAfter = ledgerFiles(priorLedger); assertEquals(ledgerBefore, ledgerAfter);
            evidence.put("D104_ledger_files_after", ledgerAfter); evidence.put("ledger_mutated", false);
            assertEquals(PREFLIGHT_SHA, sha(preflightPath)); assertEquals(D104_FINAL_SHA,
                    sha(D104.resolve("commands/java-increment-acceptance-20261007.json")));
            evidence.put("status", "VERIFIED_" + scope.toUpperCase(Locale.ROOT) + "_BOUNDED_VIEW_READ");
            evidence.put("double_tolerance", 0); evidence.put("actual_source_increment_during_D105", false);
            evidence.put("actual_source_revision_during_D105", false);
            evidence.put("source_increment_provenance", "Prior accepted D104 August source append/base publication, reused unchanged");
            evidence.put("cancellation_scope", "Pre-cancelled ReadGroup token before any reader invocation only");
            evidence.put("finished_at", Instant.now()); saveNew(output, evidence);
        } catch (Exception | AssertionError failure) {
            evidence.put("status", "FAILED"); evidence.put("error_type", failure.getClass().getSimpleName());
            evidence.put("finished_at", Instant.now());
            saveNew(DIRECTORY.resolve("java-view-read-" + scope + "-failed-" + UUID.randomUUID() + "-20261007.json"), evidence);
            throw failure;
        }
    }

    private static Map<String, Object> verify(JdbcTemplate jdbc, String view, String base, boolean isolated,
            JsonNode audit, List<MacroCoreMonthlyView> expected) throws Exception {
        var before = snapshot(jdbc, view, base);
        compareAudit(before, audit, isolated, base);
        var directBase = direct(jdbc, base); compare(expected, directBase);
        var directView = direct(jdbc, view); compare(directBase, directView);
        var reader = spy(new QuestDbBoundedReader(jdbc));
        var repo = new MacroCoreMonthlyViewReadRepository(reader, view);
        var parent = new MacroCoreMonthlyReadRepository(reader, base);
        var first = repo.findRange(JUNE, AUGUST, 1, null);
        compare(expected.subList(0, 1), first.rows()); assertTrue(first.hasMore()); assertNotNull(first.nextCursor());
        assertPhysicalToken(first.sourceVersion(), view, base);
        var second = repo.findRange(JUNE, AUGUST, 1, first.nextCursor());
        compare(expected.subList(1, 2), second.rows()); assertFalse(second.hasMore());
        assertEquals(first.sourceVersion(), second.sourceVersion());
        var replay = repo.findRange(JUNE, AUGUST, 12, null); compare(expected.subList(0, 2), replay.rows());
        assertFalse(replay.hasMore()); assertEquals(first.sourceVersion(), replay.sourceVersion());
        var later = repo.findRange(JULY, SEPTEMBER, 12, null); compare(expected.subList(1, 3), later.rows());
        assertFalse(later.hasMore()); assertEquals(first.sourceVersion(), later.sourceVersion());
        assertThrows(IllegalArgumentException.class, () -> repo.findRange(JUNE, SEPTEMBER, 1, first.nextCursor()));
        var actual = new ArrayList<MacroCoreMonthlyView>(); var pageEvidence = new ArrayList<Map<String, Object>>();
        DatasetReadCursor cursor = null;
        do {
            var page = repo.findRange(JUNE, SEPTEMBER, 1, cursor);
            assertEquals(1, page.rows().size()); assertEquals(first.sourceVersion(), page.sourceVersion());
            actual.addAll(page.rows()); pageEvidence.add(Map.of("page", pageEvidence.size() + 1,
                    "month", page.rows().getFirst().month(), "has_more", page.hasMore(), "source_version", page.sourceVersion()));
            cursor = page.nextCursor(); assertTrue(pageEvidence.size() <= 3, "Finite output must not loop or add a fourth month");
        } while (cursor != null);
        assertEquals(3, pageEvidence.size()); compare(expected, actual);
        for (int index = 0; index < MONTHS.size(); index++) {
            var key = repo.findKey(new MacroCoreMonthlyViewKey(MONTHS.get(index)));
            compare(expected.subList(index, index + 1), key.rows()); assertFalse(key.hasMore());
            assertEquals(first.sourceVersion(), key.sourceVersion());
        }
        compare(expected.subList(0, 1), repo.findForMonth(JUNE).rows());
        var empty = repo.findRange(SEPTEMBER, SEPTEMBER.plusMonths(1), 12, null);
        assertTrue(empty.rows().isEmpty()); assertFalse(empty.hasMore()); assertEquals(first.sourceVersion(), empty.sourceVersion());
        assertTrue(repo.findForMonth(SEPTEMBER).rows().isEmpty());
        var typedBase = parent.findRange(JUNE, SEPTEMBER, 12, null); assertFalse(typedBase.hasMore());
        var baseMapper = new MacroCoreMonthlyMapper();
        compare(expected, typedBase.rows().stream().map(row -> MAPPER.fromValues(baseMapper.values(row))).toList());
        var registry = new DatasetRegistry(List.of(repo, parent));
        var group = new ReadGroupConfiguration().readGroupReader(registry, reader);
        var query = new DatasetReadQuery(FIELDS, Map.of(), "month", JUNE.atDay(1), SEPTEMBER.atDay(1), 12, null);
        Path requestPath = DIRECTORY.resolve("strictread-group-request-D105-" + (isolated ? "private" : "formal") + "-20261007.json");
        saveNew(requestPath, Map.of("timeoutMillis", 30000, "members", List.of(Map.of(
                "memberId", "macro-view", "datasetId", repo.definition().datasetId(), "definitionVersion", 1, "query", query))));
        var request = group.readRequest(requestPath); var grouped = group.read(request, () -> false);
        assertTrue(grouped.complete()); assertFalse(grouped.atomicSnapshot());
        var member = grouped.require("macro-view"); assertEquals(MacroCoreMonthlyView.class, member.rowType());
        var typed = member.typedPage(MacroCoreMonthlyView.class); compare(actual, typed.rows());
        assertFalse(typed.hasMore()); assertEquals(first.sourceVersion(), typed.sourceVersion());
        clearInvocations(reader); var cancelled = group.read(request, () -> true);
        assertFalse(cancelled.complete()); assertEquals(ReadGroupReader.Status.CANCELLED, cancelled.require("macro-view").status());
        assertNull(cancelled.require("macro-view").page()); verifyNoInteractions(reader);
        for (YearMonth stop : List.of(JUNE, JUNE.minusMonths(1), JUNE.plusMonths(13)))
            assertThrows(IllegalArgumentException.class, () -> repo.findRange(JUNE, stop, 1, null));
        assertThrows(IllegalArgumentException.class, () -> repo.findRange(JUNE, SEPTEMBER, 13, null));
        assertThrows(IllegalArgumentException.class, () -> repo.findPage(new DatasetReadQuery(FIELDS,
                Map.of("month", JUNE.atDay(2)), null, null, null, 1, null)));
        assertThrows(IllegalArgumentException.class, () -> repo.findPage(new DatasetReadQuery(List.of("month"),
                Map.of("month", JUNE.atDay(1)), null, null, null, 1, null)));
        verifyNoInteractions(reader);
        for (var capability : List.of(DatasetDefinition.Capability.WRITE, DatasetDefinition.Capability.STATIC_REPLACE,
                DatasetDefinition.Capability.WAL_REPLACE))
            assertThrows(IllegalArgumentException.class, () -> repo.definition().requireCapability(capability));
        var limits = new DatasetWritePreparation.Limits(12, 65536);
        assertThrows(IllegalArgumentException.class, () -> DatasetWritePreparation.prepare(repo.definition(), actual, MAPPER::values, limits));
        assertThrows(IllegalArgumentException.class, () -> DatasetWritePreparation.prepareStatic(repo.definition(), actual, MAPPER::values, limits));
        assertThrows(IllegalArgumentException.class, () -> DatasetWritePreparation.prepareWalReplace(repo.definition(), actual, MAPPER::values, limits));
        var after = snapshot(jdbc, view, base); assertEquals(before, after, "SELECT acceptance must preserve all seven table/view/schema frontiers");
        compareAudit(after, audit, isolated, base);
        var result = new LinkedHashMap<String, Object>();
        result.put("metadata_before", before); result.put("metadata_after", after);
        result.put("actual_view_rows", actual.stream().map(row -> MAPPER.values(row).asMap()).toList());
        result.put("independent_jdbc_base_rows", directBase.stream().map(row -> MAPPER.values(row).asMap()).toList());
        result.put("independent_jdbc_view_rows", directView.stream().map(row -> MAPPER.values(row).asMap()).toList());
        result.put("oracle_rows", expected.stream().map(row -> MAPPER.values(row).asMap()).toList());
        result.put("actual_double_bits", bits(actual)); result.put("pages", pageEvidence);
        result.put("first_actual_cursor", first.nextCursor()); result.put("physical_view_base_token", first.sourceVersion());
        result.put("base_physical_token", typedBase.sourceVersion());
        result.put("unique_field_comparisons", 27); result.put("nullable_double_slot_comparisons", 24);
        result.put("nonnull_double_rawbit_comparisons", 22); result.put("null_double_comparisons", 2);
        result.put("first_june_july_repeat_exact", true); result.put("july_august_later_read_window_exact", true);
        result.put("september_empty_without_failure", true); result.put("changed_range_cursor_rejected", true);
        result.put("configured_read_group", grouped); result.put("pre_cancelled_group", cancelled);
        result.put("pre_cancelled_reader_invocations", 0); result.put("invalid_month_projection_or_budget_before_reader", true);
        result.put("typed_write_and_replacement_rejected", true);
        result.put("read_group_request", Map.of("path", requestPath.toAbsolutePath().toString(), "sha256", sha(requestPath)));
        return result;
    }

    private static List<MacroCoreMonthlyView> direct(JdbcTemplate jdbc, String table) {
        assertTrue(Set.of("macro_core_monthly", "v_macro_core_monthly", "java_d104_macro_core_monthly_acceptance",
                "java_d105_v_macro_core_monthly_acceptance").contains(table));
        String projection = "cast(month as long) AS month," + String.join(",", FIELDS.subList(1, 9));
        return jdbc.query(connection -> {
            var statement = connection.prepareStatement("SELECT " + projection + " FROM \"" + table
                    + "\" WHERE month>=cast(? as TIMESTAMP) AND month<cast(? as TIMESTAMP) ORDER BY month LIMIT 4");
            statement.setQueryTimeout(20); statement.setMaxRows(4); statement.setFetchSize(4);
            statement.setLong(1, new MacroCoreMonthlyViewKey(JUNE).storageMicros());
            statement.setLong(2, new MacroCoreMonthlyViewKey(SEPTEMBER).storageMicros()); return statement;
        }, (rs, index) -> {
            long micros = rs.getLong("month"); assertFalse(rs.wasNull());
            var key = MacroCoreMonthlyViewKey.fromStorage(Instant.ofEpochSecond(Math.floorDiv(micros, 1_000_000L),
                    Math.floorMod(micros, 1_000_000L) * 1000L));
            var values = new LinkedHashMap<String, Object>(); values.put("month", key.storageDate());
            for (String name : FIELDS.subList(1, 9)) { double value = rs.getDouble(name); values.put(name, rs.wasNull() ? null : value); }
            return MAPPER.fromValues(values);
        });
    }

    private static Map<String, Object> snapshot(JdbcTemplate jdbc, String view, String base) {
        var before = tables(jdbc, base); var alias = alias(jdbc, view);
        var schemas = new LinkedHashMap<String, Object>();
        for (String table : List.of(view, base)) {
            var columns = jdbc.query(connection -> {
                var statement = connection.prepareStatement("SELECT \"column\",\"type\",designated,upsertKey FROM table_columns('" + table + "') LIMIT 10");
                statement.setQueryTimeout(20); statement.setMaxRows(10); statement.setFetchSize(10); return statement;
            }, (rs, index) -> Map.of("column", requiredText(rs, "column"), "type", requiredText(rs, "type"),
                    "designated", requiredFlag(rs, "designated"), "upsertKey", requiredFlag(rs, "upsertKey")));
            assertEquals(9, columns.size()); schemas.put(table, columns);
        }
        var after = tables(jdbc, base); var aliasAfter = alias(jdbc, view);
        assertEquals(before, after); assertEquals(alias, aliasAfter);
        return Map.of("tables", after, "view", aliasAfter, "schemas", schemas);
    }

    private static Map<String, Object> tables(JdbcTemplate jdbc, String base) {
        var result = new LinkedHashMap<String, Object>(); var names = new ArrayList<>(SOURCES); names.add(base);
        for (String table : names) {
            var before = tableMetadata(jdbc, table);
            var counts = jdbc.query(connection -> {
                var statement = connection.prepareStatement("SELECT count() AS actual_rows FROM \"" + table + "\" LIMIT 2");
                statement.setQueryTimeout(20); statement.setMaxRows(2); statement.setFetchSize(2); return statement;
            }, (rs, index) -> nullableLong(rs, "actual_rows"));
            assertEquals(1, counts.size()); assertNotNull(counts.getFirst()); assertTrue(counts.getFirst() >= 0);
            var after = tableMetadata(jdbc, table); assertEquals(before, after, "COUNT requires a stable physical/WAL bracket");
            var state = new LinkedHashMap<>(after); state.put("actual_select_count", counts.getFirst()); result.put(table, state);
        }
        return result;
    }

    private static Map<String, Object> tableMetadata(JdbcTemplate jdbc, String table) {
            var rows = jdbc.query(connection -> {
                var statement = connection.prepareStatement("SELECT t.id,t.directoryName,t.table_txn,t.wal_txn,t.table_row_count,t.partitionBy,"
                        + "t.walEnabled,t.dedup,t.matView,t.designatedTimestamp,t.table_suspended,t.wal_pending_row_count,"
                        + "w.sequencerTxn,w.writerTxn,w.bufferedTxnSize,w.suspended FROM tables() t JOIN wal_tables() w "
                        + "ON w.name=t.table_name WHERE t.table_name=? LIMIT 2");
                statement.setString(1, table); statement.setQueryTimeout(20); statement.setMaxRows(2); statement.setFetchSize(2); return statement;
            }, (rs, index) -> {
                var row = new LinkedHashMap<String, Object>();
                for (String field : List.of("id", "table_txn", "wal_txn", "table_row_count", "wal_pending_row_count",
                        "sequencerTxn", "writerTxn", "bufferedTxnSize")) row.put(field, nullableLong(rs, field));
                for (String field : List.of("directoryName", "partitionBy", "designatedTimestamp")) row.put(field, requiredText(rs, field));
                for (String field : List.of("walEnabled", "dedup", "matView", "table_suspended", "suspended")) row.put(field, requiredFlag(rs, field));
                return row;
            });
            assertEquals(1, rows.size()); return rows.getFirst();
    }

    private static Map<String, Object> alias(JdbcTemplate jdbc, String view) {
        var rows = jdbc.query(connection -> {
            var statement = connection.prepareStatement("SELECT v.view_name,v.view_sql,v.view_table_dir_name,v.view_status,v.invalidation_reason,"
                    + "v.view_status_update_time,cast(v.view_status_update_time as long) AS view_status_update_micros,"
                    + "t.id,t.directoryName,t.partitionBy,t.walEnabled,t.dedup,t.matView,t.designatedTimestamp "
                    + "FROM views() v JOIN tables() t ON t.table_name=v.view_name WHERE v.view_name=? LIMIT 2");
            statement.setString(1, view); statement.setQueryTimeout(20); statement.setMaxRows(2); statement.setFetchSize(2); return statement;
        }, (rs, index) -> {
            var row = new LinkedHashMap<String, Object>();
            for (String field : List.of("view_name", "view_sql", "view_table_dir_name", "view_status", "view_status_update_time",
                    "directoryName", "partitionBy", "designatedTimestamp")) row.put(field, requiredText(rs, field));
            row.put("invalidation_reason", rs.getString("invalidation_reason")); row.put("id", nullableLong(rs, "id"));
            row.put("view_status_update_micros", nullableLong(rs, "view_status_update_micros"));
            for (String field : List.of("walEnabled", "dedup", "matView")) row.put(field, requiredFlag(rs, field));
            return row;
        });
        assertEquals(1, rows.size()); return rows.getFirst();
    }

    @SuppressWarnings("unchecked")
    private static void compareAudit(Map<String, Object> snapshot, JsonNode audit, boolean isolated, String base) {
        var actualTables = (Map<String, Map<String, Object>>) snapshot.get("tables");
        JsonNode reference = audit.required(isolated ? "private_after" : "formal_after").required("tables");
        for (String table : actualTables.keySet()) {
            var actual = actualTables.get(table); JsonNode state = reference.required(table);
            assertTrue(state.required("exists").asBoolean()); assertTrue(state.required("settled").asBoolean());
            JsonNode physical = state.required("physical"), wal = state.required("wal");
            for (String field : List.of("id", "table_txn", "table_row_count", "wal_pending_row_count"))
                compareNullableLong(physical.required(field), (Long) actual.get(field));
            for (String field : List.of("directoryName", "partitionBy", "designatedTimestamp"))
                assertEquals(physical.required(field).asText(), actual.get(field));
            for (String field : List.of("walEnabled", "dedup", "table_suspended"))
                assertEquals(physical.required(field).asBoolean(), actual.get(field));
            for (String field : List.of("sequencerTxn", "writerTxn", "bufferedTxnSize"))
                compareNullableLong(wal.required(field), (Long) actual.get(field));
            assertEquals(wal.required("suspended").asBoolean(), actual.get("suspended"));
            compareNullableLong(state.required("actual_select_count"), (Long) actual.get("actual_select_count"));
            assertEquals(actual.get("writerTxn"), actual.get("sequencerTxn")); assertEquals(actual.get("writerTxn"), actual.get("wal_txn"));
            assertEquals(0L, actual.get("wal_pending_row_count")); assertEquals(0L, actual.get("bufferedTxnSize"));
        }
        var actualView = (Map<String, Object>) snapshot.get("view"); JsonNode view = audit.required("view_metadata_after");
        for (String field : List.of("view_name", "view_sql", "view_table_dir_name", "view_status"))
            assertEquals(view.required(field).asText(), actualView.get(field));
        assertNull(actualView.get("invalidation_reason")); assertTrue(view.required("invalidation_reason").isNull());
        // Keep raw PG textual metadata intact. The reader pins its full string; epoch Timestamp carriers are not used.
        assertFalse(((String) actualView.get("view_status_update_time")).isBlank());
        Instant statusUpdated = Instant.parse(view.required("view_status_update_time").asText());
        assertEquals(0, statusUpdated.getNano() % 1000, "Native view status history has microsecond precision");
        assertEquals(Long.valueOf(Math.addExact(Math.multiplyExact(statusUpdated.getEpochSecond(), 1_000_000L),
                statusUpdated.getNano() / 1000L)), actualView.get("view_status_update_micros"));
        JsonNode physical = audit.required("view_physical_metadata_after");
        compareNullableLong(physical.required("id"), (Long) actualView.get("id"));
        for (String field : List.of("directoryName", "partitionBy", "designatedTimestamp"))
            assertEquals(physical.required(field).asText(), actualView.get(field));
        for (String field : List.of("walEnabled", "dedup", "matView"))
            assertEquals(physical.required(field).asBoolean(), actualView.get(field));
        if (isolated) {
            assertEquals(15L, actualTables.get(base).get("id")); assertEquals(5L, actualTables.get(base).get("table_txn"));
            assertEquals(3L, actualTables.get(base).get("table_row_count"));
        }
    }

    private static List<MacroCoreMonthlyView> parseRows(JsonNode rows) {
        assertTrue(rows.isArray()); assertEquals(3, rows.size()); var result = new ArrayList<MacroCoreMonthlyView>();
        for (JsonNode row : rows) {
            var values = new LinkedHashMap<String, Object>();
            for (String field : FIELDS) {
                JsonNode value = row.required(field);
                if (field.equals("month")) values.put(field, MacroCoreMonthlyViewKey.fromStorage(
                        value.asText().length() == 10 ? LocalDate.parse(value.asText()).atStartOfDay().toInstant(ZoneOffset.UTC)
                                : Instant.parse(value.asText())).storageDate());
                else { assertTrue(value.isNull() || value.isNumber()); values.put(field, value.isNull() ? null : value.doubleValue()); }
            }
            result.add(MAPPER.fromValues(values));
        }
        return result;
    }
    private static void checkOracleCapture(JsonNode capture, List<MacroCoreMonthlyView> expected) throws Exception {
        checkEvidence(capture); var lines = Files.readAllLines(Path.of(capture.required("path").asText()));
        assertEquals(3, lines.size());
        for (int i = 0; i < lines.size(); i++) {
            var record = JobDefinitionJson.mapper().readTree(lines.get(i));
            // Parse with explicit complete-row count, preserving its original raw-bit oracle independently.
            var values = new LinkedHashMap<String, Object>(); JsonNode row = record.required("values");
            values.put("month", MacroCoreMonthlyViewKey.fromStorage(Instant.parse(row.required("month").asText())).storageDate());
            for (String field : FIELDS.subList(1, 9)) { var value = row.required(field); values.put(field, value.isNull() ? null : value.doubleValue()); }
            compare(List.of(expected.get(i)), List.of(MAPPER.fromValues(values)));
            assertEquals(bits(List.of(expected.get(i))).get(0), record.required("raw_double_bits"));
        }
    }
    private static void compare(List<MacroCoreMonthlyView> expected, List<MacroCoreMonthlyView> actual) {
        assertEquals(expected.size(), actual.size());
        for (int i = 0; i < expected.size(); i++) {
            var left = MAPPER.values(expected.get(i)).asMap(); var right = MAPPER.values(actual.get(i)).asMap();
            assertEquals(left.get("month"), right.get("month"));
            for (String field : FIELDS.subList(1, 9)) {
                Object a = left.get(field), b = right.get(field);
                if (a == null || b == null) assertEquals(a, b, field);
                else assertEquals(Double.doubleToRawLongBits((Double) a), Double.doubleToRawLongBits((Double) b), field);
            }
        }
    }
    private static JsonNode bits(List<MacroCoreMonthlyView> rows) {
        var output = JobDefinitionJson.mapper().createArrayNode();
        for (var row : rows) {
            var entry = JobDefinitionJson.mapper().createObjectNode();
            for (String field : FIELDS.subList(1, 9)) {
                var value = (Double) MAPPER.values(row).asMap().get(field);
                if (value == null) entry.putNull(field); else entry.put(field, String.format(Locale.ROOT, "%016x", Double.doubleToRawLongBits(value)));
            }
            output.add(entry);
        }
        return output;
    }
    private static void assertPhysicalToken(String token, String view, String base) {
        assertNotNull(token); assertTrue(token.startsWith("view:" + view + ":directory:"));
        assertTrue(token.contains(":body:")); assertTrue(token.contains(":status-updated:"));
        assertTrue(token.contains(":source:table:" + base + ":id:")); assertTrue(token.contains(":txn:"));
    }
    private static Long nullableLong(ResultSet rs, String field) throws java.sql.SQLException {
        long value = rs.getLong(field); return rs.wasNull() ? null : value;
    }
    private static String requiredText(ResultSet rs, String field) throws java.sql.SQLException {
        String value = rs.getString(field); assertNotNull(value); assertFalse(value.isBlank()); return value;
    }
    private static boolean requiredFlag(ResultSet rs, String field) throws java.sql.SQLException {
        boolean value = rs.getBoolean(field); assertFalse(rs.wasNull()); return value;
    }
    private static void compareNullableLong(JsonNode expected, Long actual) {
        if (expected.isNull()) assertNull(actual); else { assertTrue(expected.isIntegralNumber()); assertNotNull(actual); assertEquals(expected.longValue(), actual.longValue()); }
    }
    private static HikariDataSource pool(String name, String host, int port, String user, String password) {
        var pool = new HikariDataSource(); pool.setPoolName(name);
        pool.setJdbcUrl("jdbc:postgresql://" + host + ":" + port + "/qdb?socketTimeout=20&connectTimeout=10");
        pool.setUsername(user); pool.setPassword(password); pool.setMaximumPoolSize(2); pool.setMinimumIdle(0);
        pool.setConnectionTimeout(10000); pool.setInitializationFailTimeout(-1); return pool;
    }
    private static int formalPort() {
        String configured = System.getenv().getOrDefault("APP_QUESTDB_PGPORT",
                System.getenv().getOrDefault("APP_QUESTDB_PG_PORT", "8812"));
        int port = Integer.parseInt(configured); assertEquals(8812, port); return port;
    }
    private static String required(String name) {
        String value = System.getenv(name); assertNotNull(value, "Required environment: " + name);
        assertFalse(value.isBlank(), "Required environment: " + name); return value;
    }
    private static JsonNode admission(Path path, String expectedHash, String scope) throws Exception {
        assertTrue(expectedHash.matches("[0-9a-f]{64}")); assertEquals(expectedHash, sha(path));
        JsonNode gate = JobDefinitionJson.mapper().readTree(path.toFile());
        assertEquals("D105", gate.required("task_id").asText()); assertEquals(1, gate.required("protocol_version").asInt());
        assertEquals("PASS", gate.required("status").asText()); assertEquals(scope, gate.required("scope").asText());
        assertEquals("accepted_for_bounded_view_read", gate.required("decision").asText());
        checkEvidence(gate.required("actual_view_audit")); checkEvidence(gate.required("java_test"));
        Path self = Path.of("src/test/java/com/zoutrankil/data/config/MacroCoreMonthlyViewLiveReadAcceptanceTest.java").toAbsolutePath().normalize();
        assertEquals(self, Path.of(gate.required("java_test").required("path").asText()).toAbsolutePath().normalize());
        var bindings = gate.required("code_bindings"); assertTrue(bindings.isArray()); assertTrue(bindings.size() >= 9 && bindings.size() <= 64);
        var paths = new HashSet<Path>();
        for (JsonNode binding : bindings) {
            checkEvidence(binding); Path item = Path.of(binding.required("path").asText()).toAbsolutePath().normalize();
            assertTrue(paths.add(item), "Duplicated admission code path");
        }
        for (String required : List.of("src/main/java/com/zoutrankil/data/domain/MacroCoreMonthlyView.java",
                "src/main/java/com/zoutrankil/data/domain/MacroCoreMonthlyViewKey.java",
                "src/main/java/com/zoutrankil/data/domain/MacroCoreMonthlyViewDataset.java",
                "src/main/java/com/zoutrankil/data/mapper/MacroCoreMonthlyViewMapper.java",
                "src/main/java/com/zoutrankil/data/repository/MacroCoreMonthlyViewReadRepository.java",
                "src/main/java/com/zoutrankil/data/repository/QuestDbMacroCoreViewReadGuard.java",
                "src/main/java/com/zoutrankil/data/repository/QuestDbBoundedReader.java",
                "src/main/java/com/zoutrankil/data/config/ReadGroupConfiguration.java"))
            assertTrue(paths.contains(Path.of(required).toAbsolutePath().normalize()), "Missing current D105 admission code binding");
        assertTrue(paths.contains(self));
        if (scope.equals("private")) checkEvidence(gate.required("private_attestation"));
        return gate;
    }
    private static JsonNode saveJvmIdentity(String scope) throws Exception {
        long pid = ProcessHandle.current().pid();
        String script = "$ErrorActionPreference='Stop';$d105JvmRecord=Get-CimInstance Win32_Process -Filter 'ProcessId=" + pid
                + "';if($null -eq $d105JvmRecord){throw 'Current Java process absent'};[pscustomobject]@{pid=[long]$d105JvmRecord.ProcessId;"
                + "parent_pid=[long]$d105JvmRecord.ParentProcessId;name=$d105JvmRecord.Name;birth_utc=$d105JvmRecord.CreationDate.ToUniversalTime().ToString('o')}|ConvertTo-Json -Compress";
        Path powershell = Path.of(required("SystemRoot"), "System32/WindowsPowerShell/v1.0/powershell.exe");
        var process = new ProcessBuilder(powershell.toString(), "-NoProfile", "-NonInteractive", "-Command", script).redirectErrorStream(true).start();
        JsonNode nativeIdentity;
        try {
            assertTrue(process.waitFor(30, TimeUnit.SECONDS), "Read-only current JVM identity observation timeout");
            byte[] output = process.getInputStream().readNBytes(16385); assertTrue(output.length <= 16384); assertEquals(0, process.exitValue());
            nativeIdentity = JobDefinitionJson.mapper().readTree(output); assertEquals(pid, nativeIdentity.required("pid").longValue());
            assertEquals("java.exe", nativeIdentity.required("name").asText().toLowerCase(Locale.ROOT));
            Instant.parse(nativeIdentity.required("birth_utc").asText());
        } finally {
            if (process.isAlive()) { process.destroyForcibly(); assertTrue(process.waitFor(10, TimeUnit.SECONDS)); }
            process.getInputStream().close();
        }
        Path file = DIRECTORY.resolve("java-view-read-" + scope + "-jvm-identity-20261007.json");
        var identity = JobDefinitionJson.mapper().createObjectNode(); identity.put("task_id", "D105"); identity.put("scope", scope);
        identity.put("jvm_pid", pid); identity.put("jvm_birth_utc", nativeIdentity.required("birth_utc").asText());
        identity.put("jvm_birth_source", "fresh Win32_Process.CreationDate UTC before connections");
        identity.put("jvm_process_handle_start_informational", ProcessHandle.current().info().startInstant().orElseThrow().toString());
        identity.set("native", nativeIdentity); identity.put("identity_child_pid", process.pid()); identity.put("identity_child_stopped", true);
        identity.put("saved_before_connections", true); identity.put("observed_at", Instant.now().toString());
        saveNew(file, identity); identity.put("path", file.toAbsolutePath().toString()); identity.put("sha256", sha(file)); return identity;
    }
    private static Map<String, Object> ledgerFiles(Path ledger) throws Exception {
        assertTrue(Files.isRegularFile(ledger), "Existing D104 ledger must not be created by the VIEW reader");
        var result = new LinkedHashMap<String, Object>();
        for (Path file : List.of(ledger, Path.of(ledger + "-wal"), Path.of(ledger + "-shm"))) {
            boolean exists = Files.isRegularFile(file); var record = new LinkedHashMap<String, Object>();
            record.put("exists", exists);
            if (exists) { assertTrue(Files.size(file) <= 64 * 1024 * 1024); record.put("bytes", Files.size(file)); record.put("sha256", sha(file)); }
            result.put(file.toAbsolutePath().toString(), record);
        }
        return result;
    }
    private static void checkEvidence(JsonNode evidence) throws Exception {
        assertEquals(evidence.required("sha256").asText(), sha(Path.of(evidence.required("path").asText())));
    }
    private static String sha(Path path) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
    }
    private static void saveNew(Path path, Object value) throws Exception {
        Files.createDirectories(path.getParent());
        Files.writeString(path, JobDefinitionJson.mapper().writerWithDefaultPrettyPrinter().writeValueAsString(value),
                StandardOpenOption.CREATE_NEW);
    }
}
