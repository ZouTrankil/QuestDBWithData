package com.zoutrankil.data.factor.storage;

import com.zoutrankil.data.domain.JobDefinitionJson;
import com.zoutrankil.data.factor.application.DailyFactorEvaluation;
import com.zoutrankil.data.factor.application.DailyFactorMetricRows;
import com.zoutrankil.data.factor.application.DailyMetricComputation;
import com.zoutrankil.data.factor.application.DailyMetricJobService;
import com.zoutrankil.data.factor.application.DailyMetricPipeline;
import com.zoutrankil.data.factor.application.DailyMetricPoolRequests;
import com.zoutrankil.data.factor.application.DailyMetricPublication;
import com.zoutrankil.data.factor.application.FactorComputationIdentity;
import com.zoutrankil.data.factor.application.FactorPoolEvidence;
import com.zoutrankil.data.factor.engine.DailyFactorLabels;
import com.zoutrankil.data.factor.engine.DailyFactorMetrics;
import com.zoutrankil.data.factor.engine.DailyMetricCalendar;
import com.zoutrankil.data.factor.engine.FrozenFactorSample;
import com.zoutrankil.data.repository.FileEvidenceStore;
import com.zoutrankil.data.repository.QuestDbWriteChecks;

import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/** Bind an evaluation to frozen files, calculation bytecode and settled source-table revisions. */
public final class DailyMetricInputGuard implements DailyMetricJobService.InputGuard {
    public record Version(
            String table,
            long id,
            String directory,
            long tableTxn,
            long walTxn,
            boolean suspended) {}

    private static final Set<String> TABLES =
            Set.of(
                    "factor_observation",
                    "stk_factor",
                    "stk_suspend",
                    "exchange_calendar",
                    "daily_basic",
                    "index_member",
                    "index_weight");
    private final JdbcTemplate jdbc;
    private final List<Version> versions;
    private final Map<Path, String> files;
    private final String code, fingerprint;
    private final Path poolEvidence;

    public DailyMetricInputGuard(JdbcTemplate template, List<Version> versions, List<Path> inputs)
            throws Exception {
        this(template, versions, inputs, null);
    }

    /** Only the explicitly identified pool artifact receives the pool-specific size budget. */
    public DailyMetricInputGuard(
            JdbcTemplate template, List<Version> versions, List<Path> inputs, Path poolEvidence)
            throws Exception {
        this.poolEvidence = poolEvidence == null ? null : poolEvidence.toAbsolutePath().normalize();
        if (this.poolEvidence != null
                && inputs.stream()
                        .map(p -> p.toAbsolutePath().normalize())
                        .noneMatch(this.poolEvidence::equals))
            throw new IllegalArgumentException("Pool evidence must be among frozen inputs");
        jdbc = new JdbcTemplate(Objects.requireNonNull(template.getDataSource()));
        jdbc.setQueryTimeout(60);
        var names = new HashSet<String>();
        for (var version : versions)
            if (!TABLES.contains(version.table())
                    || !names.add(version.table())
                    || version.suspended()
                    || version.tableTxn() != version.walTxn())
                throw new IllegalArgumentException(
                        "Unique settled evaluation source versions required");
        if (!names.equals(TABLES) || inputs.isEmpty() || inputs.size() > 64)
            throw new IllegalArgumentException(
                    "Complete bounded evaluation input identity required");
        this.versions = versions.stream().sorted(Comparator.comparing(Version::table)).toList();
        var hashes = new TreeMap<Path, String>();
        for (Path input : inputs) {
            Path path = input.toAbsolutePath().normalize();
            if (hashes.putIfAbsent(path, hash(path)) != null)
                throw new IllegalArgumentException("Duplicate frozen input path");
        }
        files = Collections.unmodifiableMap(hashes);
        code = code();
        fingerprint =
                FileEvidenceStore.sha256(
                        JobDefinitionJson.canonicalMapper()
                                .writeValueAsBytes(
                                        Map.of(
                                                "format",
                                                "java.daily_metric_inputs.v1",
                                                "sources",
                                                this.versions,
                                                "files",
                                                hashes.entrySet().stream()
                                                        .map(
                                                                e ->
                                                                        Map.of(
                                                                                "path",
                                                                                e.getKey()
                                                                                        .toString(),
                                                                                "sha256",
                                                                                e.getValue()))
                                                        .toList(),
                                                "code",
                                                code)));
        requireCurrent(fingerprint);
    }

    public String fingerprint() {
        return fingerprint;
    }

    public void requireCurrent(String expected) throws Exception {
        if (!fingerprint.equals(expected) || !code.equals(code()))
            throw new IllegalStateException("Metric input or code fingerprint changed");
        for (var entry : files.entrySet())
            if (!entry.getValue().equals(hash(entry.getKey())))
                throw new IllegalStateException("Frozen metric input changed: " + entry.getKey());
        for (var version : versions) {
            if (!QuestDbWriteChecks.walSettled(jdbc, version.table()))
                throw new IllegalStateException("Metric source WAL unsettled: " + version.table());
            var rows =
                    jdbc.queryForList(
                            "SELECT id,directoryName,table_txn,wal_txn,table_suspended FROM tables() WHERE table_name=?",
                            version.table());
            if (rows.size() != 1) throw new IllegalStateException("Metric source missing");
            var row = rows.getFirst();
            var current =
                    new Version(
                            version.table(),
                            ((Number) row.get("id")).longValue(),
                            (String) row.get("directoryName"),
                            ((Number) row.get("table_txn")).longValue(),
                            ((Number) row.get("wal_txn")).longValue(),
                            Boolean.TRUE.equals(row.get("table_suspended")));
            if (!version.equals(current))
                throw new IllegalStateException(
                        "Metric source revision changed: " + version.table());
        }
    }

    private String hash(Path path) throws Exception {
        if (Files.isSymbolicLink(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
            throw new IllegalArgumentException("Regular frozen input required");
        long limit = path.equals(poolEvidence) ? FactorPoolEvidence.MAX_BYTES : 32L * 1024 * 1024;
        if (Files.size(path) > limit)
            throw new IllegalArgumentException("Frozen evaluation input exceeds budget");
        var digest = MessageDigest.getInstance("SHA-256");
        try (var input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
            byte[] buffer = new byte[64 * 1024];
            long total = 0;
            int count;
            while ((count = input.read(buffer)) != -1) {
                total += count;
                if (total > limit)
                    throw new IllegalArgumentException("Frozen evaluation input exceeds budget");
                digest.update(buffer, 0, count);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    public static String code() throws Exception {
        return FactorComputationIdentity.sha256(
                DailyFactorEvaluation.class,
                DailyFactorLabels.class,
                DailyFactorMetrics.class,
                FrozenFactorSample.class,
                DailyFactorMetricRows.class,
                DailyMetricPublication.class,
                DailyMetricStorageMapping.class,
                DailyMetricInputGuard.class,
                QuestDbDailyEvaluationInputs.class,
                QuestDbDailyEvaluationExposures.class,
                DailyMetricComputation.class,
                DailyMetricPipeline.class,
                DailyMetricPoolRequests.class,
                DailyMetricCalendar.class,
                FactorPoolEvidence.class);
    }
}
