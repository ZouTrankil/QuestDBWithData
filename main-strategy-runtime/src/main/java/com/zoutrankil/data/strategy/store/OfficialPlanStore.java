package com.zoutrankil.data.strategy.store;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Canonical SQLite official-plan authority, compatible with the Python v3 tables.
 * Imports explicit official JSON only; scoring and target selection are separate owners.
 * Read operations never create a database, migrate it, or bind old plans to new scores.
 */
public final class OfficialPlanStore {
    public static final String DATABASE_NAME = "main_strategy_plans.sqlite3";
    public static final int SCHEMA_VERSION = 3;
    private static final String KIND = "official_weekly_plan";
    private static final long MAX_SNAPSHOT_BYTES = 8 * 1024 * 1024;
    private final Path path;

    public OfficialPlanStore(Path path) { this.path = Objects.requireNonNull(path).toAbsolutePath().normalize(); }
    public Path path() { return path; }
    public static OfficialPlanStore forPlanRoot(Path root) { return new OfficialPlanStore(root.resolve(DATABASE_NAME)); }

    /** Explicit frozen-input binding is mandatory; missing training/score identities fail before opening SQLite. */
    public StoredOfficialPlan importSnapshot(Path source, String expectedSourceSha256, PlanInputProvenance provenance) {
        if (provenance == null) throw new PlanStoreException("official plan import requires frozen score and training recipe evidence");
        PlanInputProvenance.requireSha256(expectedSourceSha256, "source snapshot");
        Path snapshot = Objects.requireNonNull(source).toAbsolutePath().normalize();
        byte[] bytes = readSnapshot(snapshot);
        if (!CanonicalPlanJson.sha256(bytes).equals(expectedSourceSha256))
            throw new PlanStoreException("official plan source SHA-256 mismatch: " + snapshot);
        OfficialWeeklyPlan plan = CanonicalPlanJson.read(bytes);
        if (!provenance.maxCompleteDate().equals(plan.signalDate()))
            throw new PlanStoreException("official plan score frontier must equal its signal date; future or incomplete inputs are forbidden");
        String digest = plan.semanticSha256(), planId = "msp_" + digest.substring(0, 24);
        String now = Instant.now().toString();
        try {
            boolean existed = Files.exists(path);
            if (existed) try (Connection existing = reader()) { requireSchema(existing); }
            Files.createDirectories(path.getParent());
            Path frozenSource = freezeSource(bytes, expectedSourceSha256);
            try (Connection connection = writer()) {
                if (existed) requireSchema(connection);
                executeScript(connection, OfficialPlanSchema.V3);
                executeScript(connection, OfficialPlanSchema.IMPORT_RECEIPTS);
                // An immediate transaction serializes same-date conflict checks and alias updates.
                execute(connection, "BEGIN IMMEDIATE");
                try {
                    verifyCurrentUniqueness(connection);
                    Optional<StoredOfficialPlan> current = findCurrent(connection, plan);
                    if (current.isPresent() && !current.get().plan().targetSymbols().equals(plan.targetSymbols()))
                        throw new PlanStoreException("same-date official target conflict: existing="
                                + current.get().plan().targetSymbols() + ", imported=" + plan.targetSymbols());
                    boolean created = !exists(connection, "SELECT 1 FROM strategy_plans WHERE content_sha256=?", digest);
                    if (created) insertPlan(connection, planId, digest, now, plan);
                    else {
                        StoredOfficialPlan existing = readById(connection, planId, false);
                        if (!storageProjection(existing.plan()).equals(storageProjection(plan)))
                            throw new PlanStoreException("existing semantic identity has inconsistent typed authority rows");
                        if (existing.provenance().isPresent() && !existing.provenance().get().equals(provenance))
                            throw new PlanStoreException("an imported official identity cannot be rebound to a different score run or recipe");
                        update(connection, "UPDATE strategy_plans SET diagnostic_payload_path=? WHERE plan_id=?",
                                plan.payload().path("diagnostic_payload_path").asText(), planId);
                    }
                    update(connection, "UPDATE strategy_plans SET is_current=0 WHERE strategy=? AND plan_kind=? AND signal_date=? AND execution_date=? AND plan_id<>?",
                            plan.strategy(), KIND, plan.signalDate().toString(), plan.executionDate().toString(), planId);
                    update(connection, "UPDATE strategy_plans SET is_current=1 WHERE plan_id=?", planId);
                    updateReceipt(connection, planId, plan, frozenSource, expectedSourceSha256, provenance, now);
                    update(connection, """
                            INSERT INTO strategy_plan_sources(plan_id,source_snapshot_path,source_sha256,recorded_at) VALUES(?,?,?,?)
                            ON CONFLICT(plan_id,source_snapshot_path) DO UPDATE SET source_sha256=excluded.source_sha256,recorded_at=excluded.recorded_at
                            """, planId, snapshot.toString(), expectedSourceSha256, now);
                    advanceAlias(connection, planId, plan, now);
                    StoredOfficialPlan stored = readById(connection, planId, created);
                    execute(connection, "COMMIT");
                    return stored;
                } catch (Exception e) {
                    try { execute(connection, "ROLLBACK"); } catch (SQLException rollback) { e.addSuppressed(rollback); }
                    throw e;
                }
            }
        } catch (PlanStoreException e) { throw e; }
        catch (Exception e) { throw new PlanStoreException("cannot import official plan into " + path, e); }
    }

