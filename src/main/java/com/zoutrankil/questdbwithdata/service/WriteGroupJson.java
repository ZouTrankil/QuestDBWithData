package com.zoutrankil.questdbwithdata.service;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import com.zoutrankil.questdbwithdata.domain.*;
import com.zoutrankil.questdbwithdata.repository.DatasetWritePreparation;
import java.nio.file.*;
import java.time.LocalDate;
import java.util.*;

/** Explicit typed rows; targets are resolved by registered owners, never accepted from this file. */
public final class WriteGroupJson {
    private final DatasetRegistry datasets;
    public WriteGroupJson(DatasetRegistry datasets) { this.datasets = Objects.requireNonNull(datasets); }
    public WriteGroupRequest read(Path path) throws Exception {
        try (var input = Files.newInputStream(path)) { return parse(input.readNBytes(64 * 1024 * 1024 + 1)); }
    }
    public WriteGroupRequest parse(byte[] bytes) throws Exception {
        if (bytes == null || bytes.length > 64 * 1024 * 1024) throw new IllegalArgumentException("Write JSON exceeds 64 MiB");
        var root = new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(bytes);
        ReadGroupJson.fields(root, "batchId", "logicalDate", "members");
        var members = root.required("members");
        if (!members.isArray() || members.isEmpty() || members.size() > 64)
            throw new IllegalArgumentException("Explicit bounded write members required");
        var result = new ArrayList<WriteGroupRequest.Member>();
        long normalizedBytes = 0;
        for (var member : members) {
            ReadGroupJson.fields(member, "memberId", "datasetId", "definitionVersion", "batchId", "rows");
            String dataset = ReadGroupJson.text(member.required("datasetId"));
            var definition = datasets.require(dataset).definition();
            int version = ReadGroupJson.integer(member.required("definitionVersion"));
            if (version != definition.schemaVersion()) throw new IllegalArgumentException("Write definition version differs");
            var input = member.required("rows");
            if (!input.isArray() || input.size() > 10000) throw new IllegalArgumentException("Bounded full rows required");
            var columns = definition.columns().stream().map(DatasetDefinition.Column::logicalName).toArray(String[]::new);
            var rows = new ArrayList<DatasetValues>();
            for (var row : input) {
                ReadGroupJson.fields(row, columns);
                var values = new LinkedHashMap<String,Object>();
                for (var column : definition.columns()) values.put(column.logicalName(),
                        ReadGroupJson.value(column, row.required(column.logicalName())));
                rows.add(new DatasetValues(values));
            }
            var limits=new DatasetWritePreparation.Limits(10000,16*1024*1024);
            var checked=definition.capabilities().contains(DatasetDefinition.Capability.STATIC_REPLACE)
                    ? DatasetWritePreparation.prepareStatic(definition,rows,java.util.function.Function.identity(),limits)
                    : DatasetWritePreparation.prepare(definition,rows,java.util.function.Function.identity(),limits);
            normalizedBytes += checked.normalizedBytes();
            if (normalizedBytes > 64 * 1024 * 1024) throw new IllegalArgumentException("Normalized write group exceeds 64 MiB");
            result.add(new WriteGroupRequest.Member(ReadGroupJson.text(member.required("memberId")), dataset, version,
                    ReadGroupJson.text(member.required("batchId")), rows));
        }
        return new WriteGroupRequest(ReadGroupJson.text(root.required("batchId")),
                LocalDate.parse(ReadGroupJson.text(root.required("logicalDate"))), result);
    }
}
