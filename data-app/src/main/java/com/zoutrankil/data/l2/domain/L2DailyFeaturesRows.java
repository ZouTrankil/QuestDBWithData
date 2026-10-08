package com.zoutrankil.data.l2.domain;

import com.zoutrankil.data.domain.*;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.Map;

/** Pure D086 canonical row encoding, bounds and explicit target admission. */
public final class L2DailyFeaturesRows {
    private L2DailyFeaturesRows() {}
    public static final String ISOLATED_TABLE_PREFIX = "java_d086_l2_daily_features_";
    public static final int MAX_BATCH_ROWS = 200;
    public static final int MAX_BATCH_BYTES = 1024 * 1024;
    public static final int MAX_READBACK_KEYS = 200;
    private static final ObjectMapper JSON = new ObjectMapper();

    public static L2DailyFeaturesKey key(L2DailyFeatures row) { return row.key(); }

    public static byte[] canonicalBytes(L2DailyFeatures row) {
        try { return JSON.writeValueAsBytes(canonicalMap(row)); }
        catch (JsonProcessingException failure) { throw new IllegalStateException("Cannot encode D086 row", failure); }
    }

    public static int estimatedTransportBytes(L2DailyFeatures row, byte[] canonical) {
        return Math.addExact(Math.multiplyExact(canonical.length, 8), 512);
    }

    public static void requireIsolatedTableName(String table) {
        DatasetDefinition.identifier(table);
        if (!table.startsWith(ISOLATED_TABLE_PREFIX) || table.length() <= ISOLATED_TABLE_PREFIX.length())
            throw new IllegalStateException("D086 writes require java_d086_l2_daily_features_<suffix>");
    }

    private static Map<String, Object> canonicalMap(L2DailyFeatures row) {
        var values = new LinkedHashMap<String, Object>();
        values.put("ts", row.tradeDate().toString());
        values.put("symbol", row.symbol());
        for (var field : L2DailyFeatureField.values())
            if (!field.identity()) values.put(field.fieldName(), row.features().get(field));
        return values;
    }
}
