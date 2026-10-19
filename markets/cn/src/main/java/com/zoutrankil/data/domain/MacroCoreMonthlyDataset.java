package com.zoutrankil.data.domain;

import java.util.List;
import java.util.Set;
import static com.zoutrankil.data.domain.DatasetDefinition.*;

/** D104 alone; source readiness and provider ingestion are separate from this derived output. */
public final class MacroCoreMonthlyDataset {
    private MacroCoreMonthlyDataset() {}
    public static final List<String> STORAGE_COLUMNS = List.of("month", "cpi_yoy", "ppi_yoy", "pmi_mfg",
            "gdp_yoy", "m2_yoy", "social_financing_stock", "new_rmb_loan", "social_financing_yoy");
    public static final List<String> REQUIRED_MONTHLY_FIELDS = List.of("cpi_yoy", "ppi_yoy", "pmi_mfg",
            "m2_yoy", "social_financing_stock", "new_rmb_loan");
    public static final List<String> SOURCE_TABLES = List.of("cn_cpi", "cn_ppi", "cn_pmi", "cn_m", "cn_gdp", "sf_month");
    public static final DatasetDefinition DEFINITION = definition("macro_core_monthly");
    public static DatasetDefinition definition(String table) {
        DatasetDefinition.identifier(table);
        return new DatasetDefinition("macro_core_monthly", 1, "derived.macro.six_source_monthly",
                "java.macro_core_monthly.bounded_materializer", table, ObjectKind.TABLE, columns(),
                List.of("month"), List.of("month"), "month", Partition.YEAR, true,
                Set.of(Capability.READ, Capability.WRITE), List.of(),
                "YYYYMM observation period at the first calendar day at exact UTC midnight, not publication time. "
                + "Eight nullable finite DOUBLEs retain stored units, nulls and signed zero without scaling or rounding. "
                + "New publication requires all six monthly fields; GDP and social-financing YoY remain optional. "
                + "GDP belongs only to its report_date month, with no forward fill. "
                + "Legacy new_rmb_loan names total social-financing increment from sf_month.inc_month. "
                + "Social-financing YoY is a fractional ratio to the prior twelfth observation, with no multiplication by 100. "
                + "Six governed physical inputs are cn_cpi, cn_ppi, cn_pmi, cn_m, cn_gdp and sf_month; "
                + "their Java Dataset/job owners are not registered, so no implemented upstream graph edges are declared. "
                + "Only isolated java_d104_macro_core_monthly_<suffix> targets and bounded 12-month reads/batches are admitted. "
                + "No formal replacement, provider readiness, automatic source refresh, full history or consumer cutover is implied.");
    }
    public static List<Column> columns() {
        return List.of(new Column("source.month:YYYYMM / cn_gdp.report_date:own_month", "month", "month",
                StorageType.TIMESTAMP, false, "Observation-period identity at exact first-day UTC midnight",
                new TemporalContract(TemporalKind.BUSINESS_DATE, "BASIC", "calendar", "MONTH",
                        "Original YYYYMM period becomes a first-day LocalDate; not a publication instant")),
                metric("cn_cpi.nt_yoy", "cpi_yoy", "CPI national YoY; unchanged stored upstream value (source declares percent)"),
                metric("cn_ppi.ppi_yoy", "ppi_yoy", "PPI YoY; unchanged stored upstream value"),
                metric("cn_pmi.pmi010000", "pmi_mfg", "Manufacturing PMI level; unchanged stored upstream value"),
                metric("cn_gdp.gdp_yoy[report_date:own_month]", "gdp_yoy", "GDP YoY in its report-date month only, nullable without carry; unchanged stored value"),
                metric("cn_m.m2_yoy", "m2_yoy", "M2 YoY; unchanged stored upstream value"),
                metric("sf_month.stk_endval", "social_financing_stock", "Total social-financing closing stock; unchanged stored value (local source specification declares 万亿元)"),
                metric("sf_month.inc_month", "new_rmb_loan", "Compatibility name for total social-financing monthly increment, not a distinct RMB-loan series; unchanged stored value (local specification declares 亿元)"),
                metric("derived:(current_stk_endval/prior_12th_observation_stk_endval)-1", "social_financing_yoy",
                        "Fractional stock change: current stock / prior twelfth sorted observation - 1; nullable absent/null baseline, no percent scaling"));
    }
    private static Column metric(String source, String field, String meaning) {
        return new Column(source, field, field, StorageType.DOUBLE, true, meaning, null);
    }
}
