package com.zoutrankil.market.us.source;

import com.fasterxml.jackson.databind.JsonNode;
import com.zoutrankil.batch.source.SourceContract;
import com.zoutrankil.batch.source.SourceCoveragePolicy;
import com.zoutrankil.batch.source.SourcePolicyProvider;
import com.zoutrankil.batch.source.SourceRowPolicy;
import com.zoutrankil.batch.source.SourceStoragePolicy;
import com.zoutrankil.batch.source.SourceStrategy;
import com.zoutrankil.batch.source.StandardSourceRequestPolicy;
import com.zoutrankil.batch.support.Json;
import com.zoutrankil.data.domain.PageContract;
import com.zoutrankil.data.domain.temporal.MarketId;

import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** 美国利率来源通过通用接口注册；日期、字段和空值保持原契约。 */
public final class UsSourcePolicyProvider implements SourcePolicyProvider {
    private static final SourceStrategy STRATEGY =
            new SourceStrategy(new Requests(), new Rows(), new Coverage(), new Storage());

    @Override
    public MarketId market() {
        return MarketId.US;
    }

    @Override
    public boolean usesSourceDates(String dataset) {
        return dataset.equals("us_tbr");
    }

    @Override
    public Map<String, SourceStrategy> strategies() {
        return Map.of("us_tbr", STRATEGY);
    }

    @Override
    public List<String> qualityFailures(String dataset, List<Map<String, Object>> rows) {
        if (!dataset.equals("us_tbr")) throw new IllegalArgumentException("Unknown US source");
        if (rows.isEmpty())
            return SourceContract.load(dataset).emptyAllowed()
                    ? List.of()
                    : List.of("empty-required-source");
        var failures = new LinkedHashSet<String>();
        if ((double) rows.stream().filter(row -> row.get("date") == null).count() / rows.size()
                > 0.01) failures.add("completeness:date");
        for (var row : rows)
            if (List.of(
                            "w4_bd", "w4_ce", "w8_bd", "w8_ce", "w13_bd", "w13_ce", "w17_bd",
                            "w17_ce", "w26_bd", "w26_ce", "w52_bd", "w52_ce")
                    .stream()
                    .noneMatch(field -> row.get(field) != null))
                failures.add("completeness:us_tbr-rates");
        return List.copyOf(failures);
    }

    private static final class Requests extends StandardSourceRequestPolicy {
        @Override
        public PageContract pageContract(SourceContract c) {
            return unpaged(
                    c, sourceFields(c), sourceKeys(c), Set.of("date"), c.sourceDocumentation());
        }
    }

    private static final class Coverage implements SourceCoveragePolicy {
        @Override
        public boolean isMarketAggregate(SourceContract c) {
            return true;
        }

        // 保留旧契约公开过的代码校验；聚合请求本身仍必须传空证券集合。
        @Override
        public boolean validCode(SourceContract c, String code) {
            return code != null && code.matches("[0-9]{6}\\.(SH|SZ|BJ)");
        }

        @Override
        public Set<String> observedCodes(SourceContract c, List<Map<String, Object>> rows) {
            return Set.of();
        }

        @Override
        public boolean covers(
                SourceContract c, List<Map<String, Object>> rows, Set<String> expected) {
            return expected.isEmpty() && rows.size() <= 1;
        }

        @Override
        public boolean coversRange(
                SourceContract c,
                List<Map<String, Object>> rows,
                Set<String> expected,
                LocalDate start,
                LocalDate end) {
            return covers(c, rows, expected);
        }
    }

    private static final class Rows implements SourceRowPolicy {
        @Override
        public List<Map<String, JsonNode>> prepareRows(
                SourceContract c,
                List<Map<String, JsonNode>> rows,
                LocalDate day,
                Set<String> expected,
                String code) {
            return rows;
        }

        @Override
        public boolean ignoredFooter(SourceContract c, Map<String, JsonNode> row, LocalDate day) {
            return false;
        }

        @Override
        public LocalDate observationDate(
                SourceContract c, Map<String, JsonNode> row, LocalDate fallback) {
            return fallback;
        }

