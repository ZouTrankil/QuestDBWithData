package com.zoutrankil.questdbwithdata.service;

import com.zoutrankil.questdbwithdata.domain.*;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import static com.zoutrankil.questdbwithdata.domain.SyncJobDefinition.*;
import static org.junit.jupiter.api.Assertions.*;

class SyncGroupPlanTest {
    private static final LocalDate DAY = LocalDate.of(2026, 9, 29);
    private SyncJobDefinition job(String id, boolean enabled, boolean daily) {
        return new SyncJobDefinition(id, 1, "stock_basic_snapshot", 1, "sample",
                Set.of(Mode.SNAPSHOT), Mode.SNAPSHOT,
                Map.of("codes", new Parameter(ParameterType.STRING_LIST, true, 20, 2, Set.of())),
                "shared", "codes", "values", new RetryPolicy(1, Duration.ofSeconds(1), Duration.ofSeconds(2)),
                Duration.ofSeconds(10), new Budget(10, 2, 2, 2, 1024), 0, List.of(),
                daily ? Frequency.DAILY : Frequency.MANUAL, ZoneOffset.UTC, enabled, daily);
    }
    private SyncJobRegistry jobs(boolean secondEnabled) {
        return new SyncJobRegistry(List.of(job("a", true, true), job("b", secondEnabled, false)),
                new DatasetRegistry(List.of(() -> StockBasicDataset.DEFINITION)),
                Map.of("stock_basic_snapshot", Set.of(Mode.SNAPSHOT)),
                new SyncJobRegistry.Policies(Set.of("shared"), Set.of("codes"), Set.of("values")));
    }
    private SyncGroupRegistry groups(SyncJobRegistry jobs, boolean daily) {
        return new SyncGroupRegistry(List.of(new SyncGroupDefinition("group.test", 1, List.of(
                new SyncGroupDefinition.Member(new JobRef("a", 1), List.of()),
                new SyncGroupDefinition.Member(new JobRef("b", 1), List.of(new JobRef("a", 1)))),
                true, daily)), jobs);
    }
    @Test void overridesAreFrozenAndMembersShareTheLogicalDate() {
        var jobs = jobs(true);
        var codes = new ArrayList<>(List.of("000001.SZ"));
        var overrides = new HashMap<String, SyncGroupPlan.Override>();
        overrides.put("b", new SyncGroupPlan.Override(null, null, Map.of("codes", List.of("600000.SH"))));
        var plan = SyncGroupPlan.prepare(groups(jobs, false), jobs, "group.test", 1, DAY,
                null, null, Map.of("codes", codes), overrides);
        codes.clear(); overrides.clear();
        assertEquals(List.of("a", "b"), plan.requests().stream().map(r -> r.definition().jobId()).toList());
        assertEquals(List.of("000001.SZ"), plan.requests().getFirst().parameters().get("codes"));
        assertEquals(List.of("600000.SH"), plan.requests().getLast().parameters().get("codes"));
        assertTrue(plan.requests().stream().allMatch(r -> DAY.equals(r.logicalDate())));
        assertTrue(plan.failFast()); assertEquals(1, plan.maxConcurrency());
        assertThrows(UnsupportedOperationException.class, () -> plan.requests().clear());
    }
    @Test void invalidLaterMemberRejectsTheWholePlanBeforeExecution() {
        var jobs = jobs(false);
        assertThrows(IllegalArgumentException.class, () -> SyncGroupPlan.prepare(groups(jobs, false), jobs,
                "group.test", 1, DAY, null, null, Map.of("codes", List.of("000001.SZ")), Map.of()));
        var enabled = jobs(true);
        assertThrows(IllegalArgumentException.class, () -> SyncGroupPlan.prepare(groups(enabled, false), enabled,
                "group.test", 1, DAY, null, null, Map.of("codes", List.of("000001.SZ")),
                Map.of("missing", new SyncGroupPlan.Override(null, null, Map.of()))));
        assertThrows(IllegalArgumentException.class, () -> SyncGroupPlan.prepare(groups(enabled, false), enabled,
                "group.test", 1, DAY, null, null, Map.of("typo", true), Map.of()));
    }
    @Test void allEnabledAndDailySelectionsRemainDistinct() {
        var jobs = jobs(true);
        var groups = groups(jobs, false);
        assertEquals(2, groups.allEnabledJobs().size());
        assertEquals(List.of("a"), groups.dailyEligibleJobs().stream().map(SyncJobDefinition::jobId).toList());
        assertThrows(IllegalArgumentException.class, () -> SyncGroupPlan.prepare(groups(jobs, true), jobs,
                "group.test", 1, DAY, null, null, Map.of("codes", List.of("000001.SZ")), Map.of()));
    }
    @Test void explicitEmptyMemberWindowClearsSharedWindow() {
        var jobs = jobs(true);
        var empty = new SyncGroupPlan.Override(null, new SyncGroupPlan.Window(null, null), Map.of());
        var plan = SyncGroupPlan.prepare(groups(jobs, false), jobs, "group.test", 1, DAY, null,
                new SyncGroupPlan.Window(DAY.minusDays(1), DAY), Map.of("codes", List.of("000001.SZ")),
                Map.of("a", empty, "b", empty));
        assertTrue(plan.requests().stream().allMatch(r -> r.from() == null && r.to() == null));
        assertThrows(IllegalArgumentException.class, () -> new SyncGroupPlan.Window(DAY, null));
    }
}
