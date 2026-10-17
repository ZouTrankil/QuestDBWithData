package com.zoutrankil.data.l2.domain;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zoutrankil.data.domain.DatasetDefinition;
import com.zoutrankil.data.domain.L2EventResponseFeatures;
import com.zoutrankil.data.domain.L2EventResponseFeaturesKey;
import com.zoutrankil.data.domain.L2EventResponseFeatureField;
import com.zoutrankil.data.domain.temporal.TemporalValues;
import java.util.LinkedHashMap;
import java.util.Map;

/** Pure D088 row encoding, batch bounds and explicit target-name admission. */
public final class L2EventResponseFeaturesRows {
    public static final String ISOLATED_TABLE_PREFIX = "java_d088_l2_event_response_features_";
    public static final int MAX_BATCH_ROWS = 200;
    public static final int MAX_BATCH_BYTES = 1024 * 1024;
    public static final int MAX_READBACK_KEYS = 200;
    private static final ObjectMapper JSON = new ObjectMapper();

    private L2EventResponseFeaturesRows() {}

    public static L2EventResponseFeaturesKey key(L2EventResponseFeatures row) { return row.key(); }
    public static byte[] canonicalBytes(L2EventResponseFeatures row) {
        try { return JSON.writeValueAsBytes(canonicalMap(row)); }
        catch (JsonProcessingException failure) {
            throw new IllegalStateException("Cannot encode a canonical D088 source row", failure);
        }
    }
    public static int estimatedTransportBytes(L2EventResponseFeatures row, byte[] canonical) {
        return Math.addExact(Math.multiplyExact(canonical.length, 8), 512);
    }

    public static void requireIsolatedTableName(String table) {
        DatasetDefinition.identifier(table);
        if (!table.startsWith(ISOLATED_TABLE_PREFIX) || table.length() <= ISOLATED_TABLE_PREFIX.length())
            throw new IllegalStateException("D088 writes require java_d088_l2_event_response_features_<suffix>");
    }

    private static Map<String, Object> canonicalMap(L2EventResponseFeatures row) {
        var values = new LinkedHashMap<String, Object>();
        values.put("trade_date", TemporalValues.formatDate(row.tradeDate(), TemporalValues.DateFormat.BASIC));
        values.put("symbol", row.symbol());
        values.put("market", row.market());
        values.put("board", row.board());
        values.put("minute", row.minute().toString());
        values.put("event_type", row.eventType());
        for (var field : L2EventResponseFeatureField.values())
            if (field.metric()) values.put(field.fieldName(), row.features().get(field));
        return values;
    }
}
