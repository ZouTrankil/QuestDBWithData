package com.zoutrankil.questdbwithdata.domain;

import java.util.List;
import java.util.Set;
import static com.zoutrankil.questdbwithdata.domain.DatasetDefinition.*;

/** Physical MONTH/WAL contract with current membership natural key. */
public final class ThsMemberDataset {
    private ThsMemberDataset() {}
    public static final DatasetDefinition DEFINITION = new DatasetDefinition(
            "ths_member", 1, "tushare.ths_member", "ths_member_owner", "ths_member", ObjectKind.TABLE,
            List.of(new Column("ts_code", "board_code", "ts_code", StorageType.SYMBOL, false,
                            "THS board identity", null),
                    new Column("con_code", "constituent_code", "con_code", StorageType.SYMBOL, false,
                            "Provider constituent identity including market suffix", null),
                    new Column("con_name", "constituent_name", "con_name", StorageType.STRING, false,
                            "Current constituent display name", null),
                    new Column("weight", "weight", "weight", StorageType.DOUBLE, true,
                            "Provider weight; unit not declared", null),
                    new Column("in_date", "in_date", "in_date", StorageType.STRING, true,
                            "Provider membership start business date",
                            new TemporalContract(TemporalKind.BUSINESS_DATE, "BASIC", "calendar", "DAY", "Membership start")),
                    new Column("out_date", "out_date", "out_date", StorageType.STRING, true,
                            "Provider membership end business date",
                            new TemporalContract(TemporalKind.BUSINESS_DATE, "BASIC", "calendar", "DAY", "Membership end")),
                    new Column("is_new", "is_new", "is_new", StorageType.STRING, true,
                            "Provider current membership Y/N flag", null),
                    new Column("derived:observation_instant", "observed_at", "update_time", StorageType.TIMESTAMP,
                            false, "UTC observation, not provider change time",
                            new TemporalContract(TemporalKind.INSTANT, "ISO_INSTANT", "UTC", "MICROS", "Observation time"))),
            List.of("board_code", "constituent_code"), List.of("ts_code", "con_code", "update_time"),
            "update_time", Partition.MONTH, true, Set.of(Capability.READ, Capability.WAL_REPLACE),
            List.of("ths_index"),
            "Current membership is unique by board and constituent. Physical dedup also includes observation time; "
                    + "a fresh timestamp alone cannot replace an older membership row.");
}