        @Override
        public Map<String, Object> normalize(
                SourceContract c, Map<String, JsonNode> row, LocalDate day, Set<String> expected) {
            if (!expected.isEmpty())
                throw new IllegalArgumentException(
                        "Aggregate source cannot claim security coverage");
            var result = new LinkedHashMap<String, Object>();
            for (var column : c.businessColumns()) {
                JsonNode value = row.get(column.source());
                if (value == null)
                    throw new IllegalArgumentException("Schema drift: missing " + column.source());
                if (value.isNull()) {
                    if (column.key()) throw new IllegalArgumentException("Null business key");
                    result.put(column.target(), null);
                    continue;
                }
                if (column.type().equals("TIMESTAMP")) {
                    if (!value.isTextual() || !value.asText().matches("[0-9]{8}"))
                        throw new IllegalArgumentException("Exact business date required");
                    var date = LocalDate.parse(value.asText(), DateTimeFormatter.BASIC_ISO_DATE);
                    if (!date.equals(day))
                        throw new IllegalArgumentException(
                                "Source returned a different logical date");
                    result.put(column.target(), date.toString());
                } else if (column.type().equals("DOUBLE")) {
                    double number;
                    if (value.isNumber()) number = value.doubleValue();
                    else if (value.isTextual()
                            && value.asText()
                                    .trim()
                                    .matches(
                                            "[+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?"))
                        number = Double.parseDouble(value.asText().trim());
                    else throw new IllegalArgumentException("Numeric type mismatch");
                    if (!Double.isFinite(number))
                        throw new IllegalArgumentException("Finite numeric value required");
                    result.put(column.target(), number);
                } else throw new IllegalArgumentException("Unsupported US source type");
            }
            return Collections.unmodifiableMap(result);
        }

        @Override
        public Map<String, Object> validateNormalized(
                SourceContract c, Map<String, Object> row, LocalDate day, Set<String> expected) {
            if (!row.keySet()
                    .equals(
                            new HashSet<>(
                                    c.businessColumns().stream()
                                            .map(SourceContract.Column::target)
                                            .toList())))
                throw new IllegalArgumentException(
                        "Frozen schema differs from registered contract");
            var source = new LinkedHashMap<String, JsonNode>();
            for (var column : c.businessColumns()) {
                Object value = row.get(column.target());
                if (column.type().equals("TIMESTAMP") && value != null)
                    value =
                            LocalDate.parse(value.toString())
                                    .format(DateTimeFormatter.BASIC_ISO_DATE);
                source.put(column.source(), Json.MAPPER.valueToTree(value));
            }
            return normalize(c, source, day, expected);
        }
    }

    private static final class Storage implements SourceStoragePolicy {
        @Override
        public List<SourceContract.Column> businessColumns(SourceContract c) {
            return c.columns();
        }

        @Override
        public String logicalDateColumn(SourceContract c) {
            return "date";
        }

        @Override
        public int writeBatchSize(SourceContract c) {
            return 1000;
        }

        @Override
        public Map<String, Object> physicalRow(
                SourceContract c, Map<String, Object> row, Instant createdAt) {
            return row;
        }

        @Override
        public String key(SourceContract c, Map<String, Object> row) {
            return Json.write(
                    c.columns().stream()
                            .filter(SourceContract.Column::key)
                            .map(column -> Objects.requireNonNull(row.get(column.target()), "key"))
                            .toList());
        }

        @Override
        public String createTableSql(SourceContract c, String table) {
            SourceContract.requireTarget(table);
            return "CREATE TABLE IF NOT EXISTS "
                    + table
                    + " ("
                    + String.join(
                            ",",
                            c.columns().stream()
                                    .map(column -> quote(column.target()) + " " + column.type())
                                    .toList())
                    + ") TIMESTAMP("
                    + quote(c.timestampColumn())
                    + ") PARTITION BY "
                    + c.partitionBy()
                    + " WAL DEDUP UPSERT KEYS("
                    + String.join(
                            ",",
                            c.columns().stream()
                                    .filter(SourceContract.Column::key)
                                    .map(column -> quote(column.target()))
                                    .toList())
                    + ")";
        }

        private static String quote(String value) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
    }
}
