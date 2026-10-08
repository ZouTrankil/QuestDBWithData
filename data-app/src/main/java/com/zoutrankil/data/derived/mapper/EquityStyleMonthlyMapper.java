package com.zoutrankil.data.derived.mapper;


import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.domain.table.EquityStyleMonthlyRow;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Complete 30-column mapping with no percentage conversion, rounding or null filling. */
public final class EquityStyleMonthlyMapper {
    public List<String> columns() { return EquityStyleMonthlyDataset.STORAGE_COLUMNS; }
    public EquityStyleMonthly fromStorage(EquityStyleMonthlyRow row) {
        Objects.requireNonNull(row, "equity style storage row required");
        return new EquityStyleMonthly(EquityStyleMonthlyKey.fromStorage(row.month()).month(),
                row.hs300Ret1m(),
                row.zz500Ret1m(),
                row.allARet1m(),
                row.cs1000Ret1m(),
                row.smallLargeRet1m(),
                row.midLargeRet1m(),
                row.growthRet1m(),
                row.valueRet1m(),
                row.growthValueRet1m(),
                row.energyRet1m(),
                row.materialsRet1m(),
                row.industrialsRet1m(),
                row.consumerDiscretionaryRet1m(),
                row.consumerStaplesRet1m(),
                row.healthcareRet1m(),
                row.financialsRet1m(),
                row.itRet1m(),
                row.telecomRet1m(),
                row.utilitiesRet1m(),
                row.energyVsAllA1m(),
                row.materialsVsAllA1m(),
                row.industrialsVsAllA1m(),
                row.consumerDiscretionaryVsAllA1m(),
                row.consumerStaplesVsAllA1m(),
                row.healthcareVsAllA1m(),
                row.financialsVsAllA1m(),
                row.itVsAllA1m(),
                row.telecomVsAllA1m(),
                row.utilitiesVsAllA1m());
    }
    public EquityStyleMonthlyRow toStorage(EquityStyleMonthly row) {
        Objects.requireNonNull(row, "equity style row required");
        return new EquityStyleMonthlyRow(row.key().storageCarrier(),
                row.hs300Ret1m(),
                row.zz500Ret1m(),
                row.allARet1m(),
                row.cs1000Ret1m(),
                row.smallLargeRet1m(),
                row.midLargeRet1m(),
                row.growthRet1m(),
                row.valueRet1m(),
                row.growthValueRet1m(),
                row.energyRet1m(),
                row.materialsRet1m(),
                row.industrialsRet1m(),
                row.consumerDiscretionaryRet1m(),
                row.consumerStaplesRet1m(),
                row.healthcareRet1m(),
                row.financialsRet1m(),
                row.itRet1m(),
                row.telecomRet1m(),
                row.utilitiesRet1m(),
                row.energyVsAllA1m(),
                row.materialsVsAllA1m(),
                row.industrialsVsAllA1m(),
                row.consumerDiscretionaryVsAllA1m(),
                row.consumerStaplesVsAllA1m(),
                row.healthcareVsAllA1m(),
                row.financialsVsAllA1m(),
                row.itVsAllA1m(),
                row.telecomVsAllA1m(),
                row.utilitiesVsAllA1m());
    }
    public DatasetValues values(EquityStyleMonthly row) {return com.zoutrankil.data.derived.domain.EquityStyleMonthlyRows.values(row); }
    public EquityStyleMonthly fromValues(Map<String, Object> values) { return fromValues(new DatasetValues(values)); }
    public EquityStyleMonthly fromValues(DatasetValues values) {
        Objects.requireNonNull(values, "equity style values required");
        if (!values.columns().equals(new java.util.LinkedHashSet<>(columns())))
            throw new IllegalArgumentException("Exactly the 30 equity style columns required");
        return new EquityStyleMonthly(EquityStyleMonthlyKey.fromDate(values.get("month", LocalDate.class)).month(),
                values.get("hs300_ret_1m", Double.class),
                values.get("zz500_ret_1m", Double.class),
                values.get("all_a_ret_1m", Double.class),
                values.get("cs1000_ret_1m", Double.class),
                values.get("small_large_ret_1m", Double.class),
                values.get("mid_large_ret_1m", Double.class),
                values.get("growth_ret_1m", Double.class),
                values.get("value_ret_1m", Double.class),
                values.get("growth_value_ret_1m", Double.class),
                values.get("energy_ret_1m", Double.class),
                values.get("materials_ret_1m", Double.class),
                values.get("industrials_ret_1m", Double.class),
                values.get("consumer_discretionary_ret_1m", Double.class),
                values.get("consumer_staples_ret_1m", Double.class),
                values.get("healthcare_ret_1m", Double.class),
                values.get("financials_ret_1m", Double.class),
                values.get("it_ret_1m", Double.class),
                values.get("telecom_ret_1m", Double.class),
                values.get("utilities_ret_1m", Double.class),
                values.get("energy_vs_all_a_1m", Double.class),
                values.get("materials_vs_all_a_1m", Double.class),
                values.get("industrials_vs_all_a_1m", Double.class),
                values.get("consumer_discretionary_vs_all_a_1m", Double.class),
                values.get("consumer_staples_vs_all_a_1m", Double.class),
                values.get("healthcare_vs_all_a_1m", Double.class),
                values.get("financials_vs_all_a_1m", Double.class),
                values.get("it_vs_all_a_1m", Double.class),
                values.get("telecom_vs_all_a_1m", Double.class),
                values.get("utilities_vs_all_a_1m", Double.class));
    }
}

