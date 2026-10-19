package com.zoutrankil.data.strategy.publication;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zoutrankil.data.strategy.publication.ScoreArtifactPublisher.Ref;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.DriverManager;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.*;
import org.apache.parquet.example.data.Group;
import org.apache.parquet.hadoop.ParquetFileReader;
import org.apache.parquet.io.LocalInputFile;
import org.apache.parquet.io.ColumnIOFactory;
import org.apache.parquet.example.data.simple.convert.GroupRecordConverter;

/** The three frozen market roles required by ETF_ONLY_TOP2, published without invoking Python. */
public final class NativeMarketInputPackagePublisher {
    public static final String PRODUCT_ID = "market.etf.rotation.input_package";
    private static final String ALIAS = "data.product." + PRODUCT_ID + "@latest";
    private static final String PRODUCER = "java.data.product.build";
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules()
            .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private static final DateTimeFormatter DATE = DateTimeFormatter.BASIC_ISO_DATE;
    private static final List<String> CALENDAR_COLUMNS = List.of("exchange", "cal_date", "is_open", "pretrade_date");
    private static final List<String> MARKET_COLUMNS = List.of("ts_code", "timestamp", "open", "high", "low", "close", "amount");
    private static final List<String> CONTEXT_COLUMNS = List.of("trade_date", "month", "hs300_ret_mtd", "zz500_ret_mtd",
            "all_a_ret_mtd", "small_large_ret_mtd", "growth_value_ret_mtd", "avg_up_down_ratio_5d", "data_quality_flag", "updated_at");
    private final Path root, database;
    private final ScoreArtifactPublisher artifacts;

    public NativeMarketInputPackagePublisher(Path artifactRoot) {
        root = Objects.requireNonNull(artifactRoot).toAbsolutePath().normalize();
        database = root.resolve("_catalog/resource_catalog.sqlite3");
        artifacts = new ScoreArtifactPublisher(root);
    }

    public record Publication(String packageId, String inputDigest, LocalDate maxCompleteDate,
            Ref manifestRef, List<Ref> components, Ref sourceProofRef) {
        public Publication { components = List.copyOf(components); }
    }