    public Optional<StoredOfficialPlan> latest(String strategy) {
        requireStrategy(strategy);
        if (!Files.isRegularFile(path)) return Optional.empty();
        try (Connection connection = reader()) {
            requireSchema(connection);
            verifyCurrentUniqueness(connection);
            return latest(connection, strategy);
        } catch (PlanStoreException e) { throw e; }
        catch (Exception e) { throw new PlanStoreException("cannot read official plan authority " + path, e); }
    }

    /** Same plan-root scope as Python's latest_official_weekly; retain the stored recipe strategy identity. */
    public Optional<StoredOfficialPlan> latestOfficialWeekly() {
        if (!Files.isRegularFile(path)) return Optional.empty();
        try (Connection connection = reader()) {
            requireSchema(connection);verifyCurrentUniqueness(connection);
            String id,strategy,execution,signal;
            try (var statement=connection.prepareStatement("SELECT plan_id,strategy,execution_date,signal_date FROM strategy_plans WHERE plan_kind=? AND is_current=1 ORDER BY execution_date DESC,signal_date DESC,plan_id LIMIT 2")) {
                bind(statement,KIND);
                try(var rows=statement.executeQuery()) {
                    if(!rows.next()) {
                        if(exists(connection,"SELECT 1 FROM strategy_plan_aliases WHERE plan_kind=? AND alias='latest'",KIND))throw new PlanStoreException("official alias has no current authority");
                        return Optional.empty();
                    }
                    id=rows.getString(1);strategy=rows.getString(2);execution=rows.getString(3);signal=rows.getString(4);
                    if(rows.next()&&execution.equals(rows.getString(3))&&signal.equals(rows.getString(4)))
                        throw new PlanStoreException("ambiguous official strategies at the same plan-root frontier");
                }
            }
            var selected=latest(connection,strategy);
            if(selected.isEmpty()||!selected.get().planId().equals(id))throw new PlanStoreException("plan-root latest authority alias is missing or stale");
            return selected;
        } catch(PlanStoreException e){throw e;}
        catch(Exception e){throw new PlanStoreException("cannot read latest official plan authority "+path,e);}
    }

    /** Legacy v3 rows can be inspected, but cannot pass this executable evidence gate without an explicit JSON import. */
    public StoredOfficialPlan requireLatest(String strategy, LocalDate expectedSignalDate, String expectedScoreRunId) {
        StoredOfficialPlan stored = latest(strategy).orElseThrow(() -> new PlanStoreException("authoritative official weekly plan is missing: " + path));
        if (!stored.semanticHashVerified() || stored.provenance().isEmpty())
            throw new PlanStoreException("official plan lacks a verified Java import and frozen score/recipe binding");
        if (!stored.plan().signalDate().equals(expectedSignalDate))
            throw new PlanStoreException("official plan signal date mismatch: " + stored.plan().signalDate() + " != " + expectedSignalDate);
        if (!stored.provenance().orElseThrow().scoreRunId().equals(expectedScoreRunId))
            throw new PlanStoreException("official plan producer run does not match the paired score publication");
        return stored;
    }

