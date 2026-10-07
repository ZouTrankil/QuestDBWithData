package com.zoutrankil.data.derived.domain;
import com.zoutrankil.data.domain.*;
import java.time.*;
import java.util.*;
import java.io.*;

/** Pure ordered projection, canonical encoding and publication admission. */
public final class MacroCoreMonthlyRows {
    private MacroCoreMonthlyRows() {}
    public static final int MAX_ROWS=12,MAX_BYTES=1024*1024;
    public static DatasetValues values(MacroCoreMonthly row) {
        Objects.requireNonNull(row, "macro row required");
        var values = new LinkedHashMap<String, Object>();
        values.put("month", row.key().storageDate()); values.put("cpi_yoy", row.cpiYoy());
        values.put("ppi_yoy", row.ppiYoy()); values.put("pmi_mfg", row.pmiMfg());
        values.put("gdp_yoy", row.gdpYoy()); values.put("m2_yoy", row.m2Yoy());
        values.put("social_financing_stock", row.socialFinancingStock()); values.put("new_rmb_loan", row.newRmbLoan());
        values.put("social_financing_yoy", row.socialFinancingYoy());
        return new DatasetValues(values);
    }
    public static YearMonth key(MacroCoreMonthly row) { return Objects.requireNonNull(row, "row required").month(); }
    public static byte[] canonicalBytes(MacroCoreMonthly row) {
            Objects.requireNonNull(row, "row required");
            try {
                var bytes = new ByteArrayOutputStream(); var out = new DataOutputStream(bytes);
                out.writeInt(row.month().getYear()); out.writeByte(row.month().getMonthValue());
                var values = values(row).asMap();
                int nulls = 0;
                for (int i = 1; i < MacroCoreMonthlyDataset.STORAGE_COLUMNS.size(); i++)
                    if (values.get(MacroCoreMonthlyDataset.STORAGE_COLUMNS.get(i)) == null) nulls |= 1 << (i - 1);
                out.writeInt(nulls);
                for (int i = 1; i < MacroCoreMonthlyDataset.STORAGE_COLUMNS.size(); i++) {
                    Double value = (Double) values.get(MacroCoreMonthlyDataset.STORAGE_COLUMNS.get(i));
                    if (value != null) out.writeLong(Double.doubleToRawLongBits(value));
                }
                out.flush(); return bytes.toByteArray();
            } catch (java.io.IOException impossible) { throw new IllegalStateException("Cannot canonicalize macro core row", impossible); }
        }
    public static int estimatedTransportBytes(MacroCoreMonthly row, byte[] canonical) {
            return Math.addExact(Math.multiplyExact(canonical.length, 4), 2048);
        }
    public static void requireBatch(List<MacroCoreMonthly> rows) {
        if (rows == null || rows.isEmpty() || rows.size() > MAX_ROWS)
            throw new IllegalArgumentException("Nonempty D104 batch of at most 12 months required");
        var months = new HashSet<YearMonth>(); long bytes = 0;
        for (var row : rows) {
            if (!months.add(key(row))) throw new IllegalArgumentException("Duplicate complete month key");
            byte[] canonical = canonicalBytes(row);
            bytes = Math.addExact(bytes, estimatedTransportBytes(row, canonical));
            if (bytes > MAX_BYTES) throw new IllegalArgumentException("D104 batch exceeds one MiB");
            var values = values(row).asMap();
            for (String field : MacroCoreMonthlyDataset.REQUIRED_MONTHLY_FIELDS)
                if (values.get(field) == null)
                    throw new IllegalArgumentException("Complete six-field monthly publication required: " + field);
        }
    }
    public static String requireIsolatedTable(String table) {
        if (table == null || !table.matches("java_d104_macro_core_monthly_[a-z0-9]{1,64}"))
            throw new IllegalArgumentException("Explicit isolated D104 table required");
        return table;
    }
}
