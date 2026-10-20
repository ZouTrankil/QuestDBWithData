package com.zoutrankil.data.derived.storage;

import com.zoutrankil.data.domain.JobDefinitionJson;
import com.zoutrankil.data.repository.FileEvidenceStore;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Retain raw history; exclude only proven duplicate aliases outside the canonical daily universe. */
public final class BseLegacyFactorAliases {
    private static final int MAX_MONTH_ROWS = 50_000;
    private static final String POLICY = "canonical_daily_universe_exact_bse_alias_copy_v1";

    private record Reference(Map<String, String> mappings, String sha256, String responseSha256) {}

    private static final Reference REFERENCE = load();

    public record Key(LocalDate date, String symbol) {}

    record Values(double close, double preClose, double amount) {}

    record Factor(Key key, Values values) {}

    public record Scope(Set<Key> excluded, Map<String, Object> evidence) {
        public Scope {
            excluded = Set.copyOf(excluded);
            evidence = Map.copyOf(evidence);
        }

        public boolean excludes(LocalDate date, String symbol) {
            return excluded.contains(new Key(date, symbol));
        }
    }

    public static Map<String, String> identity() {
        return Map.of(
                "policy",
                POLICY,
                "mappingSha256",
                REFERENCE.sha256,
                "sourceResponseSha256",
                REFERENCE.responseSha256);
    }

    public static String canonicalSymbol(String legacy) {
        return REFERENCE.mappings.get(legacy);
    }

    private static Reference load() {
        try (var stream =
                BseLegacyFactorAliases.class.getResourceAsStream(
                        "/reference-data/bse-legacy-code-mapping.json")) {
            if (stream == null) throw new IOException("Missing BSE alias reference");
            byte[] bytes = stream.readNBytes(128 * 1024 + 1);
            if (bytes.length > 128 * 1024)
                throw new IOException("BSE alias reference exceeds bound");
            var json = JobDefinitionJson.mapper().readTree(bytes);
            if (!POLICY.equals(json.path("policy").asText())
                    || !"bse_mapping".equals(json.path("source_endpoint").asText())
                    || !json.path("source_response_sha256").asText().matches("[a-f0-9]{64}"))
                throw new IOException("Invalid BSE reference identity");
            var mappings = new TreeMap<String, String>();
            var targets = new HashSet<String>();
            for (var row : json.path("mappings")) {
                String old = row.path("old").asText(), canonical = row.path("canonical").asText();
                if (!old.matches("[48][0-9]{5}\\.BJ")
                        || !canonical.matches("920[0-9]{3}\\.BJ")
                        || mappings.put(old, canonical) != null
                        || !targets.add(canonical))
                    throw new IOException("BSE aliases must be unique official code pairs");
            }
            if (mappings.isEmpty() || mappings.size() > 300)
                throw new IOException("BSE mapping count exceeds source contract");
            return new Reference(
                    Map.copyOf(mappings),
                    FileEvidenceStore.sha256(bytes),
                    json.path("source_response_sha256").asText());
        } catch (IOException failure) {
            throw new ExceptionInInitializerError(failure);
        }
    }

    public static Scope prepare(JdbcTemplate jdbc, LocalDate lower, LocalDate upper) {
        return prepare(jdbc, lower, upper, () -> {});
    }

