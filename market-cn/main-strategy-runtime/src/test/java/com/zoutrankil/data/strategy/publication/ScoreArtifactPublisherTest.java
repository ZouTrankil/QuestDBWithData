package com.zoutrankil.data.strategy.publication;

import java.nio.file.*;
import java.sql.*;
import java.time.LocalDate;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class ScoreArtifactPublisherTest {
    @TempDir Path root;
    private static final LocalDate D=LocalDate.of(2026,9,30);
    private static final String RECIPE="a".repeat(64);
    private List<Map<String,Object>> panel(LocalDate day) {return List.of(new LinkedHashMap<>(Map.of("trade_date",day,"ts_code","510300.SH","etf_factor_score_base",.625,"self_overheat_flag",false)));}
    private List<Map<String,Object>> coverage(LocalDate day) {return List.of(Map.of("trade_date",day,"etf_scored_n",1,"component_hit_rate",.90));}
    private Map<String,Object> proof(LocalDate day) {return Map.of("sourceComplete",true,"end",day.toString(),"recipeHashes",Map.of("recipe",RECIPE));}
    private ScoreArtifactPublisher.Publication publish(String run,LocalDate day)throws Exception {return new ScoreArtifactPublisher(root).publish(run,panel(day),coverage(day),List.of(day),RECIPE,proof(day));}
    @Test void parquetAndCatalogPublishOneFrozenPairAndRerunIsImmutable()throws Exception {
        var first=publish("java_scores_one",D);var second=publish("java_scores_one",D);
        assertEquals(first.panelRef().contentHash(),second.panelRef().contentHash());
        assertEquals(first.coverageRef().contentHash(),second.coverageRef().contentHash());
        ScoreArtifactPublisher.validateFrame(first.panelRef());ScoreArtifactPublisher.validateFrame(first.coverageRef());
        assertEquals(1,first.panelRef().rowCount());assertEquals(D,first.resolvedEndDate());
        try(var c=DriverManager.getConnection("jdbc:sqlite:"+root.resolve("_catalog/resource_catalog.sqlite3"));var q=c.createStatement();var r=q.executeQuery("SELECT count(DISTINCT m.run_id),count() FROM resource_aliases a JOIN resource_metadata m ON a.target_uri=m.uri AND a.target_version=m.version")){assertTrue(r.next());assertEquals(1,r.getInt(1));assertEquals(2,r.getInt(2));}
    }
    @Test void incompleteCoverageCannotAdvanceEitherAlias()throws Exception {
        var old=publish("java_scores_old",D);var publisher=new ScoreArtifactPublisher(root);
        assertThrows(IllegalStateException.class,()->publisher.publish("java_scores_gap",panel(D.plusDays(1)),coverage(D),List.of(D.plusDays(1)),RECIPE,proof(D.plusDays(1))));
        assertEquals(old,publisher.latest(D));
    }
    @Test void failedReceiptCollisionRollsBackBothAliases()throws Exception {
        var old=publish("java_scores_old",D);var publisher=new ScoreArtifactPublisher(root);
        assertThrows(IllegalStateException.class,()->publisher.publish("java_scores_old",panel(D.plusDays(1)),coverage(D.plusDays(1)),List.of(D.plusDays(1)),RECIPE,proof(D.plusDays(1))));
        assertEquals(old,publisher.latest(D));
    }
    @Test void footerGuardsDetectCatalogOnlyRowCountOrDateTampering()throws Exception {
        var p=publish("java_scores_one",D);
        try(var c=DriverManager.getConnection("jdbc:sqlite:"+root.resolve("_catalog/resource_catalog.sqlite3"));var q=c.createStatement()){q.executeUpdate("UPDATE resource_metadata SET row_count=2 WHERE role='score_panel'");}
        assertThrows(IllegalStateException.class,()->new ScoreArtifactPublisher(root).latest(D));
        assertTrue(Files.isRegularFile(p.panelRef().path()));
    }
    @Test void sourceProofMustBeCompleteBeforePublishingAndArtifactTamperingIsRejected()throws Exception {
        var publisher=new ScoreArtifactPublisher(root);
        assertThrows(IllegalArgumentException.class,()->publisher.publish("java_scores_bad",panel(D),coverage(D),List.of(D),RECIPE,Map.of("sourceComplete",false)));
        assertFalse(Files.exists(root.resolve("_catalog/resource_catalog.sqlite3")));
        var p=publish("java_scores_good",D);Files.writeString(p.coverageRef().path(),"bad bytes",StandardOpenOption.TRUNCATE_EXISTING);
        assertThrows(IllegalStateException.class,()->publisher.latest(D));
    }
    @Test void olderPublicationCannotRegressCompleteFrontier()throws Exception {
        publish("java_scores_new",D);assertThrows(IllegalStateException.class,()->publish("java_scores_old",D.minusDays(1)));
        assertEquals("java_scores_new",new ScoreArtifactPublisher(root).latest(D).producerRunId());
    }
    @Test void weeklyPlanCanReadItsOriginalPairAfterDailyAliasesAdvance()throws Exception {
        var first=publish("java_scores_weekly",D);publish("java_scores_daily",D.plusDays(1));
        var p=new ScoreArtifactPublisher(root);assertEquals(first,p.byRun("java_scores_weekly"));
        assertEquals(first,p.forDate(D));assertThrows(IllegalStateException.class,()->p.forDate(D.minusDays(1)));
        assertEquals("java_scores_daily",p.latest(D).producerRunId());
    }
    @Test void changedSourceBeforeCommitRollsBackBothAliases()throws Exception {
        var old=publish("java_scores_settled",D);var p=new ScoreArtifactPublisher(root);
        assertThrows(IllegalStateException.class,()->p.publish("java_scores_changed",panel(D.plusDays(1)),coverage(D.plusDays(1)),List.of(D.plusDays(1)),RECIPE,proof(D.plusDays(1)),()->{throw new IllegalStateException("WAL advanced");}));
        assertEquals(old,p.latest(D));assertThrows(IllegalStateException.class,()->p.byRun("java_scores_changed"));
    }
    @Test void frameRejectsRootTraversalBeforeCreatingFiles() {
        assertThrows(IllegalArgumentException.class,()->new ScoreArtifactPublisher(root).frame("../escape/","score_panel",panel(D)));
    }
}
