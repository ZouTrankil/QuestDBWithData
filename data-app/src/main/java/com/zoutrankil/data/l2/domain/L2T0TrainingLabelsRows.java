package com.zoutrankil.data.l2.domain;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zoutrankil.data.domain.DatasetDefinition;
import com.zoutrankil.data.domain.L2T0TrainingLabels;
import com.zoutrankil.data.domain.L2T0TrainingLabelsKey;
import com.zoutrankil.data.domain.L2T0TrainingLabelField;
import com.zoutrankil.data.domain.temporal.TemporalValues;
import java.util.LinkedHashMap;
import java.util.Map;

/** Pure D089 row encoding, batch bounds and explicit target-name admission. */
public final class L2T0TrainingLabelsRows {
    public static final String ISOLATED_TABLE_PREFIX = "java_d089_l2_t0_training_labels_";
    public static final int MAX_BATCH_ROWS = 200;
    public static final int MAX_BATCH_BYTES = 1024 * 1024;
    public static final int MAX_READBACK_KEYS = 200;
    private static final ObjectMapper JSON = new ObjectMapper();

    private L2T0TrainingLabelsRows() {}

    public static L2T0TrainingLabelsKey key(L2T0TrainingLabels row) { return row.key(); }
    public static byte[] canonicalBytes(L2T0TrainingLabels row) {
        try { return JSON.writeValueAsBytes(canonicalMap(row)); }
        catch (JsonProcessingException failure) {
            throw new IllegalStateException("Cannot encode a canonical D089 source row", failure);
        }
    }
    public static int estimatedTransportBytes(L2T0TrainingLabels row, byte[] canonical) {
        return Math.addExact(Math.multiplyExact(canonical.length, 8), 512);
    }

    public static void requireIsolatedTableName(String table) {
        DatasetDefinition.identifier(table);
        if (!table.startsWith(ISOLATED_TABLE_PREFIX) || table.length() <= ISOLATED_TABLE_PREFIX.length())
            throw new IllegalStateException("D089 writes require java_d089_l2_t0_training_labels_<suffix>");
    }

    private static Map<String, Object> canonicalMap(L2T0TrainingLabels row) {
        var values = new LinkedHashMap<String, Object>();
        values.put("trade_date", TemporalValues.formatDate(row.tradeDate(), TemporalValues.DateFormat.BASIC));
        values.put("symbol", row.symbol());
        values.put("market", row.market());
        values.put("board", row.board());
        values.put("minute", row.minute().toString());
        for (var field : L2T0TrainingLabelField.values())
            if (field.metric()) values.put(field.fieldName(), row.labels().get(field));
        return values;
    }
}
