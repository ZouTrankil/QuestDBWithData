package com.zoutrankil.data.factor.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.zoutrankil.data.factor.domain.FactorDefinition;
import com.zoutrankil.data.factor.domain.GlobalFactorObservation;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Canonical raw ETF observations from the shared diagnostic panel; source completeness stays separate. */
public final class EtfPanelObservations {
    private static final Set<String> SUPPORTED =
            Set.of(
                    "trend_return_20d",
                    "momentum_return_5d",
                    "technical_rsi_6d",
                    "liquidity_amount_20d",
                    "share_change_5d",
                    "flow_intensity_5d",
                    "flow_persistence_5d",
                    "component_up_breadth_1d",
                    "component_median_return_5d",
                    "component_l2_inflow_breadth",
                    "component_l2_mfi_median",
                    "residual_momentum_20d",
                    "recovery_quality_5d",
                    "component_earnings_outlook",
                    "component_return_dispersion_5d",
                    "component_top10_weight",
                    "market_correlation_shock_20d",
                    "csi_value_reference",
                    "csi_growth_reference",
                    "csi_dividend_reference",
                    "csi_quality_reference",
                    "csi_low_vol_reference",
                    "csi_momentum_reference",
                    "csi_cashflow_reference",
                    "csi_liquidity_reference",
                    "basket_obi1",
                    "basket_obi5",
                    "basket_ofi",
                    "basket_spread",
                    "basket_depth");

    private EtfPanelObservations() {}

    public static List<GlobalFactorObservation> convert(
            JsonNode panel, FactorDefinitionCatalog catalog, LocalDateTime computedAt) {
        if (!"java.etf_shared_panel.v1".equals(panel.path("schema").asText()))
            throw new IllegalArgumentException("ETF shared panel required");
        Objects.requireNonNull(computedAt);
        var from = LocalDate.parse(panel.path("from").asText());
        var through = LocalDate.parse(panel.path("through").asText());
        if (from.isAfter(through)) throw new IllegalArgumentException("Invalid ETF panel interval");
        for (String section :
                List.of(
                        "securities",
                        "components",
                        "technical",
                        "earnings",
                        "csiStyles",
                        "riskContext"))
            if (!panel.path(section).isArray())
                throw new IllegalArgumentException("Missing ETF section: " + section);
        var definitions = new HashMap<String, FactorDefinition>();
        for (var definition : catalog.definitions())
            if (definition.assetType().equals("etf")
                    && definition.enabled()
                    && definition.publishObservations()) {
                if (!definition.sourceGroup().equals("etf_maturity")
                        || !SUPPORTED.contains(definition.sourceColumn())
                        || definitions.putIfAbsent(definition.sourceColumn(), definition) != null)
                    throw new IllegalArgumentException("Unsupported or duplicate ETF binding");
            }
        var result = new ArrayList<GlobalFactorObservation>();
        var aliases =
                Map.of(
                        "ret20",
                        "trend_return_20d",
                        "ret5",
                        "momentum_return_5d",
                        "amount20",
                        "liquidity_amount_20d");
        for (var security : panel.path("securities")) {
            var dates = security.path("dates");
            var columns = security.path("columns");
            if (!dates.isArray() || !columns.isObject())
                throw new IllegalArgumentException("ETF dates and columns required");
            for (String field :
                    List.of(
                            "ret20",
                            "ret5",
                            "amount20",
                            "residual_momentum_20d",
                            "recovery_quality_5d",
                            "share_change_5d",
                            "flow_intensity_5d",
                            "flow_persistence_5d"))
                if (!columns.has(field))
                    throw new IllegalArgumentException("Missing ETF calculation column: " + field);
            var fields = columns.fields();
            while (fields.hasNext()) {
                var field = fields.next();
                String source = aliases.getOrDefault(field.getKey(), field.getKey());
                if (source.equals("component_up_breadth_1d") || !definitions.containsKey(source))
                    continue;
                if (!field.getValue().isArray() || field.getValue().size() != dates.size())
                    throw new IllegalArgumentException("Misaligned ETF output");
                for (int i = 0; i < dates.size(); i++)
                    add(
                            result,
                            definitions,
                            source,
                            security.path("symbol").asText(),
                            dates.get(i).asText(),
                            field.getValue().get(i),
                            computedAt);
            }
        }
        for (String section : List.of("components", "technical", "earnings"))
            for (var row : panel.path(section)) {
                var values = section.equals("components") ? row.path("columns") : row;
                var required =
                        switch (section) {
                            case "components" ->
                                    List.of(
                                            "component_up_breadth_1d",
                                            "component_median_return_5d",
                                            "component_return_dispersion_5d",
                                            "component_top10_weight",
                                            "component_l2_inflow_breadth",
                                            "component_l2_mfi_median",
                                            "basket_obi1",
                                            "basket_obi5",
                                            "basket_ofi",
                                            "basket_spread",
                                            "basket_depth");
                            case "technical" -> List.of("technical_rsi_6d");
                            default -> List.of("component_earnings_outlook");
                        };
                if (!values.isObject())
                    throw new IllegalArgumentException("Invalid ETF section row: " + section);
                for (String field : required)
                    if (definitions.containsKey(field) && !values.has(field))
                        throw new IllegalArgumentException(
                                "Missing ETF calculation column: " + section + "." + field);
                var fields = values.fields();
                while (fields.hasNext()) {
                    var field = fields.next();
                    if (definitions.containsKey(field.getKey()))
                        add(
                                result,
                                definitions,
                                field.getKey(),
                                row.path("symbol").asText(),
                                row.path("date").asText(),
                                field.getValue(),
                                computedAt);
                }
            }
        for (var row : panel.path("csiStyles"))
            add(
                    result,
                    definitions,
                    row.path("factor").asText(),
                    row.path("symbol").asText(),
                    row.path("date").asText(),
                    row.path("value"),
                    computedAt);
        for (var row : panel.path("riskContext"))
            add(
                    result,
                    definitions,
                    "market_correlation_shock_20d",
                    "MARKET.CN",
                    row.path("date").asText(),
                    row.path("correlation_z_60d"),
                    computedAt);
        GlobalObservationCoverage.compare(result, List.of());
        for (var row : result)
            if (row.signalDate().isBefore(from) || row.signalDate().isAfter(through))
                throw new IllegalArgumentException("ETF observation outside requested interval");
        result.sort(
                Comparator.comparing(GlobalFactorObservation::signalDate)
                        .thenComparing(GlobalFactorObservation::factorId)
                        .thenComparing(GlobalFactorObservation::entityId));
        return List.copyOf(result);
    }