    public Publication publish(String runId, LocalDate start, LocalDate end,
            List<Map<String,Object>> calendar, List<Map<String,Object>> etfDaily,
            List<Map<String,Object>> marketContext, List<Map<String,Object>> scorePanel,
            ScoreArtifactPublisher.Publication scores, Map<String,Object> sourceProof) throws Exception {
        return publish(runId,start,end,calendar,etfDaily,marketContext,scorePanel,scores,sourceProof,()->{});
    }
    public Publication publish(String runId, LocalDate start, LocalDate end,
            List<Map<String,Object>> calendar, List<Map<String,Object>> etfDaily,
            List<Map<String,Object>> marketContext, List<Map<String,Object>> scorePanel,
            ScoreArtifactPublisher.Publication scores, Map<String,Object> sourceProof,ScoreArtifactPublisher.PublicationGate gate) throws Exception {
        if (runId == null || !runId.matches("[A-Za-z0-9_.-]{1,128}")) throw invalid("bounded product run ID required");
        if (start == null || end == null || end.isBefore(start) || end.toEpochDay() - start.toEpochDay() > 730)
            throw invalid("bounded product date range required");
        if (scores == null || !runId.equals(scores.producerRunId()) || !end.equals(scores.resolvedEndDate()))
            throw invalid("product must bind the same exact score producer run and complete date");
        verifyPublication(scores);
        JsonNode scoreProof = JSON.readTree(readJson(artifacts.provenancePath(scores)));
        JsonNode providedProof = JSON.valueToTree(sourceProof);
        if (!providedProof.isObject()) throw invalid("frozen product source proof must be an object");
        for (var fields = providedProof.fields(); fields.hasNext();) {
            var field = fields.next();
            if (!Arrays.equals(jsonBytes(field.getValue()), jsonBytes(scoreProof.path(field.getKey()))))
                throw invalid("market package source proof differs from the score calculation: " + field.getKey());
        }
        verifySource(sourceProof, "calendar", calendar);
        verifySource(sourceProof, "etf_daily", etfDaily);
        verifySource(sourceProof, "market_context", marketContext);
        List<Map<String,Object>> calendarRows = calendar(calendar, start, end);
        SortedSet<LocalDate> openDates = new TreeSet<>();
        for (var row : calendarRows) {
            LocalDate day = day(row.get("cal_date"));
            if (!day.isAfter(end) && ((Number) row.get("is_open")).intValue() == 1) openDates.add(day);
        }
        if (openDates.isEmpty() || !openDates.last().equals(end)) throw invalid("product target is not an open calendar date");
        Set<String> scoreKeys = scoreKeys(scorePanel, scores.panelRef());
        SortedSet<LocalDate> scoreDates = new TreeSet<>();
        SortedSet<String> symbols = new TreeSet<>();
        for (var row : scorePanel) { scoreDates.add(day(row.get("trade_date"))); symbols.add(symbol(row.get("ts_code"))); }
        if (!scoreDates.equals(openDates)) throw invalid("frozen score grid differs from the product calendar");
        List<Map<String,Object>> marketRows = market(etfDaily, start, end, symbols, scoreKeys);
        List<Map<String,Object>> contextRows = context(marketContext, start, end, openDates);
        String prefix = "data/product_packages/market/etf/rotation/input_package/java/" + runId + "/";
        Ref calendarRef = artifacts.frame(prefix, "calendar", calendarRows, "cal_date");
        Ref marketRef = artifacts.frame(prefix, "etf_market", marketRows, "timestamp");
        Ref contextRef = artifacts.frame(prefix, "market_context", contextRows, "trade_date");
        byte[] proofBytes = jsonBytes(sourceProof);
        Ref proofRef = artifacts.jsonArtifact(prefix, "source_proof", proofBytes, start, end, 0, digest("java.main_strategy.sources.v1"));
        String createdAt = Instant.now().toString();
        var components = List.of(component("calendar", "market.calendar.daily", calendarRef, end, createdAt, CALENDAR_COLUMNS),
                component("etf_market", "market.etf.daily", marketRef, end, createdAt, MARKET_COLUMNS),
                component("market_context", "market.regime.daily_context", contextRef, end, createdAt, CONTEXT_COLUMNS));
        var parameters = new LinkedHashMap<String,Object>();
        parameters.put("start_date", start.format(DATE)); parameters.put("max_rows", 5_000_000);
        parameters.put("roles", List.of("calendar", "etf_market", "market_context"));
        parameters.put("symbols", String.join(",", symbols)); parameters.put("materialization_mode", "frozen");
        parameters.put("score_run_id", scores.producerRunId()); parameters.put("score_panel_sha256", scores.panelRef().contentHash());
        parameters.put("score_coverage_sha256", scores.coverageRef().contentHash()); parameters.put("recipe_sha256", scores.recipeSha256());
        parameters.put("source_proof_sha256", proofRef.contentHash()); parameters.put("source_proof", resource(proofRef));
        var request = Map.of("product_id", PRODUCT_ID, "as_of", end.format(DATE), "params", parameters);
        var identities = new ArrayList<Map<String,Object>>();
        for (var component : components) {
            @SuppressWarnings("unchecked") var snapshot = (Map<String,Object>) component.get("snapshot");
            identities.add(Map.of("dataset_id", snapshot.get("dataset_id"), "input_digest", snapshot.get("input_digest"), "artifact", snapshot.get("artifact")));
        }
        String inputDigest = digest(Map.of("request", request, "components", identities));
        String schemaDigest = digest(Map.of("market.calendar.daily", calendarRef.schemaDigest(), "market.etf.daily", marketRef.schemaDigest(), "market.regime.daily_context", contextRef.schemaDigest()));
        long count = Math.addExact(Math.addExact(calendarRef.rowCount(), marketRef.rowCount()), contextRef.rowCount());
        var manifest = new LinkedHashMap<String,Object>();
        manifest.put("package_id", "pkg_" + inputDigest.substring(0, 20)); manifest.put("product_id", PRODUCT_ID);
        manifest.put("product_version", "v1"); manifest.put("as_of", end.format(DATE)); manifest.put("created_at", createdAt);
        manifest.put("schema_digest", schemaDigest); manifest.put("input_digest", inputDigest); manifest.put("resolved_parameters", parameters);
        manifest.put("max_complete_date", end.format(DATE)); manifest.put("row_count", count); manifest.put("components", components);
        Ref manifestRef = artifacts.jsonArtifact(prefix, "manifest", jsonBytes(manifest), start, end, count, schemaDigest);
        Ref selectedManifest = manifestRef;
        try (var connection = artifacts.openCatalogWriter()) {
            try {
                initializeReceipts(connection);
                checkExistingFrontier(connection, end);
                ScoreArtifactPublisher.registerMetadata(connection, "calendar", runId, calendarRef, "quant_platform.data.market.calendar.daily", PRODUCER, List.of(proofRef));
                ScoreArtifactPublisher.registerMetadata(connection, "etf_market", runId, marketRef, "quant_platform.data.market.etf.daily", PRODUCER, List.of(proofRef));
                ScoreArtifactPublisher.registerMetadata(connection, "market_context", runId, contextRef, "quant_platform.data.market.regime.daily_context", PRODUCER, List.of(proofRef));
                ScoreArtifactPublisher.registerMetadata(connection, "source_proof", runId, proofRef, "quant_platform.data.main_strategy.sources", PRODUCER, List.of());
                var previous = receipt(connection, runId);
                if (previous != null) {
                    if (!inputDigest.equals(previous.inputDigest()) || !proofRef.contentHash().equals(previous.sourceProofSha256()))
                        throw invalid("immutable native package producer run collision");
                    selectedManifest = metadataRef(connection, previous.manifestUri(), previous.manifestVersion(), previous.manifestSha256());
                    var stored = readPublication(connection, selectedManifest, previous.inputDigest(), scores);
                    if (!stored.components().equals(List.of(calendarRef, marketRef, contextRef)) || !stored.sourceProofRef().equals(proofRef))
                        throw invalid("immutable native package retry differs from its original frozen inputs");
                } else {
                    ScoreArtifactPublisher.registerMetadata(connection, "data_product_package", inputDigest, manifestRef, "quant_platform.data." + PRODUCT_ID,
                            PRODUCER, List.of(calendarRef, marketRef, contextRef, proofRef, scores.panelRef(), scores.coverageRef()));
                    try (var statement = connection.prepareStatement("INSERT INTO java_market_package_publications VALUES(?,?,?,?,?,?)")) {
                        statement.setString(1, runId); statement.setString(2, inputDigest); statement.setString(3, proofRef.contentHash());
                        statement.setString(4, manifestRef.uri()); statement.setString(5, manifestRef.version()); statement.setString(6, manifestRef.contentHash());
                        statement.executeUpdate();
                    }
                }
                verifySource(sourceProof, "calendar", calendar);
                verifySource(sourceProof, "etf_daily", etfDaily);
                verifySource(sourceProof, "market_context", marketContext);
                // Recheck the pair on this same catalog snapshot, before the package alias commits.
                requireScoreAliases(connection, scores);
                ScoreArtifactPublisher.registerAlias(connection, ALIAS, selectedManifest, PRODUCER);
                gate.check();connection.commit();
            } catch (Exception failure) { connection.rollback(); throw failure; }
        }
        Publication observed = requireLatest(scores);
        if (!observed.manifestRef().contentHash().equals(selectedManifest.contentHash())) throw invalid("package alias changed immediately after publication");
        return observed;
    }

