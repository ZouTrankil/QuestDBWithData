package com.zoutrankil.market.us.marketdata;

import java.time.LocalDate;
import java.util.Map;

/** 发行人已明确的每股现金额；日期仍是来源调整日，不推算官方除息日或重算调整价。 */
final class TiingoVerifiedCashDividends {
    record Correction(double sourceCash, double cashDividend, String primarySourceUrl) {}

    private static final String OFFICE_REGULAR =
            "https://www.sec.gov/Archives/edgar/data/12978/000104746909001823/a2190902z10-k.htm";
    private static final String OFFICE_SPECIAL =
            "https://www.sec.gov/Archives/edgar/data/12978/000119312513203859/d533500dex991.htm";
    private static final String TITANIUM_2007_2008 =
            "https://www.sec.gov/Archives/edgar/data/1011657/000101165710000004/form_10k.htm";
    private static final String TITANIUM_2011 =
            "https://www.sec.gov/Archives/edgar/data/1011657/000119312512087802/d228252d10k.htm";
    private static final String TITANIUM_2012_FIRST_HALF =
            "https://www.sec.gov/Archives/edgar/data/1011657/000119312512343042/d362139d10q.htm";
    private static final String TITANIUM_2012_THIRD =
            "https://www.sec.gov/Archives/edgar/data/1011657/000005925512000277/timet10q0912.htm";
    private static final String TITANIUM_2012_FINAL =
            "https://www.sec.gov/Archives/edgar/data/1011657/000005925512000291/tiepr121120.htm";

    // 只列出原文金额已核对的偏差；其余记录保留来源原值，未知修订由调用方拒收。
    private static final Map<LocalDate, Correction> OFFICE =
            Map.ofEntries(
                    entry("2007-06-27", 0.16, 0.15, OFFICE_REGULAR),
                    entry("2008-09-29", 0.13, 0.15, OFFICE_REGULAR),
                    entry("2013-06-10", 1.49, 1.50, OFFICE_SPECIAL));
    private static final Map<LocalDate, Correction> TITANIUM =
            Map.ofEntries(
                    entry("2007-12-13", 0.07, 0.075, TITANIUM_2007_2008),
                    entry("2008-03-07", 0.07, 0.075, TITANIUM_2007_2008),
                    entry("2008-06-06", 0.07, 0.075, TITANIUM_2007_2008),
                    entry("2008-09-08", 0.07, 0.075, TITANIUM_2007_2008),
                    entry("2008-12-08", 0.08, 0.075, TITANIUM_2007_2008),
                    entry("2011-06-08", 0.07, 0.075, TITANIUM_2011),
                    entry("2011-09-07", 0.08, 0.075, TITANIUM_2011),
                    entry("2011-12-07", 0.07, 0.075, TITANIUM_2011),
                    entry("2012-03-07", 0.08, 0.075, TITANIUM_2012_FIRST_HALF),
                    entry("2012-06-07", 0.07, 0.075, TITANIUM_2012_FIRST_HALF),
                    entry("2012-09-06", 0.08, 0.075, TITANIUM_2012_THIRD),
                    entry("2012-12-05", 0.08, 0.075, TITANIUM_2012_FINAL));

    private TiingoVerifiedCashDividends() {}

    static Correction find(String symbol, LocalDate date) {
        return switch (symbol) {
            case "US000000041341" -> OFFICE.get(date);
            case "US000000009458" -> TITANIUM.get(date);
            default -> null;
        };
    }

    private static Map.Entry<LocalDate, Correction> entry(
            String date, double sourceCash, double cashDividend, String source) {
        return Map.entry(LocalDate.parse(date), new Correction(sourceCash, cashDividend, source));
    }
}
