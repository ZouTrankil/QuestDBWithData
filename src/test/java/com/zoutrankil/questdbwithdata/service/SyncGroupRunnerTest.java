package com.zoutrankil.questdbwithdata.service;

import com.zoutrankil.questdbwithdata.domain.*;
import com.zoutrankil.questdbwithdata.repository.SyncRunLedger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.*;
import java.util.*;

import static com.zoutrankil.questdbwithdata.domain.SyncJobDefinition.*;
import static org.junit.jupiter.api.Assertions.*;

class SyncGroupRunnerTest {
    @TempDir Path root;
    private static final LocalDate DAY = LocalDate.of(2026, 9, 29);
    private static JobRef ref(String id) { return new JobRef(id, 1); }
    private static SyncGroupDefinition.Member member(String id, String... after) {
        return new SyncGroupDefinition.Member(ref(id), Arrays.stream(after).map(SyncGroupRunnerTest::ref).toList());
    }
    private static SyncJobDefinition job(String id, boolean enabled, boolean daily, List<JobRef> dependencies) {
        return new SyncJobDefinition(id, 1, "stock_basic_snapshot", 1, "sample",
                Set.of(Mode.SNAPSHOT), Mode.SNAPSHOT, Map.of(), "rate", "slice", "verify",
                new RetryPolicy(1, Duration.ofMillis(1), Duration.ofSeconds(1)), Duration.ofSeconds(10),
                new Budget(1, 1, 1, 1, 4096), 0, dependencies, Frequency.MANUAL,
                ZoneOffset.UTC, enabled, daily);
    }
    private static SyncJobRegistry jobs(SyncJobDefinition... definitions) {
        return new SyncJobRegistry(List.of(definitions),
                new DatasetRegistry(List.of(() -> StockBasicDataset.DEFINITION)),
                Map.of("stock_basic_snapshot", Set.of(Mode.SNAPSHOT)),
                new SyncJobRegistry.Policies(Set.of("rate"), Set.of("slice"), Set.of("verify")));
    }
    private static SyncGroupRunner.Request request(String... jobs) {
        var inputs = new LinkedHashMap<String, SyncGroupRunner.MemberInput>();
        for (String id : jobs) inputs.put(id, new SyncGroupRunner.MemberInput(null, Map.of(), null, "target." + id));
        return new SyncGroupRunner.Request(DAY, SyncGroupRunner.Window.none(), inputs);
    }
    private static String verifiedProof(String childId) {
        return "{\"verification\":{\"passed\":true,\"expectedRows\":1,\"actualRows\":1,"
                + "\"matchedRows\":1,\"mismatchedRows\":0,\"duplicateKeys\":0,\"missingKeys\":0,"
                + "\"sourceFingerprint\":\"source-" + childId + "\",\"readbackEvidence\":\"readback-" + childId + "\"}}";
    }

    @Test void orderedFailFastGroupResumesOnlyIncompleteMembersAndPreservesParentLinks() throws Exception {
        var catalog = jobs(job("job.a", true, false, List.of()),
                job("job.b", true, false, List.of(ref("job.a"))),
                job("job.c", true, false, List.of()));
        var group = new SyncGroupDefinition("group.test", 1,
                List.of(member("job.c", "job.b"), member("job.b", "job.a"), member("job.a")), true, false);
        var registry = new SyncGroupRegistry(List.of(group), catalog);
        Path path = root.resolve("ledger.sqlite3");
        var ledger = new SyncRunLedger(path);
        var runner = new SyncGroupRunner(registry, catalog, ledger);
        var called = new ArrayList<String>();
        SyncGroupRunner.ChildExecutor firstExecutor = (child, parent, prior, target, frozen) -> {
            String id = frozen.definition().jobId(); called.add(id);
            ledger.createRun(child, parent, target, frozen);
            ledger.transition(child, 0, SyncRunState.RUNNING, "{}");
            if (id.equals("job.b")) {
                ledger.transition(child, 1, SyncRunState.FAILED, "{\"errorCode\":\"provider\"}");
                return new SyncJobRunner.Result(child, SyncRunState.FAILED, 0, 0, "provider");
            }
            ledger.transition(child, 1, SyncRunState.VERIFIED, verifiedProof(child));
            return new SyncJobRunner.Result(child, SyncRunState.VERIFIED, 1, 1, null);
        };
        var first = runner.run("group-first", "group.test", 1,
                request("job.a", "job.b", "job.c"), firstExecutor);
        assertEquals(SyncRunState.PARTIAL, first.state());
        assertEquals(List.of("job.a", "job.b"), called);
        assertEquals(2, first.members().size());
        var slots = ledger.groupMembers(first.runId());
        assertNotNull(slots.get(0).childRunId());
        assertNotNull(slots.get(1).childRunId());
        assertNull(slots.get(2).childRunId());

        called.clear();
        var resumed = new SyncGroupRunner(registry, catalog, new SyncRunLedger(path))
                .resume("group-resumed", "group-first", "group.test", 1,
                        request("job.a", "job.b", "job.c"), new SyncGroupRunner.ChildExecutor() {
                          public SyncJobRunner.Result execute(String child, String parent, String prior, String target,
                                  FrozenRequest frozen) throws Exception {
                            String id = frozen.definition().jobId(); called.add(id);
                            if (id.equals("job.b")) assertEquals(slots.get(1).childRunId(), prior);
                            ledger.createRun(child, parent, target, frozen);
                            ledger.transition(child, 0, SyncRunState.RUNNING, "{}");
                            ledger.transition(child, 1, SyncRunState.VERIFIED, verifiedProof(child));
                            return new SyncJobRunner.Result(child, SyncRunState.VERIFIED, 1, 1, null);
                          }
                          public String revalidateCompleted(String child, String target, FrozenRequest frozen) {
                              assertEquals(slots.getFirst().childRunId(), child);
                              return "unit-fixture-current-readback";
                          }
                        });
        assertEquals(SyncRunState.VERIFIED, resumed.state());
        assertEquals(List.of("job.b", "job.c"), called);
        assertTrue(resumed.members().get(0).reused());
        assertEquals(slots.get(0).childRunId(), ledger.groupMembers(resumed.runId()).get(0).childRunId());
        assertEquals("group-first", ledger.getRun(resumed.runId()).parentRunId());
        assertTrue(ledger.get(resumed.runId()).payloadJson().contains("unit-fixture-current-readback"));
        var rejected = runner.resume("group-without-recheck", "group-first", "group.test", 1,
                request("job.a", "job.b", "job.c"), (child, parent, prior, target, frozen) -> {
                    fail("Missing current readback must reject before any child executes");
                    return null;
                });
        assertEquals(SyncRunState.FAILED, rejected.state());
    }

