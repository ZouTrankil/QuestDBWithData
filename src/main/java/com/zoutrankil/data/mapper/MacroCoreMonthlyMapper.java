package com.zoutrankil.data.mapper;

import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.domain.table.MacroCoreMonthlyRow;
import java.time.LocalDate;
import java.util.*;

/** Explicit nine-field mapping; generated storage projection remains unmodified. */
public final class MacroCoreMonthlyMapper {
    public List<String> columns() { return MacroCoreMonthlyDataset.STORAGE_COLUMNS; }
    public MacroCoreMonthly fromStorage(MacroCoreMonthlyRow row) {
        Objects.requireNonNull(row, "macro storage row required");
        return new MacroCoreMonthly(MacroCoreMonthlyKey.fromStorage(row.month()).month(), row.cpiYoy(),
                row.ppiYoy(), row.pmiMfg(), row.gdpYoy(), row.m2Yoy(), row.socialFinancingStock(),
                row.newRmbLoan(), row.socialFinancingYoy());
    }
    public MacroCoreMonthlyRow toStorage(MacroCoreMonthly row) {
        Objects.requireNonNull(row, "macro row required");
        return new MacroCoreMonthlyRow(row.key().storageCarrier(), row.cpiYoy(), row.ppiYoy(), row.pmiMfg(),
                row.gdpYoy(), row.m2Yoy(), row.socialFinancingStock(), row.newRmbLoan(), row.socialFinancingYoy());
    }
    public DatasetValues values(MacroCoreMonthly row) {
        Objects.requireNonNull(row, "macro row required");
        var values = new LinkedHashMap<String, Object>();
        values.put("month", row.key().storageDate()); values.put("cpi_yoy", row.cpiYoy());
        values.put("ppi_yoy", row.ppiYoy()); values.put("pmi_mfg", row.pmiMfg());
        values.put("gdp_yoy", row.gdpYoy()); values.put("m2_yoy", row.m2Yoy());
        values.put("social_financing_stock", row.socialFinancingStock()); values.put("new_rmb_loan", row.newRmbLoan());
        values.put("social_financing_yoy", row.socialFinancingYoy());
        return new DatasetValues(values);
    }
    public MacroCoreMonthly fromValues(Map<String, Object> values) { return fromValues(new DatasetValues(values)); }
    public MacroCoreMonthly fromValues(DatasetValues values) {
        Objects.requireNonNull(values, "macro values required");
        if (!values.columns().equals(new LinkedHashSet<>(columns())))
            throw new IllegalArgumentException("Exactly the nine macro-core columns required");
        return new MacroCoreMonthly(MacroCoreMonthlyKey.fromDate(values.get("month", LocalDate.class)).month(),
                values.get("cpi_yoy", Double.class), values.get("ppi_yoy", Double.class),
                values.get("pmi_mfg", Double.class), values.get("gdp_yoy", Double.class),
                values.get("m2_yoy", Double.class), values.get("social_financing_stock", Double.class),
                values.get("new_rmb_loan", Double.class), values.get("social_financing_yoy", Double.class));
    }
}
