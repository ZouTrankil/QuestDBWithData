package com.zoutrankil.data.l2.domain;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.domain.temporal.TemporalValues;
import java.util.LinkedHashMap;
import java.util.Map;

/** Pure D087 admission and canonical bytes; no storage or application dependency. */
public final class L2IntradayBarFeaturesRows {
    public static final String ISOLATED_TABLE_PREFIX = "java_d087_l2_intraday_bar_features_";
    public static final int MAX_BATCH_ROWS = 200;
    public static final int MAX_BATCH_BYTES = 1024 * 1024;
    public static final int MAX_READBACK_KEYS = 200;
    private static final ObjectMapper JSON = new ObjectMapper();
    private L2IntradayBarFeaturesRows() {}
    public static L2IntradayBarFeaturesKey key(L2IntradayBarFeatures row) { return row.key(); }
    public static byte[] canonicalBytes(L2IntradayBarFeatures row) {
        try { return JSON.writeValueAsBytes(canonicalMap(row)); }
        catch (JsonProcessingException failure) {
            throw new IllegalStateException("Cannot encode a canonical D087 source row", failure);
        }
    }
    public static int estimatedTransportBytes(L2IntradayBarFeatures row, byte[] canonical) {
        return Math.addExact(Math.multiplyExact(canonical.length, 8), 512);
    }
public static void requireIsolatedTableName(String table) {
        DatasetDefinition.identifier(table);
        if (!table.startsWith(ISOLATED_TABLE_PREFIX) || table.length() <= ISOLATED_TABLE_PREFIX.length())
            throw new IllegalStateException("D087 writes require java_d087_l2_intraday_bar_features_<suffix>");
    }
private static Map<String, Object> canonicalMap(L2IntradayBarFeatures row) {
        var values = new LinkedHashMap<String, Object>();
        values.put("trade_date", TemporalValues.formatDate(row.tradeDate(), TemporalValues.DateFormat.BASIC));
        values.put("symbol", row.symbol());
        values.put("market", row.market());
        values.put("board", row.board());
        values.put("minute", row.minute().toString());
        for (var field : L2IntradayBarFeatureField.values())
            if (field.metric()) values.put(field.fieldName(), row.features().get(field));
        return values;
    }
}
