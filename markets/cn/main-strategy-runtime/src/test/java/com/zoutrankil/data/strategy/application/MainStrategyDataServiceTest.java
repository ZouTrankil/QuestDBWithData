package com.zoutrankil.data.strategy.application;

import com.zoutrankil.data.strategy.publication.ScoreArtifactPublisher;
import com.zoutrankil.data.strategy.publication.NativeMarketInputPackagePublisher;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zoutrankil.data.strategy.store.*;
import java.nio.file.*;
import java.time.LocalDate;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MainStrategyDataServiceTest {
    @TempDir Path root;
    private final DataSource jdbc=mock(DataSource.class);
    private static final LocalDate SIGNAL=LocalDate.of(2026,9,25);
    private static final String RECIPE="c".repeat(64);
    private Map<String,String> hashes=Map.of("recipe",RECIPE);
    private MainStrategyDataService service(){return new MainStrategyDataService(new JdbcTemplate(jdbc),root.resolve("artifacts").toString(),root.resolve("recipe.json").toString(),root.resolve("pins.json").toString(),root.toString(),root.resolve("plans.sqlite3").toString(),"2025-07-01",10000);}
    private ScoreArtifactPublisher.Publication publish(String run,LocalDate day)throws Exception {
        return publish(run,day,true);
    }
    private ScoreArtifactPublisher.Publication publish(String run,LocalDate day,boolean packageToo)throws Exception {
        var calendar=new ArrayList<Map<String,Object>>();LocalDate prior=day.minusDays(1);
        for(LocalDate d=day;!d.isAfter(day.plusDays(14));d=d.plusDays(1)){boolean open=d.getDayOfWeek()!=java.time.DayOfWeek.SATURDAY&&d.getDayOfWeek()!=java.time.DayOfWeek.SUNDAY;calendar.add(Map.of("exchange","SSE","cal_date",d,"is_open",open?1L:0L,"pretrade_date",prior));if(open)prior=d;}
        var market=List.<Map<String,Object>>of(Map.of("ts_code","510300.SH","timestamp",day,"open",1.,"high",1.2,"low",.9,"close",1.1,"amount",100.));
        var context=List.<Map<String,Object>>of(Map.of("trade_date",day,"month",day.format(java.time.format.DateTimeFormatter.ofPattern("yyyyMM")),"hs300_ret_mtd",.1,"zz500_ret_mtd",.2,"all_a_ret_mtd",.1,"small_large_ret_mtd",0.,"growth_value_ret_mtd",0.,"avg_up_down_ratio_5d",1.,"data_quality_flag","ok","updated_at","2026-10-11T10:00:00Z"));
        var panel=List.<Map<String,Object>>of(Map.of("trade_date",day,"ts_code","510300.SH","score",.5));
        var sources=new LinkedHashMap<String,Object>();sources.put("calendar",Map.of("rows",calendar.size(),"sha256",rowHash(calendar)));sources.put("etf_daily",Map.of("rows",market.size(),"sha256",rowHash(market)));sources.put("market_context",Map.of("rows",context.size(),"sha256",rowHash(context)));
        var clocks=new TreeMap<String,Object>();for(String table:List.of("exchange_calendar","etf_daily","regime_features_monitor_daily"))clocks.put(table,Map.of("id",1L,"directoryName",table+"~1","walEnabled",true,"writerTxn",2L,"sequencerTxn",2L,"suspended",false,"bufferedTxnSize",0L,"schema",List.of(Map.of("column","date","type","TIMESTAMP"))));
        var proof=Map.<String,Object>of("sourceComplete",true,"end",day.toString(),"recipeHashes",hashes,"sources",sources,"sourceClocks",clocks,"scoreProvenance",Map.of("score_recipe_id","test-only-service"));
        var score=new ScoreArtifactPublisher(root.resolve("artifacts")).publish(run,panel,List.of(Map.of("trade_date",day,"etf_scored_n",1)),List.of(day),hashes.get("recipe"),proof);
        if(packageToo)new NativeMarketInputPackagePublisher(root.resolve("artifacts")).publish(run,day,day,calendar,market,context,panel,score,proof);
        return score;
    }
    private static String rowHash(List<Map<String,Object>> rows)throws Exception {var digest=java.security.MessageDigest.getInstance("SHA-256");var mapper=new ObjectMapper();for(var row:rows){var sorted=new TreeMap<String,Object>();row.forEach((key,value)->sorted.put(key,value instanceof LocalDate date?date.toString():value));byte[] bytes=mapper.writeValueAsBytes(sorted);digest.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.length).array());digest.update(bytes);}return HexFormat.of().formatHex(digest.digest());}
    private void accepted()throws Exception {
        var accepted=new LinkedHashMap<String,String>();for(String role:List.of("factor_weights","factor_states","l2_factor_weights")){try(var stream=getClass().getResourceAsStream("/score/"+role+".parquet")){Files.copy(Objects.requireNonNull(stream),root.resolve(role+".parquet"));}accepted.put(role,ScoreArtifactPublisher.sha(root.resolve(role+".parquet")));}
        var document=new LinkedHashMap<String,Object>();document.put("strategy_id","ETF_ONLY_TOP2");document.put("score_recipe_id","test-only-service");document.put("effective_from","2026-01-01");document.put("training_cutoff","2026-09-24");document.put("input_schema_version","etf_only_top2_score_panel_v1");document.put("code_revision","test-only-revision");document.put("component_l2_weight",.55);document.put("buy_breadth_weight",.20);document.put("trend_weight",.20);document.put("liquidity_weight",.05);document.put("factor_weight_artifact","factor_weights.parquet");document.put("factor_state_artifact","factor_states.parquet");document.put("l2_factor_weight_artifact","l2_factor_weights.parquet");
        Files.write(root.resolve("recipe.json"),new ObjectMapper().writeValueAsBytes(document));accepted.put("recipe",ScoreArtifactPublisher.sha(root.resolve("recipe.json")));hashes=Map.copyOf(accepted);Files.write(root.resolve("pins.json"),new ObjectMapper().writeValueAsBytes(hashes));
    }
    private Path canonical()throws Exception {return Files.writeString(root.resolve("canonical.json"),"""
        {"schema_version":"v1","kind":"official_weekly_plan","available":true,
         "status":"official_weekly_targets_only","strategy":"ETF_ONLY_TOP2",
         "signal_date":"2026-09-25","execution_date":"2026-09-28",
         "targets":[{"symbol":"510300.SH","target_weight":0.5,"signal_date":"2026-09-25","execution_date":"2026-09-28"}],
         "diagnostic_top_candidates_excluded":true}
        """);}
    private StoredOfficialPlan importInitial(Path source,ScoreArtifactPublisher.Publication p){return new OfficialPlanStore(root.resolve("plans.sqlite3")).importSnapshot(source,uncheckedSha(source),new PlanInputProvenance(p.producerRunId(),p.panelRef().contentHash(),p.coverageRef().contentHash(),p.recipeSha256(),p.resolvedEndDate(),"original-revision"));}
    private static String uncheckedSha(Path path){try{return ScoreArtifactPublisher.sha(path);}catch(Exception e){throw new RuntimeException(e);}}
    @Test void weeklyPlanRetainsOriginalEvidenceWhenDailyScoresAndRecipeAdvance()throws Exception {
        var weekly=publish("java_scores_weekly",SIGNAL);Path source=canonical();var original=importInitial(source,weekly);
        publish("java_scores_daily",SIGNAL.plusDays(5));
        var service=service();var accepted=service.requirePlan(SIGNAL.plusDays(5));
        assertEquals(original.provenance(),accepted.provenance());
        var repeated=service.importPlan(source,ScoreArtifactPublisher.sha(source));
        assertEquals(original.planId(),repeated.planId());assertFalse(repeated.created());assertEquals(original.provenance(),repeated.provenance());
        verifyNoInteractions(jdbc);
    }
    @Test void originalPythonRecipeStrategyIsTheSameFormalAuthorityThroughImportAndReadback()throws Exception {
        accepted();var scores=publish("java_scores_recipe_identity",SIGNAL);
        Path source=canonical();Files.writeString(source,Files.readString(source).replace("ETF_ONLY_TOP2","test-only-service"));
        var service=service();var imported=service.importPlan(source,ScoreArtifactPublisher.sha(source),scores.producerRunId());
        assertEquals("test-only-service",imported.plan().strategy());
        assertEquals(imported.planId(),service.requirePlan(SIGNAL.plusDays(3)).planId());
        assertEquals(imported.planId(),service.inspectPlans().latest().orElseThrow().planId());
        assertEquals(scores.producerRunId(),imported.provenance().orElseThrow().scoreRunId());verifyNoInteractions(jdbc);
    }
    @Test void twoScoreFilesAloneCannotClaimThreeRoleInputReadiness()throws Exception {
        accepted();publish("java_scores_only",SIGNAL,false);assertThrows(IllegalStateException.class,()->service().scores(SIGNAL));verifyNoInteractions(jdbc);
    }
    @Test void historicalRequestsCannotAdvanceToTheLatestDailyFrontier()throws Exception {
        accepted();var weekly=publish("java_scores_weekly",SIGNAL);var daily=publish("java_scores_daily",SIGNAL.plusDays(5));
        assertEquals(weekly,service().scores(SIGNAL));assertEquals("20260925",service().inputPackage(SIGNAL).get("max_complete_date"));
        assertEquals(daily,new ScoreArtifactPublisher(root.resolve("artifacts")).latest(null));assertThrows(IllegalStateException.class,()->service().scores(SIGNAL.minusDays(1)));verifyNoInteractions(jdbc);
    }
    @Test void acceptedRecipeRevisionCannotBorrowAnOlderScoreRun()throws Exception {
        accepted();publish("java_scores_original",SIGNAL);var mapper=new ObjectMapper();var recipe=(com.fasterxml.jackson.databind.node.ObjectNode)mapper.readTree(Files.readAllBytes(root.resolve("recipe.json")));recipe.put("dynamic_overlay",.2);Files.write(root.resolve("recipe.json"),mapper.writeValueAsBytes(recipe));var revised=new LinkedHashMap<>(hashes);revised.put("recipe",ScoreArtifactPublisher.sha(root.resolve("recipe.json")));Files.write(root.resolve("pins.json"),mapper.writeValueAsBytes(revised));
        var failure=assertThrows(IllegalStateException.class,()->service().scores(SIGNAL));assertTrue(failure.getMessage().contains("accepted frozen recipe"));verifyNoInteractions(jdbc);
    }
    @Test void partialScorePublicationCannotBecomeOfficialWeeklyAuthority()throws Exception {
        accepted();publish("java_scores_partial",SIGNAL,false);var source=canonical();assertThrows(IllegalStateException.class,()->service().importPlan(source,ScoreArtifactPublisher.sha(source)));assertFalse(Files.exists(root.resolve("plans.sqlite3")));verifyNoInteractions(jdbc);
    }
    @Test void changedAcceptedWeightsWithTheSameRecipeJsonCannotMixWithOldScoresOrPlan()throws Exception {
        accepted();var fields=new ArrayList<String>(List.of("trade_date","dynamic_evidence_sum"));com.zoutrankil.data.strategy.score.EtfOnlyTop2ScoreProducer.FACTOR_COLUMNS.keySet().forEach(key->fields.add("w_"+key));
        String csv=String.join(",",fields)+"\n2026-01-01,1,"+String.join(",",Collections.nCopies(fields.size()-2,"0.1"))+"\n";Files.writeString(root.resolve("factor_weights.csv"),csv);
        var mapper=new ObjectMapper();var recipe=(com.fasterxml.jackson.databind.node.ObjectNode)mapper.readTree(Files.readAllBytes(root.resolve("recipe.json")));recipe.put("factor_weight_artifact","factor_weights.csv");Files.write(root.resolve("recipe.json"),mapper.writeValueAsBytes(recipe));
        var accepted=new LinkedHashMap<>(hashes);accepted.put("recipe",ScoreArtifactPublisher.sha(root.resolve("recipe.json")));accepted.put("factor_weights",ScoreArtifactPublisher.sha(root.resolve("factor_weights.csv")));hashes=Map.copyOf(accepted);Files.write(root.resolve("pins.json"),mapper.writeValueAsBytes(hashes));publish("java_scores_original_weights",SIGNAL);
        Files.writeString(root.resolve("factor_weights.csv"),csv.replace("0.1","0.2"));accepted.put("factor_weights",ScoreArtifactPublisher.sha(root.resolve("factor_weights.csv")));Files.write(root.resolve("pins.json"),mapper.writeValueAsBytes(accepted));
        assertThrows(IllegalStateException.class,()->service().scores(SIGNAL));Path source=canonical();assertThrows(IllegalStateException.class,()->service().importPlan(source,ScoreArtifactPublisher.sha(source)));assertFalse(Files.exists(root.resolve("plans.sqlite3")));verifyNoInteractions(jdbc);
    }
    @Test void staleWeeklyAuthorityCannotReuseCompleteOriginalArtifacts()throws Exception {
        var weekly=publish("java_scores_weekly",SIGNAL);importInitial(canonical(),weekly);assertThrows(IllegalStateException.class,()->service().requirePlan(SIGNAL.plusDays(10)));verifyNoInteractions(jdbc);
    }
    @Test void weeklyFrontierUsesFrozenSseHolidaySessionsAndIsoYears() {
        var opens=List.of(SIGNAL,SIGNAL.plusDays(3),SIGNAL.plusDays(4),SIGNAL.plusDays(5),LocalDate.of(2026,10,8),LocalDate.of(2026,10,9),LocalDate.of(2026,10,12));
        assertDoesNotThrow(()->MainStrategyDataService.requireWeeklyFrontier(SIGNAL,SIGNAL.plusDays(3),SIGNAL.plusDays(4),opens));
        assertDoesNotThrow(()->MainStrategyDataService.requireWeeklyFrontier(SIGNAL.plusDays(5),LocalDate.of(2026,10,8),SIGNAL.plusDays(5),opens));
        assertThrows(IllegalStateException.class,()->MainStrategyDataService.requireWeeklyFrontier(SIGNAL,SIGNAL.plusDays(3),LocalDate.of(2026,10,8),opens));
        var yearEnd=List.of(LocalDate.of(2026,12,31),LocalDate.of(2027,1,4));assertDoesNotThrow(()->MainStrategyDataService.requireWeeklyFrontier(yearEnd.getFirst(),yearEnd.getLast(),yearEnd.getFirst(),yearEnd));
    }
    @Test void missingAcceptedTrainingBlocksBeforeDatabaseAccessAndCreatesNoOutputs() {
        assertThrows(IllegalStateException.class,()->service().prepareScores(SIGNAL,"java_scores_missing_recipe"));
        assertFalse(Files.exists(root.resolve("artifacts")));verifyNoInteractions(jdbc);
    }
    @Test void statusAndPlanInspectionOnMissingArtifactsRemainReadOnly()throws Exception {
        var service=service();assertEquals(3,((List<?>)service.status().get("missingArtifacts")).size());
        assertEquals("missing",service.inspectPlans().integrityCheck());assertFalse(Files.exists(root.resolve("plans.sqlite3")));assertFalse(Files.exists(root.resolve("artifacts")));verifyNoInteractions(jdbc);
    }
}