    public static Scope prepare(
            JdbcTemplate jdbc, LocalDate lower, LocalDate upper, Runnable consumeReadBudget) {
        var factors = new ArrayList<Factor>();
        var daily = new HashSet<Key>();
        String bounds =
                " WHERE trade_date>=cast(? AS TIMESTAMP) AND trade_date<cast(? AS TIMESTAMP) AND ts_code LIKE '%.BJ' ORDER BY trade_date,ts_code LIMIT "
                        + (MAX_MONTH_ROWS + 1);
        jdbc.query(
                "SELECT trade_date,ts_code,close,pre_close,amount FROM stk_factor" + bounds,
                (RowCallbackHandler)
                        rs -> {
                            consumeReadBudget.run();
                            if (factors.size() >= MAX_MONTH_ROWS)
                                throw new IllegalStateException(
                                        "BSE factor scope exceeds monthly bound");
                            factors.add(
                                    new Factor(
                                            new Key(date(rs), rs.getString("ts_code")),
                                            new Values(
                                                    number(rs, "close"),
                                                    number(rs, "pre_close"),
                                                    number(rs, "amount"))));
                        },
                micros(lower),
                micros(upper));
        int[] count = {0};
        jdbc.query(
                "SELECT trade_date,ts_code FROM daily" + bounds,
                (RowCallbackHandler)
                        rs -> {
                            consumeReadBudget.run();
                            if (++count[0] > MAX_MONTH_ROWS)
                                throw new IllegalStateException(
                                        "BSE daily scope exceeds monthly bound");
                            if (!daily.add(new Key(date(rs), rs.getString("ts_code"))))
                                throw new IllegalStateException(
                                        "Duplicate canonical BSE daily key");
                        },
                micros(lower),
                micros(upper));
        var scope = prepare(factors, daily);
        var evidence = new LinkedHashMap<String, Object>(scope.evidence);
        evidence.put("fromInclusive", lower.toString());
        evidence.put("toExclusive", upper.toString());
        return new Scope(scope.excluded, evidence);
    }

    static Scope prepare(List<Factor> factors, Set<Key> daily) {
        var byKey = new HashMap<Key, Values>();
        for (var row : factors)
            if (byKey.put(row.key, row.values) != null)
                throw new IllegalStateException("Duplicate BSE factor key");
        var excluded = new HashSet<Key>();
        var lines = new ArrayList<String>();
        for (var row : factors) {
            String canonical = REFERENCE.mappings.get(row.key.symbol);
            if (canonical == null) continue;
            var canonicalKey = new Key(row.key.date, canonical);
            if (daily.contains(row.key) && daily.contains(canonicalKey))
                throw new IllegalStateException(
                        "Both BSE aliases occur in canonical daily universe");
            if (daily.contains(row.key) || !daily.contains(canonicalKey)) continue;
            var current = byKey.get(canonicalKey);
            if (current == null
                    || !valid(row.values)
                    || !valid(current)
                    || !row.values.equals(current))
                throw new IllegalStateException(
                        "BSE legacy alias lacks an exact canonical factor copy: " + row.key);
            excluded.add(row.key);
            lines.add(row.key.date + "|" + row.key.symbol + "|" + canonical + "|" + row.values);
        }
        Collections.sort(lines);
        var proof = new LinkedHashMap<String, Object>(identity());
        proof.put("verifiedDuplicateAliasRows", excluded.size());
        proof.put("observedBseFactorRows", factors.size());
        proof.put("canonicalBseDailyKeys", daily.size());
        proof.put(
                "excludedKeysAndValuesSha256",
                FileEvidenceStore.sha256(
                        String.join("\n", lines).getBytes(StandardCharsets.UTF_8)));
        proof.put("rawSourceRowsPreserved", true);
        return new Scope(excluded, proof);
    }

    // Paired SQL NULLs remain missing in the retained canonical row; they are never converted to
    // zero.
    private static boolean valid(Values value) {
        return !Double.isInfinite(value.close)
                && !Double.isInfinite(value.preClose)
                && !Double.isInfinite(value.amount);
    }

    private static LocalDate date(ResultSet rs) throws SQLException {
        var value = rs.getTimestamp("trade_date").toInstant().atOffset(ZoneOffset.UTC);
        if (!value.toLocalTime().equals(LocalTime.MIDNIGHT))
            throw new SQLException("BSE reference day must be UTC midnight");
        return value.toLocalDate();
    }

    private static double number(ResultSet rs, String field) throws SQLException {
        double value = rs.getDouble(field);
        return rs.wasNull() ? Double.NaN : value;
    }

    private static long micros(LocalDate date) {
        return Math.multiplyExact(date.toEpochDay(), 86_400_000_000L);
    }
}
