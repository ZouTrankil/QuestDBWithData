package com.zoutrankil.data.l2.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zoutrankil.data.config.QuestDbProperties;
import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.l2.domain.*;
import com.zoutrankil.data.l2.mapper.*;
import com.zoutrankil.data.l2.storage.*;
import com.zoutrankil.data.service.*;
import com.zoutrankil.data.sync.port.VerifiedWriteSession;
import io.questdb.client.QuestDB;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;

/** Synthetic original mapping-test inputs. No external Parquet receipt or data readiness claim. */
final class L2EventAndT0Fixtures {
    static final ObjectMapper JSON = JobDefinitionJson.mapper();
    static final LocalDate DAY = LocalDate.of(2026, 9, 21);
    static final String SYMBOL = "000001.SZ";
    static final String SOURCE = "a".repeat(64), SCHEMA = "b".repeat(64), ROOT = "c".repeat(64);
    static final List<String> HORIZONS = List.of("1m", "3m", "5m", "10m", "15m", "30m");
    enum Family {
        EVENT("D088", "L2EventResponseFeatures", "l2_event_response_features"),
        T0("D089", "L2T0TrainingLabels", "l2_t0_training_labels");
        final String code, prefix, dataset;
        Family(String code, String prefix, String dataset) { this.code=code; this.prefix=prefix; this.dataset=dataset; }
        String table() { return "java_"+code.toLowerCase()+"_"+dataset+"_contract"; }
        Class<?> sourceType() { return this == EVENT ? L2EventResponseFeaturesParquetSource.class : L2T0TrainingLabelsParquetSource.class; }
        SyncJobDefinition definition() { return this == EVENT ? L2EventResponseFeaturesJobService.definition() : L2T0TrainingLabelsJobService.definition(); }
        Object row(JsonNode input) throws Exception { return this == EVENT ? new L2EventResponseFeaturesMapper().fromParquet(input, DAY) : new L2T0TrainingLabelsMapper().fromParquet(input, DAY); }
        DatasetValues values(Object row) { return this == EVENT ? new L2EventResponseFeaturesMapper().values((L2EventResponseFeatures) row) : new L2T0TrainingLabelsMapper().values((L2T0TrainingLabels) row); }
        byte[] canonical(Object row) { return this == EVENT ? L2EventResponseFeaturesRows.canonicalBytes((L2EventResponseFeatures) row) : L2T0TrainingLabelsRows.canonicalBytes((L2T0TrainingLabels) row); }
        @SuppressWarnings({"rawtypes", "unchecked"}) VerifiedBatchExecutor.Codec<Object,Object> codec() { return (VerifiedBatchExecutor.Codec)(this == EVENT ? L2EventResponseFeaturesWritePort.CODEC : L2T0TrainingLabelsWritePort.CODEC); }
        @SuppressWarnings({"rawtypes", "unchecked"}) VerifiedWriteSession<Object,Object> writer(JdbcTemplate jdbc, QuestDB questdb) { return (VerifiedWriteSession)(this == EVENT ? new L2EventResponseFeaturesWritePort(table(),jdbc,questdb) : new L2T0TrainingLabelsWritePort(table(),jdbc,questdb)); }
        Object target(String table, JdbcTemplate jdbc, QuestDB questdb, QuestDbProperties properties) { return this == EVENT ? new QuestDbL2EventResponseFeaturesTarget(table,jdbc,questdb,properties) : new QuestDbL2T0TrainingLabelsTarget(table,jdbc,questdb,properties); }
        Object source(Path root, Path helper, String python) { return this == EVENT ? new L2EventResponseFeaturesParquetSource(root,helper,python) : new L2T0TrainingLabelsParquetSource(root,helper,python); }
        Object inspection(int rows, int pages) {
            return this == EVENT ? new L2EventResponseFeaturesParquetSource.Inspection(DAY,DAY,List.of(DAY),rows,rows,1,pages,1,SOURCE,SCHEMA,"l2-event-response-features-parquet-v1",ROOT,true)
                    : new L2T0TrainingLabelsParquetSource.Inspection(DAY,DAY,List.of(DAY),rows,rows,1,pages,1,SOURCE,SCHEMA,"l2-t0-training-labels-parquet-v1",ROOT,true,coverage(rows));
        }
        String pageFingerprint(List<?> rows) throws Exception { return (String) call(sourceType(),null,"pageFingerprint",new Class<?>[]{String.class,String.class,List.class},SOURCE,"20260921:1:0",rows); }
        JsonNode golden() throws Exception { for(JsonNode family : goldens().path("families")) if(family.path("family").asText().equals(code.toLowerCase())) return family; throw new AssertionError(code); }
        ObjectNode input() throws Exception { return (ObjectNode) golden().path("cases").get(0).path("input").deepCopy(); }
    }
    static Map<String,Map<String,Long>> coverage(long rows) {
        var result=new LinkedHashMap<String,Map<String,Long>>();
        for(String horizon:HORIZONS) {
            var counts=new LinkedHashMap<String,Long>();
            for(String measure:List.of("rows", "future_vwap_return_non_null", "future_mid_return_non_null", "sell_alpha_non_null", "buy_alpha_non_null", "sell_binary_zero_with_missing_alpha", "buy_binary_zero_with_missing_alpha")) counts.put(measure,rows);
            result.put(horizon,counts);
        }
        return result;
    }
    static JsonNode goldens() throws Exception { try(var in=L2EventAndT0Fixtures.class.getResourceAsStream("/l2-d088-d089/original-goldens.json")) { return JSON.readTree(Objects.requireNonNull(in)); } }
    static Object call(Class<?> owner,Object instance,String name,Class<?>[] types,Object...args) throws Exception {
        Method method=owner.getDeclaredMethod(name,types); method.setAccessible(true);
        try { return method.invoke(instance,args); } catch(InvocationTargetException failure) { if(failure.getCause() instanceof Exception exception)throw exception; if(failure.getCause() instanceof Error error)throw error; throw failure; }
    }
    private L2EventAndT0Fixtures() {}
}
