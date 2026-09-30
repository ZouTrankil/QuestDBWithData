package com.zoutrankil.questdbwithdata.domain;

import java.util.Objects;

/** Tushare margin_detail values in provider units; nullable provider fields remain null. */
public record MarginDetail(MarginDetailKey key, String name, Double rzye, Double rzmre, Double rzche,
        Double rqye, Double rqyl, Double rqchl, Double rqmcl, Double rzrqye) {
    public MarginDetail {
        Objects.requireNonNull(key, "Complete margin_detail key required");
        if (name != null && name.isBlank()) name = null;
        if (rzye == null || rzmre == null || rzye < 0 || rzmre < 0)
            throw new IllegalArgumentException("margin_detail financing balances must be present and nonnegative");
        for (Double value : new Double[]{rzye, rzmre, rzche, rqye, rqyl, rqchl, rqmcl, rzrqye})
            if (value != null && !Double.isFinite(value))
                throw new IllegalArgumentException("margin_detail numeric values must be finite or null");
        for (Double value : new Double[]{rqye, rqyl, rqmcl, rzrqye})
            if (value != null && value < 0)
                throw new IllegalArgumentException("margin_detail balances/quantities must be nonnegative when present");
    }
    public String tsCode() { return key.tsCode(); }
    public java.time.LocalDate tradeDate() { return key.tradeDate(); }
}
