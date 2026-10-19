package com.zoutrankil.data.etf.mapper;

import com.fasterxml.jackson.databind.JsonNode;
import com.zoutrankil.data.client.dto.TushareEtfBasicDto;
import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.domain.temporal.TemporalValues;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;

/** Strict 25-field fund_basic source mapper plus two explicitly derived storage timestamps. */
public final class EtfBasicMapper {
    public static final List<String> SOURCE_FIELDS = List.of("ts_code", "name", "management", "custodian", "fund_type",
            "found_date", "due_date", "list_date", "issue_date", "delist_date", "issue_amount", "m_fee", "c_fee",
            "duration_year", "p_value", "min_amount", "exp_return", "benchmark", "status", "invest_type", "type",
            "trustee", "purc_startdate", "redm_startdate", "market");
    private static final List<String> DATE_FIELDS = List.of("found_date", "due_date", "list_date", "issue_date",
            "delist_date", "purc_startdate", "redm_startdate");
    private static final Set<String> NULL_DATE_SENTINELS = Set.of("", "Null", "null");
    private static final DateTimeFormatter BASIC = DateTimeFormatter.BASIC_ISO_DATE;

    public TushareEtfBasicDto dto(Map<String, JsonNode> row) {
        if (row == null || !row.keySet().containsAll(SOURCE_FIELDS))
            throw new IllegalArgumentException("All 25 declared fund_basic fields are required in source response");
        return new TushareEtfBasicDto(text(row, "ts_code"), text(row, "name"), text(row, "management"),
                text(row, "custodian"), text(row, "fund_type"), text(row, "found_date"), text(row, "due_date"),
                text(row, "list_date"), text(row, "issue_date"), text(row, "delist_date"), number(row, "issue_amount"),
                number(row, "m_fee"), number(row, "c_fee"), number(row, "duration_year"), number(row, "p_value"),
                number(row, "min_amount"), number(row, "exp_return"), text(row, "benchmark"), text(row, "status"),
                text(row, "invest_type"), text(row, "type"), text(row, "trustee"), text(row, "purc_startdate"),
                text(row, "redm_startdate"), text(row, "market"));
    }

    public EtfBasic fromSource(Map<String, JsonNode> row, Instant observedAt) {
        return fromSource(dto(row), observedAt);
    }

    public EtfBasic fromSource(TushareEtfBasicDto source, Instant observedAt) {
        Objects.requireNonNull(source);
        Objects.requireNonNull(observedAt);
        TemporalValues.requirePrecision(observedAt, TemporalValues.Precision.MICROS);
        if (!"E".equals(source.market())) throw new IllegalArgumentException("fund_basic response escaped market=E");
        if (source.status() != null && !Set.of("D", "I", "L").contains(source.status()))
            throw new IllegalArgumentException("fund_basic returned an undocumented lifecycle status");
        return new EtfBasic(new EtfBasicKey(source.tsCode(), EtfBasicDataset.technicalTimestamp()), source.name(),
                source.management(), source.custodian(), source.fundType(), date(source.foundDate()), date(source.dueDate()),
                date(source.listDate()), date(source.issueDate()), date(source.delistDate()), source.issueAmount(),
                source.managementFee(), source.custodianFee(), source.durationYear(), source.parValue(), source.minimumAmount(),
                source.expectedReturn(), source.benchmark(), source.status(), source.investType(), source.type(), source.trustee(),
                date(source.purchaseStartDate()), date(source.redemptionStartDate()), source.market(), observedAt);
    }

