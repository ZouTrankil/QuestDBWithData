package com.zoutrankil.batch;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

record StandardSourceCoveragePolicy(boolean aggregate, Code codePolicy, String entityColumn,
                                    Coverage coverage, Period period) implements SourceCoveragePolicy {
    enum Code {
        STOCK, EXCHANGE, ETF_MARKET, FUTURES_BASIC, FUTURES_CONTINUOUS, FUTURES_PRODUCT,
        FUTURES_CONTRACT, ETF_PORTFOLIO, INDEX;
        boolean valid(String code) {
            if (code == null) return false;
            return switch (this) {
                case EXCHANGE -> Set.of("SSE","SZSE").contains(code);
                case ETF_MARKET -> code.equals("E");
                case FUTURES_BASIC -> Set.of("CFFEX","DCE","CZCE","CZC","SHFE","SHF","INE","GFEX").contains(code)
                        || code.matches("[A-Z]{1,3}[0-9]{3,4}\\.(CFX|SHF|DCE|CZC|INE|GFEX)");
                case FUTURES_CONTINUOUS -> code.matches("[A-Z]{1,3}\\.(CFX|SHF|DCE|CZC|INE|GFEX)");
                case FUTURES_PRODUCT -> code.matches("[A-Z]{1,3}");
                case FUTURES_CONTRACT -> code.matches("[A-Z]{1,3}[0-9]{3,4}\\.(CFX|SHF|DCE|CZC|INE|GFEX)");
                case ETF_PORTFOLIO -> code.matches("[0-9]{6}\\.(SH|SZ|BJ|OF)");
                case INDEX -> code.matches("[0-9]{6}\\.(SH|SZ|BJ|CSI|SI)");
                case STOCK -> code.matches("[0-9]{6}\\.(SH|SZ|BJ)");
            };
        }
    }
    enum Coverage { ENTITY, SNAPSHOT, BOUNDED_AGGREGATE, NONEMPTY_AGGREGATE, REQUIRED_ENTITY,
        BOND_CURVES, RATE, MONTH, SINGLE_AGGREGATE }
    enum Period { NONE, MONTH, QUARTER }

    StandardSourceCoveragePolicy {
        Objects.requireNonNull(codePolicy); Objects.requireNonNull(entityColumn);
        Objects.requireNonNull(coverage); Objects.requireNonNull(period);
    }
    @Override public boolean isMarketAggregate(SourceContract contract) { return aggregate; }
    @Override public boolean validCode(SourceContract contract, String code) { return codePolicy.valid(code); }
    @Override public Set<String> observedCodes(SourceContract contract, List<Map<String,Object>> rows) {
        if (aggregate) return Set.of();
        var codes = new TreeSet<String>();
        rows.forEach(row -> codes.add(Objects.requireNonNull((String) row.get(entityColumn))));
        return codes;
    }
    @Override public boolean covers(SourceContract contract, List<Map<String,Object>> rows, Set<String> expectedCodes) {
        return switch (coverage) {
            case SNAPSHOT -> !expectedCodes.isEmpty() && !rows.isEmpty() && observedCodes(contract,rows).equals(expectedCodes);
            case BOUNDED_AGGREGATE -> expectedCodes.isEmpty() && rows.size() <= contract.maxRows();
            case NONEMPTY_AGGREGATE -> expectedCodes.isEmpty() && !rows.isEmpty() && rows.size() < contract.maxRows();
            case REQUIRED_ENTITY -> !expectedCodes.isEmpty() && observedCodes(contract,rows).equals(expectedCodes);
            case BOND_CURVES -> expectedCodes.isEmpty()
                    && rows.stream().map(row -> row.get("curve_code")).filter(Objects::nonNull)
                    .collect(java.util.stream.Collectors.toSet()).equals(Set.of("gov","aaa_mtn","aaa_bank"));
            case RATE -> expectedCodes.isEmpty() && rows.size() <= 1;
            case MONTH -> expectedCodes.isEmpty();
            case SINGLE_AGGREGATE -> expectedCodes.isEmpty() && rows.size() == 1;
            case ENTITY -> contract.emptyAllowed() ? expectedCodes.containsAll(observedCodes(contract,rows))
                    : observedCodes(contract,rows).equals(expectedCodes);
        };
    }
    @Override public boolean coversRange(SourceContract contract, List<Map<String,Object>> rows,
                                         Set<String> expectedCodes, LocalDate start, LocalDate end) {
        boolean result = covers(contract,rows,expectedCodes);
        if (period == Period.MONTH) result &= SourcePeriods.coversMonths(rows,start,end);
        if (period == Period.QUARTER) result &= SourcePeriods.coversQuarters(rows,start,end);
        return result;
    }
}
