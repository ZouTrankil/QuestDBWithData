package com.zoutrankil.data.margin.domain;

import com.zoutrankil.data.domain.MarginSecs;
import java.util.*;
import java.time.LocalDate;

/** Immutable values crossing the MarginSecs application/storage boundary. */
public final class MarginSecsState {
    private MarginSecsState() {}
    public record Snapshot(List<MarginSecs> rows,String fingerprint) {
        public Snapshot { rows=List.copyOf(rows);if(fingerprint==null||!fingerprint.matches("[0-9a-f]{64}"))throw new IllegalArgumentException("D030 snapshot fingerprint required"); }
    }
    public record TargetRange(LocalDate min,LocalDate max,long rows) {
        public TargetRange { if((min==null)!=(max==null)||min!=null&&min.isAfter(max)||rows<0||(min==null)!=(rows==0))throw new IllegalArgumentException("Invalid D030 target range"); }
        public boolean empty(){return rows==0;}
    }
}