    private static void add(
            List<GlobalFactorObservation> result,
            Map<String, FactorDefinition> definitions,
            String source,
            String symbol,
            String date,
            JsonNode value,
            LocalDateTime computedAt) {
        var definition = definitions.get(source);
        if (!SUPPORTED.contains(source))
            throw new IllegalArgumentException("Unmapped ETF output: " + source);
        if (definition == null)
            return; // A known but disabled catalog entry is intentionally not published.
        if (symbol.isBlank()) throw new IllegalArgumentException("Missing ETF observation symbol");
        if (value.isMissingNode()) throw new IllegalArgumentException("Missing ETF value field");
        if (value.isNull()) {
            // Persist an explicit missing result so an incremental revision clears a prior finite
            // value.
            // This is storage state; the finite-value view still matches Python _factor_rows.
            result.add(
                    GlobalFactorObservations.raw(
                            LocalDate.parse(date),
                            symbol,
                            definition,
                            Double.NaN,
                            null,
                            computedAt,
                            "missing",
                            "",
                            null));
            return;
        }
        if (!value.isNumber() || !Double.isFinite(value.doubleValue()) || symbol.isBlank())
            throw new IllegalArgumentException("Invalid ETF observation");
        result.add(
                GlobalFactorObservations.raw(
                        LocalDate.parse(date),
                        symbol,
                        definition,
                        value.doubleValue(),
                        null,
                        computedAt,
                        "ok",
                        "",
                        null));
    }
}
