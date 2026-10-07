package com.zoutrankil.data.derived.mapper;

import com.zoutrankil.data.mapper.*;

import com.zoutrankil.data.domain.*;
import java.time.LocalDate;
import java.util.*;

/** All nine view columns map explicitly; the generated projection is preserved. */
public final class MacroCoreMonthlyViewMapper {
    public List<String> columns() { return MacroCoreMonthlyViewDataset.STORAGE_COLUMNS; }
    public MacroCoreMonthlyView fromStorage(com.zoutrankil.data.domain.view.MacroCoreMonthlyView row) {
        Objects.requireNonNull(row, "macro view projection required");
        return new MacroCoreMonthlyView(MacroCoreMonthlyViewKey.fromStorage(row.month()).month(),
                row.cpiYoy(), row.ppiYoy(), row.pmiMfg(), row.gdpYoy(), row.m2Yoy(),
                row.socialFinancingStock(), row.newRmbLoan(), row.socialFinancingYoy());
    }
    public com.zoutrankil.data.domain.view.MacroCoreMonthlyView toStorage(MacroCoreMonthlyView row) {
        Objects.requireNonNull(row, "macro view row required");
        return new com.zoutrankil.data.domain.view.MacroCoreMonthlyView(row.key().storageCarrier(),
                row.cpiYoy(), row.ppiYoy(), row.pmiMfg(), row.gdpYoy(), row.m2Yoy(),
                row.socialFinancingStock(), row.newRmbLoan(), row.socialFinancingYoy());
    }
    public DatasetValues values(MacroCoreMonthlyView row) {
        Objects.requireNonNull(row, "macro view row required");
        var values = new LinkedHashMap<String, Object>();
        values.put("month", row.key().storageDate()); values.put("cpi_yoy", row.cpiYoy());
        values.put("ppi_yoy", row.ppiYoy()); values.put("pmi_mfg", row.pmiMfg());
        values.put("gdp_yoy", row.gdpYoy()); values.put("m2_yoy", row.m2Yoy());
        values.put("social_financing_stock", row.socialFinancingStock()); values.put("new_rmb_loan", row.newRmbLoan());
        values.put("social_financing_yoy", row.socialFinancingYoy());
        return new DatasetValues(values);
    }
    public MacroCoreMonthlyView fromValues(Map<String, Object> values) { return fromValues(new DatasetValues(values)); }
    public MacroCoreMonthlyView fromValues(DatasetValues values) {
        Objects.requireNonNull(values, "macro view values required");
        if (!values.columns().equals(new LinkedHashSet<>(columns())))
            throw new IllegalArgumentException("Exactly the nine macro view columns required");
        return new MacroCoreMonthlyView(MacroCoreMonthlyViewKey.fromDate(values.get("month", LocalDate.class)).month(),
                values.get("cpi_yoy", Double.class), values.get("ppi_yoy", Double.class),
                values.get("pmi_mfg", Double.class), values.get("gdp_yoy", Double.class),
                values.get("m2_yoy", Double.class), values.get("social_financing_stock", Double.class),
                values.get("new_rmb_loan", Double.class), values.get("social_financing_yoy", Double.class));
    }
}
