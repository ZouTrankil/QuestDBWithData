package com.zoutrankil.data.factor.domain;

import java.util.Objects;

/** Versioned calculation binding retained from the Python factor contract. */
public record FactorDefinition(String factorId,String version,String assetType,String sourceGroup,
        String sourceTable,String sourceColumn,String transform,String direction,int lookbackPeriods,
        String pitPolicy,String normalizePolicy,String neutralizePolicy,
        boolean enabled,boolean publishObservations) {
    public FactorDefinition {
        for(String value:new String[]{factorId,version,assetType,sourceGroup,sourceColumn,
                transform,direction,pitPolicy,normalizePolicy,neutralizePolicy})
            if(Objects.requireNonNull(value).isBlank()) throw new IllegalArgumentException("Blank factor binding");
        // Retain unresolved Python definitions in the inventory. They are not
        // executable source bindings and must not be assigned an invented table.
        Objects.requireNonNull(sourceTable);
        if(lookbackPeriods<0) throw new IllegalArgumentException("Negative factor lookback");
    }
    public double directionSign() { return "negative".equals(direction)?-1:1; }
    public boolean hasDeclaredSource() { return !sourceTable.isBlank(); }
}
