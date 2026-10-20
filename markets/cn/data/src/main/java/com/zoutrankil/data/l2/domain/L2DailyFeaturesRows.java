package com.zoutrankil.data.l2.domain;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zoutrankil.data.domain.DatasetDefinition;
import com.zoutrankil.data.domain.l2.L2DailyFeatureField;
import com.zoutrankil.data.domain.l2.L2DailyFeatures;
import com.zoutrankil.data.domain.l2.L2DailyFeaturesKey;
import com.zoutrankil.market.cn.model.CnTradeDateLayout;

import java.util.LinkedHashMap;
import java.util.Map;

/** Pure D086 canonical row encoding, bounds and explicit target admission. */
public final class L2DailyFeaturesRows {
    private L2DailyFeaturesRows() {}

    public static final String ISOLATED_TABLE_PREFIX = "java_d086_l2_daily_features_";
    public static final int MAX_BATCH_ROWS = 200;
    public static final int MAX_BATCH_BYTES = 1024 * 1024;
    public static final int MAX_FORMAL_BATCH_BYTES = 8 * 1024 * 1024;
    public static final int MAX_READBACK_KEYS = 200;
    private static final ObjectMapper JSON = new ObjectMapper();

    public static L2DailyFeaturesKey key(L2DailyFeatures row) {
        return row.key();
    }

    public static byte[] canonicalBytes(L2DailyFeatures row) {
        return canonicalBytes(row, CnTradeDateLayout.LEGACY_V1);
    }

    /** v1 原 JSON 字节保持不动；v2 日期键单独编码，不能互借完成摘要。 */
    public static byte[] canonicalBytes(L2DailyFeatures row, CnTradeDateLayout layout) {
        try {
            return JSON.writeValueAsBytes(canonicalMap(row, layout));
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("Cannot encode D086 row", failure);
        }
    }

    public static int estimatedTransportBytes(L2DailyFeatures row, byte[] canonical) {
        return Math.addExact(Math.multiplyExact(canonical.length, 8), 512);
    }

    public static void requireIsolatedTableName(String table) {
        DatasetDefinition.identifier(table);
        if (!table.startsWith(ISOLATED_TABLE_PREFIX)
                || table.length() <= ISOLATED_TABLE_PREFIX.length())
            throw new IllegalStateException(
                    "D086 writes require java_d086_l2_daily_features_<suffix>");
    }

    private static Map<String, Object> canonicalMap(L2DailyFeatures row, CnTradeDateLayout layout) {
        var values = new LinkedHashMap<String, Object>();
        values.put(layout.physicalDateColumn("l2_daily_features"), row.tradeDate().toString());
        values.put("symbol", row.symbol());
        for (var field : L2DailyFeatureField.values())
            if (!field.identity()) values.put(field.fieldName(), row.features().get(field));
        return values;
    }
}