    @Test void unknownJobsAndEnabledDailySelectorsRemainDistinct() {
        var catalog = jobs(job("job.manual", true, false, List.of()),
                job("job.daily", true, true, List.of()), job("job.disabled", false, false, List.of()));
        var group = new SyncGroupDefinition("group.test", 1,
                List.of(member("job.manual"), member("job.daily")), true, false);
        var registry = new SyncGroupRegistry(List.of(group), catalog);
        assertEquals(2, registry.allEnabledJobs().size());
        assertEquals(List.of("job.daily"), registry.dailyEligibleJobs().stream().map(SyncJobDefinition::jobId).toList());
        assertThrows(IllegalArgumentException.class, () -> new SyncGroupRegistry(List.of(
                new SyncGroupDefinition("bad", 1, List.of(member("job.missing")), true, false)), catalog));
    }

    @Test void ledgerCannotVerifyGroupWithUnfinishedChildSlot() throws Exception {
        var ledger = new SyncRunLedger(root.resolve("ledger.sqlite3"));
        ledger.createGroupRun(new SyncRunLedger.Run("group-1", null, "group.test", 1,
                DAY.toString(), "group-control", "{}"), List.of(ref("job.a")));
        ledger.transition("group-1", 0, SyncRunState.RUNNING, "{}");
        assertThrows(IllegalStateException.class,
                () -> ledger.transition("group-1", 1, SyncRunState.VERIFIED, verifiedProof("group-1")));
        assertEquals(SyncRunState.RUNNING, ledger.get("group-1").state());
    }

    @Test void durableGroupCancellationPreservesFirstChildAndDoesNotStartSecond() throws Exception {
        var catalog = jobs(job("job.a", true, false, List.of()), job("job.b", true, false, List.of()));
        var group = new SyncGroupDefinition("group.test", 1,
                List.of(member("job.a"), member("job.b")), true, false);
        var ledger = new SyncRunLedger(root.resolve("cancel.sqlite3"));
        var runner = new SyncGroupRunner(new SyncGroupRegistry(List.of(group), catalog), catalog, ledger);
        var called = new ArrayList<String>();
        var result = runner.run("group-cancel", "group.test", 1, request("job.a", "job.b"),
                (child, parent, prior, target, frozen) -> {
                    called.add(frozen.definition().jobId());
                    ledger.createRun(child, parent, target, frozen);
                    ledger.transition(child, 0, SyncRunState.RUNNING, "{}");
                    ledger.transition(child, 1, SyncRunState.VERIFIED, verifiedProof(child));
                    assertTrue(ledger.requestCancellation(parent));
                    return new SyncJobRunner.Result(child, SyncRunState.VERIFIED, 1, 1, null);
                });
        assertEquals(SyncRunState.CANCELLED, result.state());
        assertEquals(List.of("job.a"), called);
        assertEquals(SyncRunState.VERIFIED, ledger.get(result.members().getFirst().childRunId()).state());
        assertNull(ledger.groupMembers(result.runId()).get(1).childRunId());
    }

    @Test void allEmptyChildrenProduceEmptyParentWithoutWriteSuccessClaim() throws Exception {
        var catalog = jobs(job("job.empty", true, false, List.of()));
        var group = new SyncGroupDefinition("group.empty", 1, List.of(member("job.empty")), true, false);
        var ledger = new SyncRunLedger(root.resolve("empty.sqlite3"));
        var runner = new SyncGroupRunner(new SyncGroupRegistry(List.of(group), catalog), catalog, ledger);
        var result = runner.run("group-empty", "group.empty", 1, request("job.empty"),
                (child, parent, prior, target, frozen) -> {
                    ledger.createRun(child, parent, target, frozen);
                    ledger.transition(child, 0, SyncRunState.RUNNING, "{}");
                    ledger.transition(child, 1, SyncRunState.VERIFIED_EMPTY,
                            "{\"sourceComplete\":true,\"returnedRows\":0,\"submittedRows\":0,"
                                    + "\"responseEvidence\":\"unit-empty-source\"}");
                    return new SyncJobRunner.Result(child, SyncRunState.VERIFIED_EMPTY, 0, 0, null);
                });
        assertEquals(SyncRunState.VERIFIED_EMPTY, result.state());
        assertEquals(SyncRunState.VERIFIED_EMPTY, ledger.get(result.runId()).state());
    }
}
