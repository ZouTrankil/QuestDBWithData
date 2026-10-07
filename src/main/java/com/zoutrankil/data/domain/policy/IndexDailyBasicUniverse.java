package com.zoutrankil.data.domain.policy;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Exact five-code universe used by the Python index_dailybasic sync function. */
public final class IndexDailyBasicUniverse {
    private IndexDailyBasicUniverse() {}
    public static final List<String> CORE_INDICES = List.of(
            "000300.SH", "000016.SH", "399006.SZ", "000905.SH", "000852.SH");
    private static final Set<String> MEMBERS = Set.copyOf(CORE_INDICES);
    static { if (MEMBERS.size() != 5) throw new ExceptionInInitializerError("D020 universe cardinality changed"); }
    public static String resolve(String code) {
        if (code == null) return null;
        String canonical = code.strip().toUpperCase(Locale.ROOT);
        return MEMBERS.contains(canonical) ? canonical : null;
    }
    public static boolean valid(String code) { return resolve(code) != null; }
}
