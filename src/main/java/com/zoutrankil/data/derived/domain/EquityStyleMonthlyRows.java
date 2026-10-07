package com.zoutrankil.data.derived.domain;
import com.zoutrankil.data.domain.*;
import java.time.*;
import java.util.*;
import java.io.*;

/** Pure ordered projection, canonical encoding and publication admission. */
public final class EquityStyleMonthlyRows {
    private EquityStyleMonthlyRows() {}
    public static final int MAX_ROWS=12,MAX_BYTES=1024*1024;
    public static DatasetValues values(EquityStyleMonthly row) {
        Objects.requireNonNull(row, "equity style row required");
        var values = new LinkedHashMap<String, Object>();
        values.put("month", row.key().storageDate());
        values.put("hs300_ret_1m", row.hs300Ret1m());
        values.put("zz500_ret_1m", row.zz500Ret1m());
        values.put("all_a_ret_1m", row.allARet1m());
        values.put("cs1000_ret_1m", row.cs1000Ret1m());
        values.put("small_large_ret_1m", row.smallLargeRet1m());
        values.put("mid_large_ret_1m", row.midLargeRet1m());
        values.put("growth_ret_1m", row.growthRet1m());
        values.put("value_ret_1m", row.valueRet1m());
        values.put("growth_value_ret_1m", row.growthValueRet1m());
        values.put("energy_ret_1m", row.energyRet1m());
        values.put("materials_ret_1m", row.materialsRet1m());
        values.put("industrials_ret_1m", row.industrialsRet1m());
        values.put("consumer_discretionary_ret_1m", row.consumerDiscretionaryRet1m());
        values.put("consumer_staples_ret_1m", row.consumerStaplesRet1m());
        values.put("healthcare_ret_1m", row.healthcareRet1m());
        values.put("financials_ret_1m", row.financialsRet1m());
        values.put("it_ret_1m", row.itRet1m());
        values.put("telecom_ret_1m", row.telecomRet1m());
        values.put("utilities_ret_1m", row.utilitiesRet1m());
        values.put("energy_vs_all_a_1m", row.energyVsAllA1m());
        values.put("materials_vs_all_a_1m", row.materialsVsAllA1m());
        values.put("industrials_vs_all_a_1m", row.industrialsVsAllA1m());
        values.put("consumer_discretionary_vs_all_a_1m", row.consumerDiscretionaryVsAllA1m());
        values.put("consumer_staples_vs_all_a_1m", row.consumerStaplesVsAllA1m());
        values.put("healthcare_vs_all_a_1m", row.healthcareVsAllA1m());
        values.put("financials_vs_all_a_1m", row.financialsVsAllA1m());
        values.put("it_vs_all_a_1m", row.itVsAllA1m());
        values.put("telecom_vs_all_a_1m", row.telecomVsAllA1m());
        values.put("utilities_vs_all_a_1m", row.utilitiesVsAllA1m());
        return new DatasetValues(values);
    }
    public static YearMonth key(EquityStyleMonthly row) { return Objects.requireNonNull(row, "row required").month(); }
    public static byte[] canonicalBytes(EquityStyleMonthly row) {
            Objects.requireNonNull(row, "row required");
            try {
                var bytes = new ByteArrayOutputStream(); var out = new DataOutputStream(bytes);
                out.writeInt(row.month().getYear()); out.writeByte(row.month().getMonthValue());
                var values = values(row).asMap();
                int nulls = 0;
                for (int i = 1; i < EquityStyleMonthlyDataset.STORAGE_COLUMNS.size(); i++)
                    if (values.get(EquityStyleMonthlyDataset.STORAGE_COLUMNS.get(i)) == null) nulls |= 1 << (i - 1);
                out.writeInt(nulls);
                for (int i = 1; i < EquityStyleMonthlyDataset.STORAGE_COLUMNS.size(); i++) {
                    Double value = (Double) values.get(EquityStyleMonthlyDataset.STORAGE_COLUMNS.get(i));
                    if (value != null) out.writeLong(Double.doubleToRawLongBits(value));
                }
                out.flush(); return bytes.toByteArray();
            } catch (java.io.IOException impossible) { throw new IllegalStateException("Cannot canonicalize equity style row", impossible); }
        }
    public static int estimatedTransportBytes(EquityStyleMonthly row, byte[] canonical) {
            return Math.addExact(Math.multiplyExact(canonical.length, 4), 2048);
        }
    public static void requireBatch(List<EquityStyleMonthly> rows) {
        if (rows == null || rows.isEmpty() || rows.size() > MAX_ROWS)
            throw new IllegalArgumentException("Nonempty D103 batch of at most 12 months required");
        var months = new HashSet<YearMonth>(); long bytes = 0;
        for (var row : rows) {
            if (!months.add(key(row))) throw new IllegalArgumentException("Duplicate complete month key");
            byte[] canonical = canonicalBytes(row);
            bytes = Math.addExact(bytes, estimatedTransportBytes(row, canonical));
            if (bytes > MAX_BYTES) throw new IllegalArgumentException("D103 batch exceeds one MiB");
            if (values(row).asMap().entrySet().stream()
                    .noneMatch(e -> !e.getKey().equals("month") && e.getValue() != null))
                throw new IllegalArgumentException("A timestamp-only all-null row cannot be transported as ILP");
        }
    }
    public static String requireIsolatedTable(String table) {
        if (table == null || !table.matches("java_d103_equity_style_monthly_[a-z0-9]{1,64}"))
            throw new IllegalArgumentException("Explicit isolated D103 table required");
        return table;
    }
}
