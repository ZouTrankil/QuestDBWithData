package com.zoutrankil.questdbwithdata.client.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.zoutrankil.questdbwithdata.domain.L2T0TrainingLabelField;
import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.EnumMap;
import java.util.List;

/** Explicit 62-column D089 Parquet DTO before timezone and business-key normalization. */
public record L2T0TrainingLabelsParquetDto(LocalDate tradeDate, String symbol, String market,
        String board, LocalDateTime localMinute,
        EnumMap<L2T0TrainingLabelField, Object> labels) {
    private static final DateTimeFormatter BASIC_DATE = DateTimeFormatter.BASIC_ISO_DATE;

    public static L2T0TrainingLabelsParquetDto decode(JsonNode row, LocalDate expectedDate) throws IOException {
        if (row == null || !row.isObject() || row.size() != L2T0TrainingLabelField.values().length)
            throw new IOException("D089 Parquet row does not match the frozen 62-column schema");
        String rawDate = text(row, L2T0TrainingLabelField.TRADE_DATE);
        if (!rawDate.matches("[0-9]{8}")) throw new IOException("D089 trade_date must use YYYYMMDD");
        final LocalDate day;
        try { day = LocalDate.parse(rawDate, BASIC_DATE); }
        catch (DateTimeParseException failure) { throw new IOException("D089 trade_date is invalid", failure); }
        if (!day.equals(expectedDate)) throw new IOException("D089 row date differs from its source partition");
        String symbol = text(row, L2T0TrainingLabelField.SYMBOL);
        String market = text(row, L2T0TrainingLabelField.MARKET);
        String board = text(row, L2T0TrainingLabelField.BOARD);
        JsonNode minuteNode = row.get("minute");
        if (minuteNode == null || !minuteNode.isTextual() || minuteNode.textValue().isBlank())
            throw new IOException("D089 minute is missing");
        final LocalDateTime localMinute;
        try { localMinute = LocalDateTime.parse(minuteNode.textValue()); }
        catch (DateTimeParseException failure) {
            throw new IOException("D089 minute must be a timezone-naive local Parquet timestamp", failure);
        }
        if (!localMinute.toLocalDate().equals(day) || localMinute.getSecond() != 0 || localMinute.getNano() != 0)
            throw new IOException("D089 minute must be a whole minute within trade_date");
        var labels = new EnumMap<L2T0TrainingLabelField, Object>(L2T0TrainingLabelField.class);
        for (var field : L2T0TrainingLabelField.values())
            if (field.metric()) labels.put(field, field.decode(row.get(field.fieldName())));
        validateSourceSemantics(labels);
        return new L2T0TrainingLabelsParquetDto(day, symbol, market, board, localMinute, labels);
    }

    private static void validateSourceSemantics(EnumMap<L2T0TrainingLabelField, Object> labels) throws IOException {
        if (!List.of("quote_depth_ok", "wide_spread_or_thin_depth")
                .contains(labels.get(L2T0TrainingLabelField.EXECUTABILITY_REASON)))
            throw new IOException("D089 executability_reason differs from the Python source rule");
        long executable = (Long) labels.get(L2T0TrainingLabelField.EXECUTABILITY_LABEL);
        if (executable != 0L && executable != 1L)
            throw new IOException("D089 executability_label must be binary");
        String expectedReason = executable == 1L ? "quote_depth_ok" : "wide_spread_or_thin_depth";
        if (!expectedReason.equals(labels.get(L2T0TrainingLabelField.EXECUTABILITY_REASON)))
            throw new IOException("D089 executability_label and reason disagree");
        if (!"rule_data_missing".equals(labels.get(L2T0TrainingLabelField.POLICY_LABEL))
                || !"needs_inventory_limit_st_price_cage_inputs".equals(labels.get(L2T0TrainingLabelField.POLICY_REASON))
                || !"sell_first_inventory_required".equals(labels.get(L2T0TrainingLabelField.PRIMARY_T0_SIDE)))
            throw new IOException("D089 policy placeholders differ from the Python source contract");

        double commission = (Double) labels.get(L2T0TrainingLabelField.COMMISSION_RATE);
        double tax = (Double) labels.get(L2T0TrainingLabelField.STAMP_TAX_RATE);
        double slippage = (Double) labels.get(L2T0TrainingLabelField.SLIPPAGE_BPS);
        double cost = (Double) labels.get(L2T0TrainingLabelField.ROUNDTRIP_COST_RATE);
        double expectedCost = (2.0 * commission) + tax + (slippage / 10_000.0);
        if (Math.abs(cost - expectedCost) > 1e-12)
            throw new IOException("D089 roundtrip_cost_rate differs from the Python T0CostConfig formula");

        for (String horizon : List.of("1m", "3m", "5m", "10m", "15m", "30m")) {
            validateAlphaPair(labels, horizon, "SELL_FIRST_GROSS_ALPHA_", "SELL_FIRST_NET_ALPHA_",
                    "SELL_FIRST_OPPORTUNITY_LABEL_", cost);
            validateAlphaPair(labels, horizon, "BUY_FIRST_AUX_GROSS_ALPHA_", "BUY_FIRST_AUX_NET_ALPHA_",
                    "BUY_FIRST_AUX_OPPORTUNITY_LABEL_", cost);
        }
    }

    private static void validateAlphaPair(EnumMap<L2T0TrainingLabelField, Object> labels, String horizon,
            String grossPrefix, String netPrefix, String labelPrefix, double cost) throws IOException {
        var grossField = L2T0TrainingLabelField.named(grossPrefix.toLowerCase(java.util.Locale.ROOT) + horizon);
        var netField = L2T0TrainingLabelField.named(netPrefix.toLowerCase(java.util.Locale.ROOT) + horizon);
        var labelField = L2T0TrainingLabelField.named(labelPrefix.toLowerCase(java.util.Locale.ROOT) + horizon);
        Double gross = (Double) labels.get(grossField);
        Double net = (Double) labels.get(netField);
        Long opportunity = (Long) labels.get(labelField);
        if (gross == null) {
            if (net != null || opportunity != 0L)
                throw new IOException("D089 missing gross alpha must have null net alpha and opportunity label 0");
        } else if (net == null || Math.abs(net - (gross - cost)) > 1e-12
                || opportunity != (gross > cost ? 1L : 0L)) {
            throw new IOException("D089 net alpha or opportunity label differs from the Python threshold rule at " + horizon);
        }
    }

    private static String text(JsonNode row, L2T0TrainingLabelField field) throws IOException {
        Object value = field.decode(row.get(field.fieldName()));
        if (!(value instanceof String text) || text.isBlank())
            throw new IOException("D089 required source text is missing: " + field.fieldName());
        return text;
    }
}