    /** Stronger acceptance when the caller has resolved both immutable score refs and its recipe. */
    public StoredOfficialPlan requireLatest(String strategy, LocalDate expectedSignalDate, PlanInputProvenance expected) {
        if (expected == null) throw new PlanStoreException("paired score and recipe evidence is required");
        StoredOfficialPlan stored = requireLatest(strategy, expectedSignalDate, expected.scoreRunId());
        if (!stored.provenance().orElseThrow().equals(expected))
            throw new PlanStoreException("official plan frozen scores or recipe differ from the publication being accepted");
        return stored;
    }

    public Inspection inspect(String strategy) {
        requireStrategy(strategy);
        if (!Files.isRegularFile(path)) return new Inspection(path, 0, "missing", 0, Optional.empty());
        try (Connection connection = reader()) {
            requireSchema(connection);
            String integrity = scalar(connection, "PRAGMA integrity_check");
            if (!"ok".equals(integrity)) throw new PlanStoreException("official plan integrity check failed: " + integrity);
            try (var foreignKeys = connection.createStatement(); var rows = foreignKeys.executeQuery("PRAGMA foreign_key_check")) {
                if (rows.next()) throw new PlanStoreException("official plan has broken foreign-key references");
            }
            verifyCurrentUniqueness(connection);
            try (PreparedStatement statement = connection.prepareStatement("SELECT plan_id FROM strategy_plans WHERE strategy=? AND plan_kind=? AND is_current=1")) {
                bind(statement, strategy, KIND);
                try (ResultSet rows = statement.executeQuery()) { while (rows.next()) readById(connection, rows.getString(1), false); }
            }
            int count;
            try (PreparedStatement statement = connection.prepareStatement("SELECT COUNT(*) FROM strategy_plans WHERE strategy=? AND plan_kind=? AND is_current=1")) {
                bind(statement, strategy, KIND);
                try (ResultSet rows = statement.executeQuery()) { rows.next(); count = rows.getInt(1); }
            }
            return new Inspection(path, SCHEMA_VERSION, integrity, count, latest(connection, strategy));
        } catch (PlanStoreException e) { throw e; }
        catch (Exception e) { throw new PlanStoreException("cannot inspect official plan authority " + path, e); }
    }

    public record Inspection(Path databasePath, int schemaVersion, String integrityCheck, int currentPlans,
            Optional<StoredOfficialPlan> latest) { }

