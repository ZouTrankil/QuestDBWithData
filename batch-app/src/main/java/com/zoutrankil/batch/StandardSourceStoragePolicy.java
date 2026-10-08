package com.zoutrankil.batch;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

record StandardSourceStoragePolicy(Technical technical, Key keyPolicy, int batchSize) implements SourceStoragePolicy {
    enum Technical { NONE, UPDATE_TIME, EPOCH_AND_UPDATE_TIME }
    enum Key { BUSINESS, STOCK_CODE, FUTURES_EPOCH, ETF_EPOCH }
    StandardSourceStoragePolicy { Objects.requireNonNull(technical); Objects.requireNonNull(keyPolicy); }

    @Override public List<SourceContract.Column> businessColumns(SourceContract contract) {
        if (technical == Technical.NONE) return contract.columns();
        return contract.columns().stream().filter(column -> !column.target().equals("update_time")
                && (technical != Technical.EPOCH_AND_UPDATE_TIME || !column.target().equals("timestamp"))).toList();
    }
    @Override public String logicalDateColumn(SourceContract contract) {
        return contract.columns().stream().filter(column -> column.source().equals(contract.sourceDate()))
                .findFirst().orElseThrow().target();
    }
    @Override public int writeBatchSize(SourceContract contract) { return batchSize; }
    @Override public Map<String,Object> physicalRow(SourceContract contract, Map<String,Object> businessRow, Instant createdAt) {
        if (technical == Technical.NONE) return businessRow;
        var result = new LinkedHashMap<String,Object>(businessRow);
        if (technical == Technical.EPOCH_AND_UPDATE_TIME) result.put("timestamp","1970-01-01T00:00:00Z");
        result.put("update_time",createdAt.truncatedTo(java.time.temporal.ChronoUnit.MICROS).toString());
        return Collections.unmodifiableMap(result);
    }
    @Override public String key(SourceContract contract, Map<String,Object> row) {
        if (keyPolicy == Key.FUTURES_EPOCH || keyPolicy == Key.ETF_EPOCH) {
            Object timestamp = row.get("timestamp");
            if (timestamp != null && !Set.of("1970-01-01","1970-01-01T00:00:00Z","1970-01-01T00:00:00").contains(timestamp.toString()))
                throw new IllegalArgumentException(keyPolicy == Key.FUTURES_EPOCH
                        ? "Futures basic technical timestamp must remain the legacy epoch sentinel"
                        : "ETF basic technical timestamp must remain the legacy epoch sentinel");
            return Json.write(List.of(Objects.requireNonNull(row.get("ts_code"),"key"),"1970-01-01"));
        }
        if (keyPolicy == Key.STOCK_CODE) return Json.write(List.of(Objects.requireNonNull(row.get("ts_code"),"key")));
        return Json.write(contract.columns().stream().filter(SourceContract.Column::key)
                .map(column -> Objects.requireNonNull(row.get(column.target()),"key")).toList());
    }
    @Override public String createTableSql(SourceContract contract, String table) {
        SourceContract.requireTarget(table);
        return "CREATE TABLE IF NOT EXISTS "+table+" ("+String.join(",",contract.columns().stream().map(c -> quoteIdentifier(c.target())+" "+c.type()).toList())
                +") TIMESTAMP("+quoteIdentifier(contract.timestampColumn())+") PARTITION BY "+contract.partitionBy()+" WAL DEDUP UPSERT KEYS("
                +String.join(",",contract.columns().stream().filter(SourceContract.Column::key).map(c -> quoteIdentifier(c.target())).toList())+")";
    }
    private static String quoteIdentifier(String value) { return "\""+value.replace("\"","\"\"")+"\""; }
}
