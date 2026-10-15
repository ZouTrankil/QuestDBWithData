package com.zoutrankil.data.service;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zoutrankil.batch.BusinessState;
import com.zoutrankil.batch.CompletionEvidence;
import com.zoutrankil.data.domain.JobDefinitionJson;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MainStrategyPlanAuthorityTest {
    private static final LocalDate DAY=LocalDate.of(2026,9,30);
    private static ObjectNode authority() {
        return JobDefinitionJson.mapper().createObjectNode().put("planId","weekly-plan-1")
                .put("contentSha256","a".repeat(64)).put("scoreRun","java_scores_weekly");
    }
    private static CompletionEvidence stage(String artifact,String hash) {
        return new CompletionEvidence(1,"java.main_strategy_daily","daily-instance","OfficialPlanData",
                "daily-instance",DAY,DAY,DAY,DAY,DAY,"2","stage-sha256:"+hash,"frozen-input",1,1,
                true,true,true,true,true,false,Instant.parse("2026-09-30T13:00:00Z"),BusinessState.VERIFIED,artifact,null);
    }
    @Test void unchangedWeeklyAuthorityRemainsValidWhenDailyScoreAliasAdvances() {
        // The weekly bound score run remains authoritative; daily score publication is a separate input.
        var saved=authority();var current=authority().put("unrelatedLatestDailyScoreRun","java_scores_next_day");
        assertDoesNotThrow(()->MainStrategyDailyPipeline.requireSamePlanAuthority(saved,current));
    }
    @Test void sameTargetsWithChangedWeightOrStateCannotReuseAnOldCertificate() {
        var current=authority().put("contentSha256","b".repeat(64));
        var failure=assertThrows(Exception.class,()->MainStrategyDailyPipeline.requireSamePlanAuthority(authority(),current));
        assertTrue(failure.getMessage().contains("contentSha256"));
    }
    @Test void replacingAPlanWithIdenticalContentStillRequiresItsOwnAuthorityEvidence() {
        var current=authority().put("planId","weekly-plan-2");
        assertThrows(Exception.class,()->MainStrategyDailyPipeline.requireSamePlanAuthority(authority(),current));
    }
    @Test void rebindingAnUnchangedPlanToAnotherScoringRunIsRejected() {
        var current=authority().put("scoreRun","java_scores_revised_training");
        assertThrows(Exception.class,()->MainStrategyDailyPipeline.requireSamePlanAuthority(authority(),current));
    }
    @Test void legacyCertificateWithoutAuthorityCannotClaimCurrentPlanReadiness() {
        assertThrows(Exception.class,()->MainStrategyDailyPipeline.requireSamePlanAuthority(
                JobDefinitionJson.mapper().createObjectNode(),authority()));
        assertThrows(Exception.class,()->MainStrategyDailyPipeline.requireSamePlanAuthority(authority(),authority().put("scoreRun","")));
    }
    @Test void acceptanceMustBindTheExactOfficialStageFromTheSameRun() {
        var original=stage("owned/official-original.json","c".repeat(64));
        assertDoesNotThrow(()->MainStrategyDailyPipeline.requireSamePlanStage(original,original));
        var replacement=stage("owned/official-replacement.json","d".repeat(64));
        assertThrows(Exception.class,()->MainStrategyDailyPipeline.requireSamePlanStage(original,replacement));
        assertThrows(Exception.class,()->MainStrategyDailyPipeline.requireSamePlanStage(null,original));
    }
}