    /** Read-only: a package cannot borrow readiness or source identity from another score run. */
    public Publication requireLatest(ScoreArtifactPublisher.Publication scores) throws Exception {
        return load(scores, true);
    }

    /** Read the original complete bundle without following either current score or package aliases. */
    public Publication requireByRun(ScoreArtifactPublisher.Publication scores) throws Exception {
        return load(scores, false);
    }

    private Publication load(ScoreArtifactPublisher.Publication scores, boolean latest) throws Exception {
        if (scores == null || !Files.isRegularFile(database)) throw invalid("native frozen market package is missing");
        if (!latest && !artifacts.byRun(scores.producerRunId()).equals(scores)) throw invalid("historical package requires the exact immutable score publication");
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database.toUri() + "?mode=ro")) {
            try (var statement = connection.createStatement()) { statement.execute("PRAGMA query_only=ON"); statement.execute("PRAGMA busy_timeout=2000"); }
            connection.setAutoCommit(false);
            if (!latest) {
                var recorded = receipt(connection, scores.producerRunId());
                if (recorded != null) {
                    var ref = metadataRef(connection, recorded.manifestUri(), recorded.manifestVersion(), recorded.manifestSha256());
                    var publication = readPublication(connection, ref, recorded.inputDigest(), scores);
                    if (!recorded.sourceProofSha256().equals(publication.sourceProofRef().contentHash())) throw invalid("native package receipt source proof differs");
                    return publication;
                }
                // Compatibility for catalogs created before the unique run receipt. An alias is never consulted.
                String prefix = "quant://artifact/data/product_packages/market/etf/rotation/input_package/java/" + scores.producerRunId() + "/";
                Publication selected = null; int count = 0;
                try (var statement = connection.prepareStatement("SELECT uri,version,content_hash,schema_digest,row_count,data_start_date,data_end_date,run_id FROM resource_metadata WHERE substr(uri,1,?)=? AND role='data_product_package' AND producer_capability=? ORDER BY uri LIMIT 33")) {
                    statement.setInt(1, prefix.length()); statement.setString(2, prefix); statement.setString(3, PRODUCER);
                    try (var rows = statement.executeQuery()) {
                        while (rows.next()) {
                            if (++count > 32) throw invalid("historical native package audit budget exceeded");
                            var candidate = readPublication(connection, ref(rows), rows.getString(8), scores);
                            if (selected != null && (!selected.packageId().equals(candidate.packageId()) || !selected.inputDigest().equals(candidate.inputDigest())
                                    || !selected.maxCompleteDate().equals(candidate.maxCompleteDate()) || !selected.components().equals(candidate.components())
                                    || !selected.sourceProofRef().equals(candidate.sourceProofRef()))) throw invalid("ambiguous historical native package inputs");
                            if (selected == null) selected = candidate;
                        }
                    }
                }
                if (selected == null) throw invalid("native three-role market package has not been published for the original score run");
                return selected;
            }
            requireScoreAliases(connection, scores);
            Ref manifestRef;
            try (var statement = connection.prepareStatement("SELECT m.uri,m.version,m.content_hash,m.schema_digest,m.row_count,m.data_start_date,m.data_end_date,m.run_id FROM resource_aliases a JOIN resource_metadata m ON m.uri=a.target_uri AND m.version=a.target_version AND m.content_hash=a.target_content_hash WHERE a.alias=? AND m.role='data_product_package' AND m.producer_capability=?")) {
                statement.setString(1, ALIAS); statement.setString(2, PRODUCER);
                try (var rows = statement.executeQuery()) {
                    if (!rows.next()) throw invalid("native three-role market package has not been published");
                    manifestRef = ref(rows);
                    return readPublication(connection, manifestRef, rows.getString(8), scores);
                }
            }
        }
    }

    private Publication readPublication(java.sql.Connection connection, Ref manifestRef, String inputDigest, ScoreArtifactPublisher.Publication scores) throws Exception {
                    verifyRef(manifestRef);
                    JsonNode document = JSON.readTree(readJson(manifestRef.path()));
                    if (!PRODUCT_ID.equals(document.path("product_id").asText()) || !"v1".equals(document.path("product_version").asText())
                            || !inputDigest.equals(document.path("input_digest").asText())
                            || !manifestRef.schemaDigest().equals(document.path("schema_digest").asText())
                            || manifestRef.rowCount() != document.path("row_count").asLong(-1)) throw invalid("native market manifest differs from its catalog card");
                    LocalDate complete = LocalDate.parse(document.path("max_complete_date").asText(), DATE);
                    JsonNode parameters = document.path("resolved_parameters");
                    if (!complete.equals(scores.resolvedEndDate()) || !complete.equals(manifestRef.end())
                            || !parameters.path("score_run_id").asText().equals(scores.producerRunId())
                            || !parameters.path("score_panel_sha256").asText().equals(scores.panelRef().contentHash())
                            || !parameters.path("score_coverage_sha256").asText().equals(scores.coverageRef().contentHash())
                            || !parameters.path("recipe_sha256").asText().equals(scores.recipeSha256())) throw invalid("market package frozen score/recipe binding changed");
                    var refs = new ArrayList<Ref>(); Set<String> roles = new HashSet<>();
                    for (JsonNode component : document.path("components")) {
                        String role = component.path("role").asText();
                        if (!roles.add(role) || !component.path("required").asBoolean()) throw invalid("invalid package role identity");
                        JsonNode snapshot = component.path("snapshot"), reference = snapshot.path("artifact");
                        String uri = reference.path("uri").asText(); Path path = artifact(uri);
                        JsonNode part = snapshot.path("parts").path(0);
                        Ref ref = new Ref(uri, reference.path("version").asText(), reference.path("content_hash").asText(), snapshot.path("schema_digest").asText(),
                                snapshot.path("row_count").asLong(-1), path, LocalDate.parse(part.path("data_start_date").asText(), DATE), LocalDate.parse(part.path("data_end_date").asText(), DATE));
                        verifyRef(ref);
                        ScoreArtifactPublisher.validateFrame(ref);
                        if (!"frozen".equals(snapshot.path("materialization_mode").asText()) || !"complete".equals(snapshot.path("completeness_status").asText()))
                            throw invalid("package contains an unfrozen or uncertified component");
                        refs.add(ref);
                    }
                    if (!roles.equals(Set.of("calendar", "etf_market", "market_context"))) throw invalid("native market package must contain exactly three roles");
                    if (refs.stream().mapToLong(Ref::rowCount).sum() != manifestRef.rowCount()) throw invalid("native package aggregate row count differs from its components");
                    JsonNode proofResource = parameters.path("source_proof");
                    Ref proof = new Ref(proofResource.path("uri").asText(), proofResource.path("version").asText(), proofResource.path("content_hash").asText(),
                            digest("java.main_strategy.sources.v1"), 0, artifact(proofResource.path("uri").asText()), manifestRef.start(), complete);
                    verifyRef(proof);
                    if (!proof.contentHash().equals(parameters.path("source_proof_sha256").asText()) || !JSON.readTree(readJson(proof.path())).path("sourceComplete").asBoolean())
                        throw invalid("native package lacks a complete immutable source proof");
                    JsonNode scoreProof = JSON.readTree(readJson(artifacts.provenancePath(scores))), packageProof = JSON.readTree(readJson(proof.path()));
                    for (var fields = packageProof.fields(); fields.hasNext();) {
                        var field = fields.next();
                        if (!field.getValue().equals(scoreProof.path(field.getKey()))) throw invalid("native package original source proof differs from its score calculation");
                    }
                    return new Publication(document.path("package_id").asText(), document.path("input_digest").asText(), complete, manifestRef, refs, proof);
    }

    private record RunReceipt(String inputDigest, String sourceProofSha256, String manifestUri, String manifestVersion, String manifestSha256) {}
    private static void initializeReceipts(java.sql.Connection connection) throws Exception {
        try (var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE IF NOT EXISTS java_market_package_publications(score_run_id TEXT PRIMARY KEY,input_digest TEXT NOT NULL,source_proof_sha256 TEXT NOT NULL,manifest_uri TEXT NOT NULL,manifest_version TEXT NOT NULL,manifest_sha256 TEXT NOT NULL)");
        }
    }
    private static RunReceipt receipt(java.sql.Connection connection, String runId) throws Exception {
        try (var query = connection.prepareStatement("SELECT count(*) FROM sqlite_master WHERE type='table' AND name='java_market_package_publications'"); var rows = query.executeQuery()) {
            rows.next(); if (rows.getInt(1) == 0) return null;
        }
        try (var query = connection.prepareStatement("SELECT input_digest,source_proof_sha256,manifest_uri,manifest_version,manifest_sha256 FROM java_market_package_publications WHERE score_run_id=?")) {
            query.setString(1, runId);
            try (var rows = query.executeQuery()) { return rows.next() ? new RunReceipt(rows.getString(1), rows.getString(2), rows.getString(3), rows.getString(4), rows.getString(5)) : null; }
        }
    }
    private Ref metadataRef(java.sql.Connection connection, String uri, String version, String contentHash) throws Exception {
        try (var query = connection.prepareStatement("SELECT uri,version,content_hash,schema_digest,row_count,data_start_date,data_end_date FROM resource_metadata WHERE uri=? AND version=? AND content_hash=? AND role='data_product_package' AND producer_capability=?")) {
            query.setString(1, uri); query.setString(2, version); query.setString(3, contentHash); query.setString(4, PRODUCER);
            try (var rows = query.executeQuery()) { if (!rows.next()) throw invalid("native package receipt metadata is missing or changed"); return ref(rows); }
        }
    }
    private Ref ref(java.sql.ResultSet rows) throws Exception {
        return new Ref(rows.getString(1), rows.getString(2), rows.getString(3), rows.getString(4), rows.getLong(5), artifact(rows.getString(1)),
                LocalDate.parse(rows.getString(6), DATE), LocalDate.parse(rows.getString(7), DATE));
    }

    public List<Path> evidence(Publication publication) {
        var paths = new ArrayList<Path>(); paths.add(publication.manifestRef().path());
        publication.components().forEach(ref -> paths.add(ref.path())); paths.add(publication.sourceProofRef().path());
        return List.copyOf(paths);
    }

    private void verifyPublication(ScoreArtifactPublisher.Publication scores) throws Exception {
        var latest = artifacts.latest(scores.resolvedEndDate());
        if (!latest.equals(scores)) throw invalid("market package must use the currently resolved exact Java score publication");
    }
    private static void requireScoreAliases(java.sql.Connection connection, ScoreArtifactPublisher.Publication scores) throws Exception {
        for (var entry : Map.of("score_panel", scores.panelRef(), "score_coverage", scores.coverageRef()).entrySet())
            try (var statement = connection.prepareStatement("SELECT m.run_id,m.content_hash FROM resource_aliases a JOIN resource_metadata m ON m.uri=a.target_uri AND m.version=a.target_version AND m.content_hash=a.target_content_hash WHERE a.alias=?")) {
                statement.setString(1, "research.etf_only_top2." + entry.getKey() + "@latest");
                try (var rows = statement.executeQuery()) {
                    if (!rows.next() || !scores.producerRunId().equals(rows.getString(1)) || !entry.getValue().contentHash().equals(rows.getString(2)))
                        throw invalid("score pair changed while publishing or reading the market package");
                }
            }
    }
    private static void checkExistingFrontier(java.sql.Connection connection, LocalDate end) throws Exception {
        try (var statement = connection.prepareStatement("SELECT m.data_end_date FROM resource_aliases a JOIN resource_metadata m ON m.uri=a.target_uri AND m.version=a.target_version WHERE a.alias=?")) {
            statement.setString(1, ALIAS);
            try (var rows = statement.executeQuery()) {
                if (rows.next() && LocalDate.parse(rows.getString(1), DATE).isAfter(end)) throw invalid("market package cannot regress the complete frontier");
            }
        }
    }

    private static List<Map<String,Object>> calendar(List<Map<String,Object>> source, LocalDate start, LocalDate end) {
        LocalDate through = end.plusDays(14); var rows = new TreeMap<LocalDate,Map<String,Object>>();
        for (var input : source) {
            LocalDate date = day(input.get("cal_date")); if (date.isBefore(start) || date.isAfter(through)) continue;
            if (!"SSE".equals(Objects.toString(input.get("exchange"), ""))) throw invalid("SSE calendar required");
            var row = project(input, CALENDAR_COLUMNS); row.put("cal_date", date);
            if (!(row.get("is_open") instanceof Number number) || (number.doubleValue() != 0 && number.doubleValue() != 1)) throw invalid("typed open calendar flag required");
            row.put("is_open", number.longValue());
            if (row.get("pretrade_date") != null) {
                LocalDate prior = day(row.get("pretrade_date")); if (!prior.isBefore(date)) throw invalid("calendar pretrade date is not prior"); row.put("pretrade_date", prior);
            }
            if (rows.put(date, Collections.unmodifiableMap(row)) != null) throw invalid("duplicate calendar business key");
        }
        if (rows.size() != through.toEpochDay() - start.toEpochDay() + 1) throw invalid("calendar requires every natural day and fourteen-day execution lookahead");
        return List.copyOf(rows.values());
    }
    private static List<Map<String,Object>> market(List<Map<String,Object>> source, LocalDate start, LocalDate end, Set<String> symbols, Set<String> expected) {
        var rows = new TreeMap<String,Map<String,Object>>();
        for (var input : source) {
            LocalDate date = day(input.get("timestamp")); String code = Objects.toString(input.get("ts_code"), "");
            if (date.isBefore(start) || date.isAfter(end) || !symbols.contains(code)) continue;
            String symbol = symbol(code);
            var row = project(input, MARKET_COLUMNS); row.put("timestamp", date); row.put("ts_code", symbol);
            for (String field : List.of("open", "high", "low", "close", "amount")) {
                if (!(row.get(field) instanceof Number number) || !Double.isFinite(number.doubleValue()) || number.doubleValue() < 0
                        || (!field.equals("amount") && number.doubleValue() == 0)) throw invalid("ETF executable market value missing or invalid: " + field);
            }
            String key = date + ":" + symbol;
            if (rows.put(key, Collections.unmodifiableMap(row)) != null) throw invalid("duplicate ETF market business key");
        }
        if (!rows.keySet().containsAll(expected)) throw invalid("ETF market lacks one or more frozen score candidate business keys");
        return List.copyOf(rows.values());
    }
    private static List<Map<String,Object>> context(List<Map<String,Object>> source, LocalDate start, LocalDate end, Set<LocalDate> dates) {
        var rows = new TreeMap<LocalDate,Map<String,Object>>();
        for (var input : source) {
            LocalDate date = day(input.get("trade_date")); if (date.isBefore(start) || date.isAfter(end)) continue;
            var row = project(input, CONTEXT_COLUMNS); row.put("trade_date", date);
            YearMonth month = month(row.get("month"));
            if (!month.equals(YearMonth.from(date))) throw invalid("market context month label disagrees with trade date");
            row.put("month", month.format(DateTimeFormatter.ofPattern("uuuuMM")));
            if (row.get("updated_at") == null || !Set.of("ok", "proxy_style").contains(Objects.toString(row.get("data_quality_flag"), "")))
                throw invalid("market context requires recorded availability and canonical ok/proxy_style quality evidence");
            try { Instant.parse(row.get("updated_at").toString()); }
            catch (Exception e) { throw invalid("market context updated_at is not a recorded ISO timestamp"); }
            for (String field : List.of("hs300_ret_mtd", "zz500_ret_mtd", "all_a_ret_mtd", "small_large_ret_mtd", "growth_value_ret_mtd", "avg_up_down_ratio_5d"))
                if (!(row.get(field) instanceof Number number) || !Double.isFinite(number.doubleValue())) throw invalid("market context critical value is unavailable: " + field);
            if (rows.put(date, Collections.unmodifiableMap(row)) != null) throw invalid("duplicate market context business key");
        }
        if (!rows.keySet().equals(dates)) throw invalid("market context does not cover exactly the open signal grid");
        return List.copyOf(rows.values());
    }
    private static Set<String> scoreKeys(List<Map<String,Object>> rows, Ref panel) throws Exception {
        if (rows == null || rows.size() != panel.rowCount() || rows.size() > 5_000_000) throw invalid("provided score candidate row count differs from frozen panel");
        var keys = new HashSet<String>();
        for (var row : rows) if (!keys.add(day(row.get("trade_date")) + ":" + symbol(row.get("ts_code")))) throw invalid("duplicate score candidate key");
        var observed = new HashSet<String>();
        // LocalInputFile avoids Hadoop filesystem/user resolution, which is incompatible with Java 24 Subject removal.
        try (var reader = ParquetFileReader.open(new LocalInputFile(panel.path()))) {
            var schema = reader.getFileMetaData().getSchema();
            if (schema.getType("trade_date").asPrimitiveType().getPrimitiveTypeName() != org.apache.parquet.schema.PrimitiveType.PrimitiveTypeName.INT32
                    || !(schema.getType("trade_date").getLogicalTypeAnnotation() instanceof org.apache.parquet.schema.LogicalTypeAnnotation.DateLogicalTypeAnnotation))
                throw invalid("Java frozen score panel requires a logical DATE trade_date");
            var columns = new ColumnIOFactory().getColumnIO(schema);
            for (org.apache.parquet.column.page.PageReadStore pages; (pages = reader.readNextRowGroup()) != null;) {
                var records = columns.getRecordReader(pages, new GroupRecordConverter(schema));
                for (long index = 0; index < pages.getRowCount(); index++) {
                    Group group = records.read();
                    String key = LocalDate.ofEpochDay(group.getInteger("trade_date", 0)) + ":" + symbol(group.getString("ts_code", 0));
                    if (!observed.add(key) || observed.size() > 5_000_000) throw invalid("frozen score panel contains duplicate keys or exceeds budget");
                }
            }
        }
        if (!observed.equals(keys)) throw invalid("provided score candidates disagree with immutable Parquet keys");
        return keys;
    }
    private static void verifySource(Map<String,Object> proof, String name, List<Map<String,Object>> rows) throws Exception {
        if (proof == null || !Boolean.TRUE.equals(proof.get("sourceComplete")) || rows == null || rows.isEmpty() || rows.size() > 5_000_000)
            throw invalid("complete bounded source proof required: " + name);
        if (!(proof.get("sources") instanceof Map<?,?> sources) || !(sources.get(name) instanceof Map<?,?> source)
                || !(source.get("rows") instanceof Number count) || count.longValue() != rows.size()
                || !rowHash(rows).equals(source.get("sha256"))) throw invalid("source rows disagree with frozen read certificate: " + name);
        String table = name.equals("calendar") ? "exchange_calendar" : name.equals("etf_daily") ? "etf_daily" : "regime_features_monitor_daily";
        if (!(proof.get("sourceClocks") instanceof Map<?,?> clocks) || !(clocks.get(table) instanceof Map<?,?> clock)
                || !Boolean.TRUE.equals(clock.get("walEnabled")) || !Boolean.FALSE.equals(clock.get("suspended"))
                || !(clock.get("writerTxn") instanceof Number writer) || !(clock.get("sequencerTxn") instanceof Number sequence)
                || writer.longValue() != sequence.longValue() || !(clock.get("bufferedTxnSize") instanceof Number pending)
                || pending.longValue() != 0 || !(clock.get("id") instanceof Number) || Objects.toString(clock.get("directoryName"), "").isBlank()
                || !(clock.get("schema") instanceof List<?> schema) || schema.isEmpty()) throw invalid("source requires settled WAL, schema, and physical generation proof: " + table);
    }
    private static Map<String,Object> component(String role, String dataset, Ref ref, LocalDate asOf, String created, List<String> columns) {
        var temporal = new LinkedHashMap<String,Object>();
        temporal.put("event_time_field", role.equals("calendar") ? "cal_date" : role.equals("etf_market") ? "timestamp" : "trade_date");
        temporal.put("available_time_field", role.equals("market_context") ? "updated_at" : null); temporal.put("observation_frequency", "daily");
        temporal.put("period_label_rule", role.equals("market_context") ? "month is YYYYMM of the trade-date calendar month" : "");
        temporal.put("availability_rule", role.equals("calendar") ? "Calendar rows are reference data available before the session."
                : role.equals("market_context") ? "A context row is observable only after updated_at; frozen current revisions do not prove historical availability."
                : "Daily ETF market data is observable after the session.");
        temporal.put("point_in_time", "declared"); temporal.put("public_time_source", "unknown"); temporal.put("public_time_precision", "unknown");
        temporal.put("public_time_timezone", ""); temporal.put("revision_visibility", "latest_only");
        var snapshot = new LinkedHashMap<String,Object>();
        snapshot.put("snapshot_id", "snap_" + ref.version()); snapshot.put("dataset_id", dataset); snapshot.put("as_of", asOf.format(DATE));
        snapshot.put("schema_version", "v1"); snapshot.put("artifact_uri", ref.uri()); snapshot.put("row_count", ref.rowCount()); snapshot.put("created_at", created);
        snapshot.put("schema_digest", ref.schemaDigest()); snapshot.put("input_digest", ref.contentHash()); snapshot.put("artifact", resource(ref));
        snapshot.put("node_id", "local"); snapshot.put("data_location", ref.path().toString()); snapshot.put("max_complete_date", asOf.format(DATE));
        snapshot.put("max_observed_date", ref.end().format(DATE)); snapshot.put("completeness_status", "complete");
        snapshot.put("completeness_policy", role.equals("calendar") ? "contiguous_sse_natural_days_plus_14_v1"
                : role.equals("etf_market") ? "frozen_score_candidate_business_keys_v1" : "bounded_regime_calendar_grid_v1");
        snapshot.put("materialization_mode", "frozen"); snapshot.put("temporal", temporal); snapshot.put("manifest", null);
        snapshot.put("parts", List.of(Map.of("partition_key", "all", "artifact", resource(ref), "row_count", ref.rowCount(),
                "data_start_date", ref.start().format(DATE), "data_end_date", ref.end().format(DATE), "content_hash", ref.contentHash(), "columns", columns, "metadata", Map.of())));
        return Map.of("role", role, "required", true, "snapshot", snapshot);
    }
    private static Map<String,Object> resource(Ref ref) { return Map.of("uri", ref.uri(), "version", ref.version(), "content_hash", ref.contentHash(), "media_type", ref.path().toString().endsWith(".json") ? "application/json" : "application/vnd.apache.parquet"); }
    private void verifyRef(Ref ref) throws Exception {
        if (ref.contentHash() == null || !ref.contentHash().matches("[a-f0-9]{64}") || !ref.contentHash().substring(0, 16).equals(ref.version())
                || ref.rowCount() < 0 || !Files.isRegularFile(ref.path()) || !ScoreArtifactPublisher.sha(ref.path()).equals(ref.contentHash())) throw invalid("native immutable artifact/card changed");
    }
    private Path artifact(String uri) {
        if (uri == null || !uri.startsWith("quant://artifact/")) throw invalid("native package requires artifact resources");
        Path path = root.resolve(uri.substring("quant://artifact/".length())).normalize();
        if (!path.startsWith(root) || path.equals(root)) throw invalid("native artifact escapes root"); return path;
    }
    private static Map<String,Object> project(Map<String,Object> input, List<String> columns) {
        var output = new LinkedHashMap<String,Object>();
        for (String column : columns) { if (!input.containsKey(column)) throw invalid("missing native role column " + column); output.put(column, input.get(column)); }
        return output;
    }
    private static LocalDate day(Object value) {
        if (value instanceof LocalDate day) return day;
        if (value == null) throw invalid("native role date is missing");
        String text = value.toString();
        return text.matches("[0-9]{8}") ? LocalDate.parse(text, DATE) : LocalDate.parse(text.substring(0, 10));
    }
    private static YearMonth month(Object value) {
        if (value == null) throw invalid("market context month label is missing");
        String text = value.toString();
        try {
            if (text.matches("[0-9]{6}")) return YearMonth.parse(text, DateTimeFormatter.ofPattern("uuuuMM"));
            LocalDate first = day(value);
            if (first.getDayOfMonth() != 1) throw invalid("market context month date must be the first calendar day");
            return YearMonth.from(first);
        } catch (IllegalStateException failure) { throw failure; }
        catch (RuntimeException failure) { throw invalid("market context month label requires YYYYMM or a first-of-month date"); }
    }
    private static String symbol(Object value) {
        String symbol = Objects.toString(value, ""); if (!symbol.matches("[0-9]{6}\\.(SH|SZ|BJ)")) throw invalid("invalid ETF candidate symbol"); return symbol;
    }
    private static String rowHash(List<Map<String,Object>> rows) throws Exception {
        var digest = MessageDigest.getInstance("SHA-256");
        for (var row : rows) {
            var sorted = new TreeMap<String,Object>(); row.forEach((key,value) -> sorted.put(key, value instanceof LocalDate date ? date.toString() : value));
            byte[] bytes = JSON.writeValueAsBytes(sorted); digest.update(ByteBuffer.allocate(4).putInt(bytes.length).array()); digest.update(bytes);
        }
        return HexFormat.of().formatHex(digest.digest());
    }
    private static String digest(Object value) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(jsonBytes(value))); }
    /** Match json.dumps(ensure_ascii=False, sort_keys=True) separators used by the Python product identity. */
    private static byte[] jsonBytes(Object value) throws Exception {
        StringBuilder text = new StringBuilder(); append(JSON.valueToTree(value), text); return text.toString().getBytes(StandardCharsets.UTF_8);
    }
    private static void append(JsonNode node, StringBuilder text) throws Exception {
        if (node.isObject()) {
            text.append('{'); var keys = new TreeSet<String>(); node.fieldNames().forEachRemaining(keys::add); boolean first = true;
            for (String key : keys) { if (!first) text.append(", "); first = false; text.append(JSON.writeValueAsString(key)).append(": "); append(node.get(key), text); } text.append('}');
        } else if (node.isArray()) {
            text.append('['); for (int index = 0; index < node.size(); index++) { if (index > 0) text.append(", "); append(node.get(index), text); } text.append(']');
        } else {
            if (node.isFloatingPointNumber() && !Double.isFinite(node.doubleValue())) throw invalid("non-finite native manifest value");
            text.append(JSON.writeValueAsString(node));
        }
    }
    private static byte[] readJson(Path path) throws Exception {
        if (Files.size(path) > 16 * 1024 * 1024) throw invalid("native manifest exceeds sixteen MiB"); return Files.readAllBytes(path);
    }
    private static IllegalStateException invalid(String message) { return new IllegalStateException(message); }
}