    private Optional<StoredOfficialPlan> latest(Connection connection, String strategy) throws SQLException {
        String id = null;
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT p.plan_id,p.is_current,p.strategy,p.plan_kind FROM strategy_plan_aliases a
                JOIN strategy_plans p ON p.plan_id=a.plan_id
                WHERE a.strategy=? AND a.plan_kind=? AND a.alias='latest'
                """)) {
            bind(statement, strategy, KIND);
            try (ResultSet rows = statement.executeQuery()) {
                if (rows.next()) {
                    if (rows.getInt("is_current") != 1 || !strategy.equals(rows.getString("strategy")) || !KIND.equals(rows.getString("plan_kind")))
                        throw new PlanStoreException("official latest alias does not reference a current plan with matching authority identity");
                    id = rows.getString("plan_id");
                }
            }
        }
        // A missing alias on a populated authority is corruption, not an invitation to infer latest from positions.
        if (id == null && exists(connection, "SELECT 1 FROM strategy_plans WHERE strategy=? AND plan_kind=? AND is_current=1", strategy, KIND))
            throw new PlanStoreException("official authority has current plans but no latest alias");
        if (id != null) {
            try (PreparedStatement statement = connection.prepareStatement("SELECT plan_id FROM strategy_plans WHERE strategy=? AND plan_kind=? AND is_current=1 ORDER BY execution_date DESC,signal_date DESC LIMIT 1")) {
                bind(statement, strategy, KIND);
                try (ResultSet rows = statement.executeQuery()) {
                    if (!rows.next() || !id.equals(rows.getString(1))) throw new PlanStoreException("official latest alias is stale or regressed");
                }
            }
        }
        return id == null ? Optional.empty() : Optional.of(readById(connection, id, false));
    }

    private Optional<StoredOfficialPlan> findCurrent(Connection connection, OfficialWeeklyPlan plan) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT plan_id FROM strategy_plans WHERE strategy=? AND plan_kind=? AND signal_date=? AND execution_date=? AND is_current=1")) {
            bind(statement, plan.strategy(), KIND, plan.signalDate().toString(), plan.executionDate().toString());
            try (ResultSet rows = statement.executeQuery()) { return rows.next() ? Optional.of(readById(connection, rows.getString(1), false)) : Optional.empty(); }
        }
    }

    private StoredOfficialPlan readById(Connection connection, String id, boolean created) throws SQLException {
        ObjectNode payload = CanonicalPlanJson.JSON.createObjectNode();
        String digest, publishedAt;
        try (PreparedStatement statement = connection.prepareStatement("SELECT * FROM strategy_plans WHERE plan_id=?")) {
            bind(statement, id);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) throw new PlanStoreException("missing official plan " + id);
                digest = rows.getString("content_sha256"); publishedAt = rows.getString("published_at");
                PlanInputProvenance.requireSha256(digest, "official semantic content");
                if (!id.equals("msp_" + digest.substring(0, 24))) throw new PlanStoreException("official plan id differs from its semantic hash");
                for (String name : List.of("schema_version", "status", "strategy", "signal_date", "execution_date",
                        "valuation_date", "rebalance_trigger", "portfolio_state", "state_trigger_reason", "diagnostic_payload_path")) {
                    String value = rows.getString(name);
                    if (value == null) payload.putNull(name); else payload.put(name, value);
                }
                payload.put("kind", rows.getString("plan_kind"));
                payload.put("available", strictBoolean(rows.getObject("available"), "available"));
                payload.put("diagnostic_top_candidates_excluded", strictBoolean(rows.getObject("diagnostic_top_candidates_excluded"), "diagnostic_top_candidates_excluded"));
            }
        }
        ArrayNode targets = payload.putArray("targets");
        try (PreparedStatement statement = connection.prepareStatement("SELECT * FROM strategy_plan_targets WHERE plan_id=? ORDER BY ordinal")) {
            bind(statement, id);
            try (ResultSet rows = statement.executeQuery()) {
                int expectedOrdinal = 0;
                while (rows.next()) {
                    if (rows.getInt("ordinal") != expectedOrdinal++) throw new PlanStoreException("official target ordinals are discontinuous");
                    ObjectNode target = targets.addObject();
                    for (String name : CanonicalPlanJson.TARGET_COLUMNS) {
                        Object value = rows.getObject(name);
                        if (value == null) target.putNull(name);
                        else if (name.endsWith("_flag")) target.put(name, strictBoolean(value, name));
                        else if (value instanceof Number number) target.put(name, number.doubleValue());
                        else target.put(name, value.toString());
                    }
                    target.set("signal_date", payload.get("signal_date"));
                    target.set("execution_date", payload.get("execution_date"));
                    target.set("valuation_date", payload.get("valuation_date"));
                    ArrayNode components = target.putArray("component_set");
                    try (PreparedStatement componentStatement = connection.prepareStatement("SELECT ordinal,symbol FROM strategy_plan_target_components WHERE plan_id=? AND target_ordinal=? ORDER BY ordinal")) {
                        bind(componentStatement, id, rows.getInt("ordinal"));
                        try (ResultSet componentRows = componentStatement.executeQuery()) {
                            int componentOrdinal = 0;
                            while (componentRows.next()) {
                                if (componentRows.getInt(1) != componentOrdinal++) throw new PlanStoreException("official component ordinals are discontinuous");
                                components.add(componentRows.getString(2));
                            }
                        }
                    }
                }
            }
        }
        OfficialWeeklyPlan typedPlan = CanonicalPlanJson.normalize(payload, true);
        if (!hasTable(connection, "strategy_plan_import_receipts"))
            return legacy(id, digest, publishedAt, typedPlan, created);
        try (PreparedStatement statement = connection.prepareStatement("SELECT * FROM strategy_plan_import_receipts WHERE plan_id=?")) {
            bind(statement, id);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) return legacy(id, digest, publishedAt, typedPlan, created);
                OfficialWeeklyPlan imported = CanonicalPlanJson.read(rows.getString("canonical_payload").getBytes(StandardCharsets.UTF_8));
                if (!digest.equals(imported.semanticSha256()) || !storageProjection(imported).equals(storageProjection(typedPlan)))
                    throw new PlanStoreException("official canonical import hash or typed authority rows have changed");
                PlanInputProvenance provenance = new PlanInputProvenance(rows.getString("score_run_id"), rows.getString("score_panel_sha256"),
                        rows.getString("score_coverage_sha256"), rows.getString("recipe_sha256"), LocalDate.parse(rows.getString("max_complete_date")), rows.getString("recipe_code_revision"));
                if (!provenance.maxCompleteDate().equals(imported.signalDate())) throw new PlanStoreException("official input evidence violates signal-date PIT");
                String sourcePath = rows.getString("source_snapshot_path"), sourceSha = rows.getString("source_sha256");
                PlanInputProvenance.requireSha256(sourceSha, "import source");
                byte[] sourceBytes = readSnapshot(Path.of(sourcePath));
                if (!sourceSha.equals(CanonicalPlanJson.sha256(sourceBytes))) throw new PlanStoreException("official import source is missing or changed");
                OfficialWeeklyPlan sourcePlan = CanonicalPlanJson.read(sourceBytes);
                if (!sourcePlan.canonicalJson().equals(imported.canonicalJson())) throw new PlanStoreException("official import receipt disagrees with its source snapshot");
                if (!exists(connection, "SELECT 1 FROM strategy_plan_sources WHERE plan_id=? AND source_snapshot_path=? AND source_sha256=?", id, sourcePath, sourceSha))
                    throw new PlanStoreException("official import receipt lacks the authoritative source hash record");
                return new StoredOfficialPlan(id, digest, publishedAt, imported, created, Optional.of(provenance), sourcePath, sourceSha, true);
            }
        }
    }

    private static StoredOfficialPlan legacy(String id, String digest, String publishedAt, OfficialWeeklyPlan plan, boolean created) {
        // Python v3 does not persist target-level valuation dates; its original semantic hash cannot always be reconstructed.
        return new StoredOfficialPlan(id, digest, publishedAt, plan, created, Optional.empty(), "", "", false);
    }

    private static ObjectNode storageProjection(OfficialWeeklyPlan plan) {
        ObjectNode projected = plan.payload();
        projected.remove("diagnostic_payload_path");
        for (JsonNode target : projected.path("targets")) {
            ((ObjectNode) target).set("valuation_date", projected.get("valuation_date"));
            // SQLite REAL loses the sign of zero; the immutable canonical payload retains it for semantic hashing.
            for (String name : CanonicalPlanJson.TARGET_COLUMNS)
                if (target.path(name).isNumber() && target.path(name).doubleValue() == 0) ((ObjectNode) target).put(name, 0.0);
        }
        return projected;
    }

    private static void insertPlan(Connection connection, String id, String digest, String now, OfficialWeeklyPlan plan) throws SQLException {
        ObjectNode data = plan.payload();
        update(connection, """
                INSERT INTO strategy_plans(plan_id,content_sha256,schema_version,plan_kind,available,status,strategy,
                    signal_date,execution_date,valuation_date,rebalance_trigger,portfolio_state,state_trigger_reason,
                    diagnostic_top_candidates_excluded,diagnostic_payload_path,published_at,is_current)
                VALUES(?,?,'v1',?,1,?,?,?,?,?,?,?, ?,1,?,?,1)
                """, id, digest, KIND, data.path("status").asText(), plan.strategy(), plan.signalDate().toString(),
                plan.executionDate().toString(), plan.valuationDate() == null ? null : plan.valuationDate().toString(),
                data.path("rebalance_trigger").asText(), data.path("portfolio_state").asText(), data.path("state_trigger_reason").asText(),
                data.path("diagnostic_payload_path").asText(), now);
        String columnList = String.join(",", CanonicalPlanJson.TARGET_COLUMNS);
        String placeholders = String.join(",", java.util.Collections.nCopies(CanonicalPlanJson.TARGET_COLUMNS.size() + 2, "?"));
        int ordinal = 0;
        for (JsonNode target : data.path("targets")) {
            List<Object> values = new ArrayList<>(); values.add(id); values.add(ordinal);
            for (String name : CanonicalPlanJson.TARGET_COLUMNS) {
                JsonNode value = target.path(name);
                values.add(value.isNull() ? null : value.isBoolean() ? (value.booleanValue() ? 1 : 0)
                        : value.isNumber() ? value.doubleValue() : value.asText());
            }
            update(connection, "INSERT INTO strategy_plan_targets(plan_id,ordinal," + columnList + ") VALUES(" + placeholders + ")", values.toArray());
            int componentOrdinal = 0;
            for (JsonNode component : target.path("component_set"))
                update(connection, "INSERT INTO strategy_plan_target_components(plan_id,target_ordinal,ordinal,symbol) VALUES(?,?,?,?)",
                        id, ordinal, componentOrdinal++, component.asText());
            ordinal++;
        }
    }

    private static void updateReceipt(Connection connection, String id, OfficialWeeklyPlan plan, Path source, String sourceSha,
            PlanInputProvenance provenance, String now) throws SQLException {
        update(connection, """
                INSERT INTO strategy_plan_import_receipts(plan_id,canonical_payload,source_snapshot_path,source_sha256,
                    score_run_id,score_panel_sha256,score_coverage_sha256,recipe_sha256,max_complete_date,recipe_code_revision,recorded_at)
                VALUES(?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT(plan_id) DO UPDATE SET
                    canonical_payload=excluded.canonical_payload,source_snapshot_path=excluded.source_snapshot_path,
                    source_sha256=excluded.source_sha256,recorded_at=excluded.recorded_at
                """, id, plan.canonicalJson(), source.toString(), sourceSha, provenance.scoreRunId(), provenance.scorePanelSha256(),
                provenance.scoreCoverageSha256(), provenance.recipeSha256(), provenance.maxCompleteDate().toString(), provenance.recipeCodeRevision(), now);
        update(connection, """
                INSERT INTO strategy_plan_sources(plan_id,source_snapshot_path,source_sha256,recorded_at) VALUES(?,?,?,?)
                ON CONFLICT(plan_id,source_snapshot_path) DO UPDATE SET source_sha256=excluded.source_sha256,recorded_at=excluded.recorded_at
                """, id, source.toString(), sourceSha, now);
    }

    private static void advanceAlias(Connection connection, String id, OfficialWeeklyPlan plan, String now) throws SQLException {
        boolean advance = true;
        try (PreparedStatement statement = connection.prepareStatement("SELECT p.execution_date,p.signal_date FROM strategy_plan_aliases a JOIN strategy_plans p ON p.plan_id=a.plan_id WHERE a.strategy=? AND a.plan_kind=? AND a.alias='latest'")) {
            bind(statement, plan.strategy(), KIND);
            try (ResultSet rows = statement.executeQuery()) {
                if (rows.next()) {
                    int executionOrder = plan.executionDate().toString().compareTo(rows.getString(1));
                    advance = executionOrder > 0 || (executionOrder == 0 && plan.signalDate().toString().compareTo(rows.getString(2)) >= 0);
                }
            }
        }
        if (advance) update(connection, """
                INSERT INTO strategy_plan_aliases(strategy,plan_kind,alias,plan_id,updated_at) VALUES(?,?,'latest',?,?)
                ON CONFLICT(strategy,plan_kind,alias) DO UPDATE SET plan_id=excluded.plan_id,updated_at=excluded.updated_at
                """, plan.strategy(), KIND, id, now);
    }

    private Connection writer() throws SQLException {
        Connection connection = DriverManager.getConnection("jdbc:sqlite:" + path);
        try {
            execute(connection, "PRAGMA foreign_keys=ON"); execute(connection, "PRAGMA busy_timeout=10000");
            execute(connection, "PRAGMA journal_mode=WAL"); execute(connection, "PRAGMA synchronous=FULL");
            return connection;
        } catch (SQLException e) { connection.close(); throw e; }
    }
    private Connection reader() throws SQLException {
        Connection connection = DriverManager.getConnection("jdbc:sqlite:" + path.toUri().toASCIIString() + "?mode=ro");
        try {
            execute(connection, "PRAGMA foreign_keys=ON"); execute(connection, "PRAGMA busy_timeout=2000");
            execute(connection, "PRAGMA query_only=ON"); execute(connection, "BEGIN"); return connection;
        } catch (SQLException e) { connection.close(); throw e; }
    }
    private static void requireSchema(Connection connection) throws SQLException {
        if (!Integer.toString(SCHEMA_VERSION).equals(scalar(connection, "PRAGMA user_version")))
            throw new PlanStoreException("official authority requires SQLite schema version 3; readers never migrate storage");
        for (String name : List.of("strategy_plans", "strategy_plan_targets", "strategy_plan_target_components", "strategy_plan_aliases", "strategy_plan_sources"))
            if (!hasTable(connection, name)) throw new PlanStoreException("official authority is missing table " + name);
    }
    private static void verifyCurrentUniqueness(Connection connection) throws SQLException {
        try (var statement = connection.createStatement(); var rows = statement.executeQuery("SELECT 1 FROM strategy_plans WHERE is_current=1 GROUP BY strategy,plan_kind,signal_date,execution_date HAVING COUNT(*)>1 LIMIT 1")) {
            if (rows.next()) throw new PlanStoreException("official authority contains multiple current content revisions for one business identity");
        }
    }
    private static boolean hasTable(Connection connection, String name) throws SQLException {
        return exists(connection, "SELECT 1 FROM sqlite_master WHERE type='table' AND name=?", name);
    }
    private static boolean exists(Connection connection, String sql, Object... values) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, values); try (ResultSet rows = statement.executeQuery()) { return rows.next(); }
        }
    }
    private static void update(Connection connection, String sql, Object... values) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) { bind(statement, values); statement.executeUpdate(); }
    }
    private static void bind(PreparedStatement statement, Object... values) throws SQLException {
        for (int i = 0; i < values.length; i++) {
            if (values[i] == null) statement.setNull(i + 1, Types.NULL); else statement.setObject(i + 1, values[i]);
        }
    }
    private static void execute(Connection connection, String sql) throws SQLException {
        try (var statement = connection.createStatement()) { statement.execute(sql); }
    }
    private static void executeScript(Connection connection, String sql) throws SQLException {
        for (String statement : sql.split(";")) if (!statement.isBlank()) execute(connection, statement);
    }
    private static String scalar(Connection connection, String sql) throws SQLException {
        try (var statement = connection.createStatement(); var rows = statement.executeQuery(sql)) {
            if (!rows.next()) throw new PlanStoreException("official authority inspection returned no value");
            return rows.getString(1);
        }
    }
    private static boolean strictBoolean(Object value, String name) {
        if (!(value instanceof Number number) || (number.intValue() != 0 && number.intValue() != 1) || number.doubleValue() != number.intValue())
            throw new PlanStoreException("invalid authoritative boolean " + name);
        return ((Number) value).intValue() == 1;
    }
    private static byte[] readSnapshot(Path source) {
        try {
            if (!Files.isRegularFile(source) || Files.size(source) > MAX_SNAPSHOT_BYTES)
                throw new PlanStoreException("official source snapshot is missing or exceeds eight MiB: " + source);
            byte[] bytes = Files.readAllBytes(source);
            if (bytes.length > MAX_SNAPSHOT_BYTES) throw new PlanStoreException("official source snapshot exceeds eight MiB: " + source);
            return bytes;
        } catch (IOException e) { throw new PlanStoreException("cannot read official source snapshot " + source, e); }
    }
    private Path freezeSource(byte[] bytes, String digest) throws IOException {
        Path directory = path.getParent().resolve("official-plan-sources");
        Files.createDirectories(directory);
        Path frozen = directory.resolve(digest + ".json");
        try { Files.write(frozen, bytes, java.nio.file.StandardOpenOption.CREATE_NEW); }
        catch (java.nio.file.FileAlreadyExistsException e) {
            if (!digest.equals(CanonicalPlanJson.sha256(readSnapshot(frozen))))
                throw new PlanStoreException("immutable official source snapshot has changed: " + frozen, e);
        }
        return frozen;
    }
    private static void requireStrategy(String strategy) {
        if (strategy == null || strategy.isBlank()) throw new PlanStoreException("strategy is required");
    }
}
