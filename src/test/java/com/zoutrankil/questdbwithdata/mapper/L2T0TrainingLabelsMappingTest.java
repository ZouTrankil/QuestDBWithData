package com.zoutrankil.questdbwithdata.mapper;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zoutrankil.questdbwithdata.domain.DatasetDefinition;
import com.zoutrankil.questdbwithdata.domain.JobDefinitionJson;
import com.zoutrankil.questdbwithdata.domain.L2T0TrainingLabelField;
import com.zoutrankil.questdbwithdata.domain.L2T0TrainingLabelsDataset;
import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class L2T0TrainingLabelsMappingTest {
    private static final LocalDate DAY = LocalDate.parse("2026-09-21");
    private static final Instant MINUTE = DAY.atTime(9, 15).atZone(ZoneId.of("Asia/Shanghai")).toInstant();
    private static final ObjectMapper JSON = JobDefinitionJson.mapper();

    @Test
    void freezesAll62ColumnsAndSymbolMinuteKey() throws Exception {
        var mapper = new L2T0TrainingLabelsMapper();
        var row = mapper.fromParquet(sourceRow(), DAY);
        assertEquals(62, L2T0TrainingLabelField.values().length);
        assertEquals(62, L2T0TrainingLabelsMapper.columns().size());
        assertEquals(List.of("symbol", "minute"), L2T0TrainingLabelsDataset.DEFINITION.businessKey());
        assertEquals(List.of("symbol", "minute"), L2T0TrainingLabelsDataset.DEFINITION.dedupKey());
        assertEquals("minute", L2T0TrainingLabelsDataset.DEFINITION.designatedTimestamp());
        assertEquals(DatasetDefinition.Partition.DAY, L2T0TrainingLabelsDataset.DEFINITION.partition());
        assertTrue(L2T0TrainingLabelsDataset.DEFINITION.wal());
        assertEquals(MINUTE, row.minute());
        assertEquals(1L, row.label("sell_first_opportunity_label_1m"));
        assertNull(row.label("sell_first_gross_alpha_30m"));
        var roundTrip = mapper.fromValues(mapper.values(row));
        assertEquals(row, roundTrip);
        assertEquals(row.key(), roundTrip.key());
        assertEquals(DAY, mapper.values(row).get("trade_date", LocalDate.class));
    }

    @Test
    void rejectsOffsetTimestampsDateDriftAndInvalidSourceSemantics() throws Exception {
        var mapper = new L2T0TrainingLabelsMapper();
        var offset = sourceRow(); offset.put("minute", "2026-09-21T09:15:00+08:00");
        assertThrows(IOException.class, () -> mapper.fromParquet(offset, DAY));
        var drift = sourceRow(); drift.put("trade_date", "20260922");
        assertThrows(IOException.class, () -> mapper.fromParquet(drift, LocalDate.parse("2026-09-22")));
        var missing = sourceRow(); missing.remove("symbol");
        assertThrows(IOException.class, () -> mapper.fromParquet(missing, DAY));
        var fractionalLabel = sourceRow(); fractionalLabel.put("sell_first_opportunity_label_1m", 0.5d);
        assertThrows(IOException.class, () -> mapper.fromParquet(fractionalLabel, DAY));
        var badPolicy = sourceRow(); badPolicy.put("policy_label", "approved");
        assertThrows(IOException.class, () -> mapper.fromParquet(badPolicy, DAY));
        var badNet = sourceRow(); badNet.put("sell_first_net_alpha_1m", 0.5d);
        assertThrows(IOException.class, () -> mapper.fromParquet(badNet, DAY));
    }

    @Test
    void completeKeyRequiresCanonicalSymbolAndWholeMinute() {
        assertThrows(IllegalArgumentException.class,
                () -> new com.zoutrankil.questdbwithdata.domain.L2T0TrainingLabelsKey("000001", MINUTE));
        assertThrows(IllegalArgumentException.class,
                () -> new com.zoutrankil.questdbwithdata.domain.L2T0TrainingLabelsKey("000001.SZ", MINUTE.plusSeconds(1)));
        assertNotEquals(new com.zoutrankil.questdbwithdata.domain.L2T0TrainingLabelsKey("000001.SZ", MINUTE),
                new com.zoutrankil.questdbwithdata.domain.L2T0TrainingLabelsKey("000001.SZ", MINUTE.plusSeconds(60)));
    }

    private static ObjectNode sourceRow() {
        var row = JSON.createObjectNode();
        for (var field : L2T0TrainingLabelField.values()) {
            String name = field.fieldName();
            switch (field) {
                case TRADE_DATE -> row.put(name, "20260921");
                case SYMBOL -> row.put(name, "000001.SZ");
                case MARKET -> row.put(name, "SZ");
                case BOARD -> row.put(name, "MAIN");
                case MINUTE -> row.put(name, "2026-09-21T09:15:00");
                case EXECUTABILITY_LABEL -> row.put(name, 1L);
                case EXECUTABILITY_REASON -> row.put(name, "quote_depth_ok");
                case POLICY_LABEL -> row.put(name, "rule_data_missing");
                case POLICY_REASON -> row.put(name, "needs_inventory_limit_st_price_cage_inputs");
                case PRIMARY_T0_SIDE -> row.put(name, "sell_first_inventory_required");
                case ROUNDTRIP_COST_RATE -> row.put(name, 0.0016d);
                case STAMP_TAX_RATE -> row.put(name, 0.0005d);
                case COMMISSION_RATE -> row.put(name, 0.0003d);
                case SLIPPAGE_BPS -> row.put(name, 5.0d);
                default -> {
                    if (field.storageType() == DatasetDefinition.StorageType.LONG) {
                        row.put(name, 1L);
                    } else if (field.storageType() == DatasetDefinition.StorageType.DOUBLE) {
                        if (name.contains("alpha") && name.endsWith("30m")) row.putNull(name);
                        else if (name.contains("net_alpha")) row.put(name, 0.0004d);
                        else if (name.contains("gross_alpha")) row.put(name, 0.002d);
                        else row.put(name, 0.001d);
                    } else throw new AssertionError("Unexpected D089 field: " + name);
                }
            }
        }
        row.put("sell_first_opportunity_label_30m", 0L);
        row.put("buy_first_aux_opportunity_label_30m", 0L);
        return row;
    }
}
