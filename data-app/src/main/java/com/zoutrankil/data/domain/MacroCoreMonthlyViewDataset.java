package com.zoutrankil.data.domain;

import java.util.List;
import java.util.Set;
import static com.zoutrankil.data.domain.DatasetDefinition.*;

/** D105's exact SELECT * alias is a read interface to D104, without another materializer. */
public final class MacroCoreMonthlyViewDataset {
    private MacroCoreMonthlyViewDataset() {}
    public static final String FORMAL_OBJECT = "v_macro_core_monthly";
    public static final String ISOLATED_OBJECT = "java_d105_v_macro_core_monthly_acceptance";
    public static final String BASE_REFRESH_JOB_ID = "data.macro_core_monthly";
    public static final int BASE_REFRESH_JOB_VERSION = 1;
    public static final List<String> STORAGE_COLUMNS = List.of("month", "cpi_yoy", "ppi_yoy", "pmi_mfg",
            "gdp_yoy", "m2_yoy", "social_financing_stock", "new_rmb_loan", "social_financing_yoy");
    public static final DatasetDefinition DEFINITION = definition(FORMAL_OBJECT);

    public static DatasetDefinition definition(String objectName) {
        requireViewObject(objectName);
        return new DatasetDefinition(FORMAL_OBJECT, 1, "derived.macro_core_monthly.alias",
                "schema.monthly_views.deployment_only", objectName, ObjectKind.VIEW, columns(),
                List.of("month"), List.of(), "month", Partition.NONE, false,
                Set.of(Capability.READ), List.of("macro_core_monthly"),
                "Ordinary SELECT * FROM macro_core_monthly alias with nine exact base fields. "
                + "No filters, joins, aggregation, deduplication, filling, scaling or rounding. "
                + "Month is the base observation period at its exact first calendar day UTC midnight; not a publication instant. "
                + "Eight nullable finite DOUBLEs preserve base units, NULL and signed zero, including historical required-source NULLs. "
                + "GDP has no forward fill; legacy new_rmb_loan denotes total social-financing increment; "
                + "social-financing YoY remains a fractional ratio with no multiplication by 100. "
                + "An ordinary view has no physical partition, WAL, UPSERT key, direct writer or checkpoint. "
                + "Canonical source refresh delegates to data.macro_core_monthly v1; no competing view sync job is registered. "
                + "Reads require all nine columns, a first-day month equality or at most twelve increasing months, "
                + "pages of at most twelve rows, and the exact view SQL plus stable base physical/WAL/schema token. "
                + "Only the formal alias or explicit D105 private alias is admitted. "
                + "No provider completeness, full history, automatic formal refresh or consumer cutover is certified.");
    }
    public static void requireViewObject(String objectName) {
        DatasetDefinition.identifier(objectName);
        if (!FORMAL_OBJECT.equals(objectName) && !ISOLATED_OBJECT.equals(objectName))
            throw new IllegalArgumentException("Exact formal or D105 isolated macro view required");
    }
    public static List<Column> columns() {
        return List.of(new Column("macro_core_monthly.month", "month", "month", StorageType.TIMESTAMP,
                false, "Base observation-month identity, exact first-day UTC midnight",
                new TemporalContract(TemporalKind.BUSINESS_DATE, "BASIC", "calendar", "MONTH",
                        "Base YYYYMM observation becomes first-day LocalDate; not a publication instant")),
                metric("cpi_yoy", "Base CPI YoY; unchanged stored value, source declares percent"),
                metric("ppi_yoy", "Base PPI YoY; unchanged stored value"),
                metric("pmi_mfg", "Base manufacturing PMI level; unchanged stored value"),
                metric("gdp_yoy", "Base GDP YoY in report-date own month only, nullable without forward fill"),
                metric("m2_yoy", "Base M2 YoY; unchanged stored value, source declares percent"),
                metric("social_financing_stock", "Base total social-financing stock; unchanged stored value, local source declares 万亿元"),
                metric("new_rmb_loan", "Compatibility name for base total social-financing monthly increment; local source declares 亿元"),
                metric("social_financing_yoy", "Base fractional stock change to prior twelfth observation; nullable, no percent scaling"));
    }
    private static Column metric(String field, String meaning) {
        return new Column("macro_core_monthly." + field, field, field, StorageType.DOUBLE, true, meaning, null);
    }
}
