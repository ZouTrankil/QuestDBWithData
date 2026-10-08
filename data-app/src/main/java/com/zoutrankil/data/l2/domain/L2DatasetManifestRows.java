package com.zoutrankil.data.l2.domain;

import com.zoutrankil.data.domain.*;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.nio.charset.StandardCharsets;

/** Pure D085 canonical row encoding, bounds and explicit target admission. */
public final class L2DatasetManifestRows {
    private L2DatasetManifestRows() {}
    public static final String ISOLATED_TABLE_PREFIX = "java_d085_l2_dataset_manifest_";
    public static final int MAX_BATCH_ROWS = 200;
    public static final int MAX_BATCH_BYTES = 1024 * 1024;
    public static final int MAX_READBACK_KEYS = 200;
    public static final int MAX_DATE_ROWS = 100_000;

    public static L2DatasetManifestKey key(L2DatasetManifest row) { return row.key(); }

    public static byte[] canonicalBytes(L2DatasetManifest row) {
        try {
            var bytes = new ByteArrayOutputStream(512);
            try (var output = new DataOutputStream(bytes)) {
                writeString(output, row.tradeDate().toString());
                writeString(output, row.symbol());
                writeNullableString(output, row.market());
                writeNullableString(output, row.board());
                writeNullableString(output, row.sourceRoot());
                writeNullableString(output, row.outputRoot());
                writeNullableString(output, row.featureVersion());
                writeNullableBoolean(output, row.dailyFeatureOk());
                writeNullableBoolean(output, row.t0Ok());
                writeNullableString(output, row.rawRowCounts());
                writeNullableString(output, row.outputPaths());
                writeNullableString(output, row.costConfig());
                writeNullableString(output, row.horizonsMin());
                writeNullableString(output, row.errors());
                output.writeLong(row.batchId());
                writeString(output, row.tradeDateTs().toString());
            }
            return bytes.toByteArray();
        } catch (java.io.IOException impossible) { throw new IllegalStateException(impossible); }
    }

    public static int estimatedTransportBytes(L2DatasetManifest row, byte[] canonical) {
        return Math.addExact(Math.multiplyExact(canonical.length, 8), 256);
    }

    public static void requireIsolatedTableName(String table) {
        DatasetDefinition.identifier(table);
        if (!table.startsWith(ISOLATED_TABLE_PREFIX) || table.length() <= ISOLATED_TABLE_PREFIX.length())
            throw new IllegalStateException("D085 writes require a java_d085_l2_dataset_manifest_<suffix> target");
    }

    private static void writeNullableString(DataOutputStream output, String value) throws java.io.IOException {
        output.writeBoolean(value != null);
        if (value != null) writeString(output, value);
    }

    private static void writeNullableBoolean(DataOutputStream output, Boolean value) throws java.io.IOException {
        output.writeBoolean(value != null);
        if (value != null) output.writeBoolean(value);
    }

    private static void writeString(DataOutputStream output, String value) throws java.io.IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        output.writeInt(bytes.length);
        output.write(bytes);
    }
}
