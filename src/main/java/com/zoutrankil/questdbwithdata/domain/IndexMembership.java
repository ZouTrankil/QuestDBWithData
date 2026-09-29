package com.zoutrankil.questdbwithdata.domain;

import java.time.*;
import java.util.*;

/** One membership period; observedAt is never an effective-date or source-version cursor. */
public record IndexMembership(String indexCode,String tsCode,Instant observedAt,String indexName,
        String constituentCode,String constituentName,LocalDate membershipStartDate,LocalDate membershipEndDate,
        String latestFlag,Double weight,String level,String l1Name,String l2Name,String l3Name) {
    public record Key(String indexCode,String tsCode,LocalDate membershipStartDate) {}
    public IndexMembership {
        if(indexCode==null || !indexCode.matches("[0-9]{6}\\.SI")) throw new IllegalArgumentException("SW index code required");
        // Actual retained membership includes T00018.SH; preserve that historical identity without rewriting it.
        if(tsCode==null || !tsCode.matches("([0-9]{6}\\.(SH|SZ|BJ)|T[0-9]{5}\\.SH)")) throw new IllegalArgumentException("Stock code required");
        Objects.requireNonNull(membershipStartDate,"Membership start date is part of natural identity");
        Objects.requireNonNull(observedAt,"Observation time required");
        if(observedAt.getNano()%1000!=0) throw new IllegalArgumentException("Microsecond observation required");
        if(membershipEndDate!=null && membershipEndDate.isBefore(membershipStartDate))
            throw new IllegalArgumentException("Membership end precedes start");
        if(!Set.of("Y","N").contains(Objects.requireNonNull(latestFlag))) throw new IllegalArgumentException("Y/N flag required");
        if(!Set.of("L1","L2","L3").contains(Objects.requireNonNull(level))) throw new IllegalArgumentException("Industry level required");
        if(weight!=null && !Double.isFinite(weight)) throw new IllegalArgumentException("Finite existing weight required");
    }
    public Key key() { return new Key(indexCode,tsCode,membershipStartDate); }
}
