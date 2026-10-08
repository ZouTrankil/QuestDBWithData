package com.zoutrankil.data.derived.domain;
import com.zoutrankil.data.domain.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;

/** Immutable source census and physical identity; component order is evidence. */
public final class EquityStyleMonthlySourceData {
    private EquityStyleMonthlySourceData() {}
    public static final int MAX_MONTHS=12, MAX_SOURCE_ROWS=6200;
    public static final List<String> SOURCE_FIELDS=IndexMonthlyDataset.columns().stream().map(DatasetDefinition.Column::storageName).toList();
    public static final Map<String,String> FEATURE_CODES;
    static {
        var m=new LinkedHashMap<String,String>();
        m.put("hs300_ret_1m","000300.SH");m.put("zz500_ret_1m","000905.SH");m.put("all_a_ret_1m","000985.SH");m.put("cs1000_ret_1m","000852.SH");
        // Preserve the existing owner bindings, including the legacy growth/value labels.
        m.put("value_ret_1m","000920.SH");m.put("growth_ret_1m","000921.SH");
        String[] sectors={"energy","materials","industrials","consumer_discretionary","consumer_staples","healthcare","financials","it","telecom","utilities"};
        for(int i=0;i<sectors.length;i++)m.put(sectors[i]+"_ret_1m",String.format(Locale.ROOT,"%06d.SH",986+i));
        FEATURE_CODES=Collections.unmodifiableMap(m);
    }
    public record Snapshot(String table,long tableId,String directory,long physicalTxn,long sequenceTxn,String schemaHash) {
        public Snapshot {
            DatasetDefinition.identifier(table);
            if(tableId<1||directory==null||directory.isBlank()||physicalTxn<0||sequenceTxn<0||schemaHash==null||!schemaHash.matches("[0-9a-f]{64}"))
                throw new IllegalArgumentException("Complete settled source physical identity required");
        }
        public String version(){return hash(table+"\n"+tableId+"\n"+directory+"\n"+physicalTxn+"\n"+sequenceTxn+"\n"+schemaHash);}
    }
    public record Batch(Snapshot snapshot,String rawFingerprint,int rawRows,List<EquityStyleMonthly> rows) {
        public Batch {
            Objects.requireNonNull(snapshot);rows=List.copyOf(rows);
            if(rawFingerprint==null||!rawFingerprint.matches("[0-9a-f]{64}")||rawRows<0||rawRows>MAX_SOURCE_ROWS||rows.size()>MAX_MONTHS)
                throw new IllegalArgumentException("Finite complete source census required");
        }
        public String fingerprint(){return hash(snapshot.version()+"\n"+rawFingerprint);}
    }
    static String hash(String text){try{return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
}
