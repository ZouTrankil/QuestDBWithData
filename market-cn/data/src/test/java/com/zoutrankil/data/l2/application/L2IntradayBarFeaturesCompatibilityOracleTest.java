package com.zoutrankil.data.l2.application;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.l2.mapper.L2IntradayBarFeaturesMapper;
import com.zoutrankil.data.l2.storage.L2IntradayBarFeaturesWritePort;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Replays all 44 expectations independently verified against the original pre-migration compiled D087 classes. */
class L2IntradayBarFeaturesCompatibilityOracleTest {
    private static final ObjectMapper JSON=new ObjectMapper();
    private static final LocalDate DAY=LocalDate.of(2026,9,21);
    private static final String EXPECTED="{\n  \"provenance\": \"Independent Python encoder, fixed reviewed ordered fields and finite JSON values; no Java/production codec invocation\",\n  \"fields\": [\n    \"trade_date\",\n    \"symbol\",\n    \"market\",\n    \"board\",\n    \"minute\",\n    \"open\",\n    \"high\",\n    \"low\",\n    \"close\",\n    \"volume\",\n    \"amount\",\n    \"tick_count\",\n    \"active_buy_amount\",\n    \"active_sell_amount\",\n    \"vwap\",\n    \"has_trade_1m\",\n    \"bid1\",\n    \"ask1\",\n    \"mid\",\n    \"spread\",\n    \"microprice\",\n    \"bid_depth_1\",\n    \"ask_depth_1\",\n    \"depth_1\",\n    \"obi_1\",\n    \"bid_depth_5\",\n    \"ask_depth_5\",\n    \"depth_5\",\n    \"obi_5\",\n    \"bid_depth_10\",\n    \"ask_depth_10\",\n    \"depth_10\",\n    \"obi_10\",\n    \"quote_count\",\n    \"ofi_1m\",\n    \"active_buy_ratio\",\n    \"active_sell_ratio\",\n    \"vwap_gap_to_mid\",\n    \"vwap_gap_to_open\",\n    \"vwap_slope_3m\",\n    \"vwap_slope_5m\",\n    \"ret_1m\",\n    \"vol_ratio_1m\",\n    \"range_1m\",\n    \"ret_3m\",\n    \"vol_ratio_3m\",\n    \"range_3m\",\n    \"ret_5m\",\n    \"vol_ratio_5m\",\n    \"range_5m\",\n    \"ret_10m\",\n    \"vol_ratio_10m\",\n    \"range_10m\",\n    \"ret_15m\",\n    \"vol_ratio_15m\",\n    \"range_15m\",\n    \"ret_30m\",\n    \"vol_ratio_30m\",\n    \"range_30m\",\n    \"cancel_ratio\"\n  ],\n  \"sourceFingerprint\": \"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\",\n  \"cursor\": \"20260921:3:0\",\n  \"canonical\": [\n    {\n      \"file\": \"canonical-1.json\",\n      \"byteLength\": 1046,\n      \"sha256\": \"009f5fccb011b52d2257879d40260e0208ea3ec118769e0387232c32ba4b8926\",\n      \"transportBytes\": 8880\n    },\n    {\n      \"file\": \"canonical-2.json\",\n      \"byteLength\": 1062,\n      \"sha256\": \"4d30c4e36fe6f4d4ee4e1f1f53e603f5b1733d82b98783b66adfed0ce622da03\",\n      \"transportBytes\": 9008\n    }\n  ],\n  \"pageFrameLength\": 2206,\n  \"pageFingerprint\": \"3dbc551292089da386503d2f9a55476291729fbc7be6bce71e7ef211b08842fd\",\n  \"frameRule\": \"UTF8(d087-page-v1), 00, ASCII(sourceFingerprint), 00, UTF8(cursor), [int32BE length + canonical bytes] for each row; no cursor separator or row newline\"\n}\n";
    @FunctionalInterface interface Checked {void run()throws Exception;}
    private static String sha(byte[] bytes)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
    private static void check(String name,Object expected,Object actual){assertEquals(expected,actual,name);}
    private static void failure(String name,Class<? extends Exception> type,Checked action){assertThrows(type,action::run,name);}
    @Test void all44CapturedOldClassExpectationsHold()throws Exception {
        JsonNode inputs=L2IntradayBarFeaturesFixtures.sourceRows();
        JsonNode expected=JSON.readTree(EXPECTED);
        var mapper=new L2IntradayBarFeaturesMapper();var rows=new ArrayList<L2IntradayBarFeatures>();
        var observations=new ArrayList<Map<String,Object>>();
        for(int i=0;i<inputs.size();i++){
            var row=mapper.fromParquet(inputs.get(i),DAY);rows.add(row);
            byte[] actual=L2IntradayBarFeaturesWritePort.CODEC.canonicalBytes(row),golden=(i==0?L2IntradayBarFeaturesFixtures.CANONICAL_1:L2IntradayBarFeaturesFixtures.CANONICAL_2).getBytes(StandardCharsets.UTF_8);
            check("row"+i+" canonical bytes",true,Arrays.equals(golden,actual));
            check("row"+i+" canonical byte length",expected.path("canonical").get(i).path("byteLength").asInt(),actual.length);
            check("row"+i+" canonical sha256",expected.path("canonical").get(i).path("sha256").asText(),sha(actual));
            check("row"+i+" transport bytes",expected.path("canonical").get(i).path("transportBytes").asInt(),L2IntradayBarFeaturesWritePort.CODEC.estimatedTransportBytes(row,actual));
            check("row"+i+" key",row.key(),L2IntradayBarFeaturesWritePort.CODEC.key(row));
            check("row"+i+" mapped roundtrip",row,mapper.fromValues(mapper.values(row)));
            check("row"+i+" canonical fields",60,JSON.readTree(actual).size());
            check("row"+i+" nullable open present",true,JSON.readTree(actual).has("open")&&JSON.readTree(actual).path("open").isNull());
            check("row"+i+" minute UTC",i==0?"2026-09-21T01:15:00Z":"2026-09-21T01:16:00Z",row.minute().toString());
            observations.add(Map.of("symbol",row.symbol(),"minute",row.minute().toString(),"tradeDate",row.tradeDate().toString(),"features",row.features().size(),"codecSha",sha(actual)));
        }
        check("double signed zero raw bits",Double.doubleToRawLongBits(-0.0),Double.doubleToRawLongBits((Double)rows.getFirst().feature("volume")));
        check("integer exceeds exact double range",9007199254740993L,rows.getLast().feature("tick_count"));
        check("UTF8 board unchanged","主板",rows.getLast().board());
        var fields=new ArrayList<String>();expected.path("fields").forEach(n->fields.add(n.asText()));
        check("all frozen columns in order",fields,L2IntradayBarFeaturesMapper.columns());
        var method=L2IntradayBarFeaturesParquetSource.class.getDeclaredMethod("pageFingerprint",String.class,String.class,List.class);method.setAccessible(true);
        String page=(String)method.invoke(null,expected.path("sourceFingerprint").asText(),expected.path("cursor").asText(),rows);
        check("page fingerprint independent frame",expected.path("pageFingerprint").asText(),page);
        var errors=new LinkedHashMap<String,Object>();
        failure("key symbol format",IllegalArgumentException.class,()->new L2IntradayBarFeaturesKey("000001",rows.getFirst().minute()));
        failure("key second alignment",IllegalArgumentException.class,()->new L2IntradayBarFeaturesKey("000001.SZ",rows.getFirst().minute().plusSeconds(1)));
        failure("key nanosecond alignment",IllegalArgumentException.class,()->new L2IntradayBarFeaturesKey("000001.SZ",rows.getFirst().minute().plusNanos(1)));
        failure("key null minute",NullPointerException.class,()->new L2IntradayBarFeaturesKey("000001.SZ",null));
        failure("formal table admission",IllegalStateException.class,()->L2IntradayBarFeaturesWritePort.requireIsolatedTableName("l2_intraday_bar_features"));
        failure("empty suffix admission",IllegalStateException.class,()->L2IntradayBarFeaturesWritePort.requireIsolatedTableName("java_d087_l2_intraday_bar_features_"));
        L2IntradayBarFeaturesWritePort.requireIsolatedTableName("java_d087_l2_intraday_bar_features_probe");
        for(String kind:List.of("fractional_long","missing_symbol","required_null","offset_minute","wrong_date")){
            ObjectNode node=inputs.get(0).deepCopy();
            switch(kind){
                case "fractional_long"->node.put("tick_count",2.5);
                case "missing_symbol"->node.remove("symbol");
                case "required_null"->node.putNull("volume");
                case "offset_minute"->node.put("minute","2026-09-21T09:15:00+08:00");
                case "wrong_date"->node.put("trade_date","20260922");
            }
            failure("Parquet "+kind,java.io.IOException.class,()->mapper.fromParquet(node,DAY));
        }
        var definition=L2IntradayBarFeaturesJobService.definition();
        check("definition jobId","data.l2_intraday_bar_features",definition.jobId());
        check("definition maxWindowDays",31,definition.budget().maxWindowDays());
        check("definition maxSlices",10000,definition.budget().maxSlices());
        check("definition maxPages",25000,definition.budget().maxPages());
        check("definition maxRows",300000,definition.budget().maxRows());
        check("definition batch bytes",1048576,definition.budget().maxBatchBytes());
        check("definition timeout seconds",7200L,definition.timeout().getSeconds());
        check("definition revisionDays",3,definition.revisionDays());
        check("definition dependencies",List.of(new SyncJobDefinition.JobRef("data.l2_dataset_manifest",1)),definition.dependencies());
        check("definition zone","Asia/Shanghai",definition.zone().getId());
    }
}