    public DatasetValues values(EtfBasic row) {
        Objects.requireNonNull(row);
        var values = new LinkedHashMap<String,Object>();
        values.put("ts_code", row.tsCode()); values.put("name", row.name()); values.put("management", row.management());
        values.put("custodian", row.custodian()); values.put("fund_type", row.fundType()); values.put("found_date", row.foundDate());
        values.put("due_date", row.dueDate()); values.put("list_date", row.listDate()); values.put("issue_date", row.issueDate());
        values.put("delist_date", row.delistDate()); values.put("issue_amount", row.issueAmount()); values.put("m_fee", row.managementFee());
        values.put("c_fee", row.custodianFee()); values.put("duration_year", row.durationYear()); values.put("p_value", row.parValue());
        values.put("min_amount", row.minimumAmount()); values.put("exp_return", row.expectedReturn()); values.put("benchmark", row.benchmark());
        values.put("status", row.status()); values.put("invest_type", row.investType()); values.put("type", row.type());
        values.put("trustee", row.trustee()); values.put("purc_startdate", row.purchaseStartDate());
        values.put("redm_startdate", row.redemptionStartDate()); values.put("market", row.market());
        values.put("timestamp", new TemporalValues.TechnicalTimestamp(row.timestamp(), EtfBasicDataset.DEFINITION
                .columns().stream().filter(column -> column.logicalName().equals("timestamp")).findFirst().orElseThrow().temporal().meaning()));
        values.put("update_time", row.updateTime());
        if (values.size() != EtfBasicDataset.DEFINITION.columns().size())
            throw new IllegalStateException("etf_basic mapping must cover all 27 physical fields");
        return new DatasetValues(values);
    }

    public EtfBasic fromValues(DatasetValues values) {
        Objects.requireNonNull(values);
        var technical = values.get("timestamp", TemporalValues.TechnicalTimestamp.class);
        return new EtfBasic(new EtfBasicKey(values.get("ts_code", String.class), technical.storageCarrier()),
                values.get("name", String.class), values.get("management", String.class), values.get("custodian", String.class),
                values.get("fund_type", String.class), values.get("found_date", LocalDate.class), values.get("due_date", LocalDate.class),
                values.get("list_date", LocalDate.class), values.get("issue_date", LocalDate.class), values.get("delist_date", LocalDate.class),
                values.get("issue_amount", Double.class), values.get("m_fee", Double.class), values.get("c_fee", Double.class),
                values.get("duration_year", Double.class), values.get("p_value", Double.class), values.get("min_amount", Double.class),
                values.get("exp_return", Double.class), values.get("benchmark", String.class), values.get("status", String.class),
                values.get("invest_type", String.class), values.get("type", String.class), values.get("trustee", String.class),
                values.get("purc_startdate", LocalDate.class), values.get("redm_startdate", LocalDate.class),
                values.get("market", String.class), values.get("update_time", Instant.class));
    }

    public static String sourceText(Map<String, JsonNode> row, String field) { return text(row, field); }
    private static String text(Map<String, JsonNode> row, String field) {
        JsonNode value = row.get(field);
        if (value == null || value.isNull()) return null;
        if (!value.isTextual()) throw new IllegalArgumentException("Text fund_basic source field required: " + field);
        return value.textValue();
    }
    private static Double number(Map<String, JsonNode> row, String field) {
        JsonNode value = row.get(field);
        if (value == null || value.isNull()) return null;
        if (!value.isNumber() && !value.isTextual()) throw new IllegalArgumentException("Numeric fund_basic source field required: " + field);
        String raw = value.asText();
        if (raw.isBlank()) throw new IllegalArgumentException("Empty nonnull numeric fund_basic value: " + field);
        double parsed;
        try { parsed = new BigDecimal(raw).doubleValue(); }
        catch (NumberFormatException invalid) { throw new IllegalArgumentException("Invalid fund_basic number: " + field, invalid); }
        if (!Double.isFinite(parsed)) throw new IllegalArgumentException("Non-finite fund_basic number: " + field);
        return parsed;
    }
    private static LocalDate date(String raw) {
        if (raw == null || NULL_DATE_SENTINELS.contains(raw)) return null;
        return TemporalValues.businessDate(raw, TemporalValues.DateFormat.BASIC);
    }
    public static String basicDate(LocalDate value) { return value == null ? null : value.format(BASIC); }
}
