package com.zoutrankil.data.service;

import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.repository.SyncRunLedger;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.*;

/** Ordered, fail-fast composition. Each child remains an ordinary single-dataset run. */
public final class SyncGroupRunner {
    public record Window(LocalDate from, LocalDate to) {
        public Window {
            if ((from == null) != (to == null) || from != null && to.isBefore(from))
                throw new IllegalArgumentException("Complete ordered group window required");
        }
        public static Window none() { return new Window(null, null); }
    }
    public record MemberInput(SyncJobDefinition.Mode mode, Map<String, ?> parameters,
                              Window window, String targetId) {
        public MemberInput {
            parameters = Map.copyOf(parameters);
            SyncJobDefinition.name(targetId);
        }
    }
    public record Request(LocalDate logicalDate, Window commonWindow, Map<String, MemberInput> members,
                          Map<String, ?> commonParameters) {
        public Request {
            Objects.requireNonNull(logicalDate);
            Objects.requireNonNull(commonWindow);
            members = Map.copyOf(members);
            commonParameters = Map.copyOf(commonParameters);
        }
        public Request(LocalDate logicalDate, Window commonWindow, Map<String, MemberInput> members) {
            this(logicalDate, commonWindow, members, Map.of());
        }
    }
    public record MemberOutcome(int ordinal, String jobId, String childRunId,
                                SyncRunState state, boolean reused, int verifiedRows) {}
    public record Result(String runId, SyncRunState state, List<MemberOutcome> members) {
        public Result { members = List.copyOf(members); }
    }
    @FunctionalInterface public interface ChildExecutor {
        SyncJobRunner.Result execute(String childRunId, String parentRunId, String priorChildRunId,
                                     String targetId, SyncJobDefinition.FrozenRequest request) throws Exception;
        /** Read-only source/target verification, returning a durable receipt path. Never replay writes here. */
        default String revalidateCompleted(String priorChildRunId, String targetId,
                                           SyncJobDefinition.FrozenRequest request) throws Exception {
            throw new IllegalStateException("Completed child requires current readback verification");
        }
    }
    private record Prepared(int ordinal, SyncGroupDefinition.Member member,
                            String targetId, SyncJobDefinition.FrozenRequest request) {}
    private final SyncGroupRegistry groups;
    private final SyncJobRegistry jobs;
    private final SyncRunLedger ledger;

    public SyncGroupRunner(SyncGroupRegistry groups, SyncJobRegistry jobs, SyncRunLedger ledger) {
        this.groups = Objects.requireNonNull(groups);
        this.jobs = Objects.requireNonNull(jobs);
        this.ledger = Objects.requireNonNull(ledger);
    }
    public Result run(String runId, String groupId, int version, Request request,
                      ChildExecutor executor) throws Exception {
        return execute(runId, null, groupId, version, request, executor);
    }
    public Result resume(String runId, String priorGroupRunId, String groupId, int version,
                         Request request, ChildExecutor executor) throws Exception {
        return execute(runId, Objects.requireNonNull(priorGroupRunId), groupId, version, request, executor);
    }
    private Result execute(String runId, String prior, String groupId, int version,
                           Request request, ChildExecutor executor) throws Exception {
        Objects.requireNonNull(executor);
        var group = groups.require(groupId, version);
        if (!group.enabled()) throw new IllegalArgumentException("Group is disabled");
        var prepared = prepare(group, request);
        var json = JobDefinitionJson.mapper();
        var snapshot = new LinkedHashMap<String, Object>();
        snapshot.put("group", group);
        snapshot.put("logicalDate", request.logicalDate());
        snapshot.put("members", prepared.stream().map(p -> Map.of("ordinal", p.ordinal(),
                "jobId", p.member().job().jobId(), "targetId", p.targetId(),
                "frozenRequest", SyncRequestIdentity.snapshotJson(p.request()))).toList());
        String frozenJson = json.writeValueAsString(snapshot);
        List<SyncRunLedger.GroupMember> previous = prior == null ? List.of() : priorMembers(prior, frozenJson, prepared);
        ledger.createGroupRun(new SyncRunLedger.Run(runId, prior, groupId, version,
                request.logicalDate().toString(), "group-control", frozenJson),
                prepared.stream().map(p -> p.member().job()).toList());
        ledger.transition(runId, 0, SyncRunState.RUNNING, "{}");
        var outcomes = new ArrayList<MemberOutcome>();
        var reusedEvidence = new LinkedHashMap<String, String>();
        for (var member : prepared) {
            if (Thread.currentThread().isInterrupted() || ledger.cancellationRequested(runId))
                return stop(runId, SyncRunState.CANCELLED, outcomes, "group-cancelled-before-next-child");
            var earlier = prior == null ? null : previous.get(member.ordinal());
            String oldChild = earlier == null ? null : earlier.childRunId();
            SyncRunState oldState = oldChild == null ? null : childStateIfPresent(oldChild);
            if (oldState == SyncRunState.VERIFIED || oldState == SyncRunState.VERIFIED_EMPTY) {
                requireChildIdentity(oldChild, member, null);
                try {
                    String receipt = executor.revalidateCompleted(oldChild, member.targetId(), member.request());
                    if (receipt == null || receipt.isBlank() || receipt.length() > 4096)
                        throw new IllegalStateException("Current child readback receipt required");
                    reusedEvidence.put(oldChild, receipt);
                } catch (Exception failedReadback) {
                    return stop(runId, outcomes.isEmpty() ? SyncRunState.FAILED : SyncRunState.PARTIAL,
                            outcomes, "completed-child-readback:" + failedReadback.getClass().getSimpleName());
                }
                ledger.assignGroupMember(runId, member.ordinal(), oldChild);
                outcomes.add(new MemberOutcome(member.ordinal(), member.member().job().jobId(),
                        oldChild, oldState, true, 0));
                continue;
            }
            String childId = "run-" + UUID.randomUUID();
            ledger.assignGroupMember(runId, member.ordinal(), childId);
            try {
                var result = executor.execute(childId, runId, oldChild, member.targetId(), member.request());
                if (result == null || !childId.equals(result.runId()))
                    throw new IllegalStateException("Child executor did not return reserved run");
                requireChildIdentity(childId, member, runId);
                if (ledger.get(childId).state() != result.state())
                    throw new IllegalStateException("Child result differs from ledger");
                outcomes.add(new MemberOutcome(member.ordinal(), member.member().job().jobId(),
                        childId, result.state(), false, result.verifiedRows()));
                if (result.state() != SyncRunState.VERIFIED && result.state() != SyncRunState.VERIFIED_EMPTY)
                    return stop(runId, result.state() == SyncRunState.IN_DOUBT
                            ? SyncRunState.IN_DOUBT : result.state() == SyncRunState.CANCELLED
                            ? SyncRunState.CANCELLED : outcomes.stream().anyMatch(o -> o.state() == SyncRunState.VERIFIED
                            || o.state() == SyncRunState.VERIFIED_EMPTY) ? SyncRunState.PARTIAL : SyncRunState.FAILED,
                            outcomes, "child-not-verified");
            } catch (Exception uncertain) {
                return stop(runId, SyncRunState.IN_DOUBT, outcomes,
                        "child-execution-exception:" + uncertain.getClass().getSimpleName());
            }
        }
        String refs = String.join(";", outcomes.stream().map(o -> "ledger:" + o.childRunId()).toList());
        String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(frozenJson.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        if (outcomes.stream().allMatch(o -> o.state() == SyncRunState.VERIFIED_EMPTY)) {
            String emptyProof = json.writeValueAsString(Map.of("sourceComplete", true,
                    "returnedRows", 0, "submittedRows", 0, "responseEvidence", refs,
                    "reusedChildReadback", reusedEvidence, "sourceFingerprint", digest));
            ledger.transition(runId, ledger.get(runId).revision(), SyncRunState.VERIFIED_EMPTY, emptyProof);
            return new Result(runId, SyncRunState.VERIFIED_EMPTY, outcomes);
        }
        String proof = json.writeValueAsString(Map.of("metric", "verifiedChildRuns",
                "reusedChildReadback", reusedEvidence, "verification", Map.of(
                "passed", true, "expectedRows", outcomes.size(), "actualRows", outcomes.size(),
                "matchedRows", outcomes.size(), "mismatchedRows", 0, "duplicateKeys", 0,
                "missingKeys", 0, "sourceFingerprint", digest, "readbackEvidence", refs)));
        ledger.transition(runId, ledger.get(runId).revision(), SyncRunState.VERIFIED, proof);
        return new Result(runId, SyncRunState.VERIFIED, outcomes);
    }
    private List<Prepared> prepare(SyncGroupDefinition group, Request request) {
        var ordered = group.orderedMembers();
        if (!ordered.stream().map(m -> m.job().jobId()).collect(java.util.stream.Collectors.toSet())
                .equals(request.members().keySet()))
            throw new IllegalArgumentException("Explicit input required for each group member only");
        var result = new ArrayList<Prepared>();
        var overrides = new LinkedHashMap<String, SyncGroupPlan.Override>();
        for (var member : ordered) {
            var input = Objects.requireNonNull(request.members().get(member.job().jobId()));
            overrides.put(member.job().jobId(), new SyncGroupPlan.Override(input.mode(),
                    input.window() == null ? null : new SyncGroupPlan.Window(input.window().from(), input.window().to()),
                    input.parameters()));
        }
        var plan = SyncGroupPlan.prepare(groups, jobs, group.groupId(), group.version(), request.logicalDate(),
                null, new SyncGroupPlan.Window(request.commonWindow().from(), request.commonWindow().to()),
                request.commonParameters(), overrides);
        for (int ordinal = 0; ordinal < ordered.size(); ordinal++) {
            var member = ordered.get(ordinal);
            var input = Objects.requireNonNull(request.members().get(member.job().jobId()));
            var frozen = plan.requests().get(ordinal);
            result.add(new Prepared(ordinal, member, input.targetId(), frozen));
        }
        return List.copyOf(result);
    }
    private List<SyncRunLedger.GroupMember> priorMembers(String prior, String frozenJson,
                                                          List<Prepared> prepared) throws Exception {
        var run = ledger.getRun(prior);
        var state = ledger.get(prior).state();
        if (!state.terminal()) throw new IllegalStateException("Prior group is active or in doubt");
        var mapper = JobDefinitionJson.mapper();
        if (!mapper.readTree(run.frozenJson()).equals(mapper.readTree(frozenJson)))
            throw new IllegalArgumentException("Group definition, parameters or targets changed");
        var members = ledger.groupMembers(prior);
        if (members.size() != prepared.size()) throw new IllegalStateException("Prior group slots incomplete");
        for (int i = 0; i < members.size(); i++) {
            var old = members.get(i);
            if (old.ordinal() != i || !old.jobId().equals(prepared.get(i).member().job().jobId())
                    || old.jobVersion() != prepared.get(i).member().job().version())
                throw new IllegalStateException("Prior group member identity changed");
            if (old.childRunId() != null) {
                var childState = childStateIfPresent(old.childRunId());
                if (childState != null && !childState.terminal())
                    throw new IllegalStateException("Prior child active or in doubt");
            }
        }
        return members;
    }
    private SyncRunState childStateIfPresent(String id) throws Exception {
        try { return ledger.get(id).state(); }
        catch (IllegalArgumentException missing) { return null; }
    }
    private void requireChildIdentity(String childId, Prepared member, String expectedParent) throws Exception {
        var run = ledger.getRun(childId);
        var job = member.member().job();
        if (expectedParent != null && !expectedParent.equals(run.parentRunId())
                || !run.jobId().equals(job.jobId()) || run.jobVersion() != job.version()
                || !run.targetId().equals(member.targetId())
                || !SyncRequestIdentity.fingerprint(run.frozenJson(), run.targetId()).equals(
                        SyncRequestIdentity.fingerprint(member.request(), member.targetId())))
            throw new IllegalStateException("Child run differs from frozen group member");
    }
    private Result stop(String runId, SyncRunState state, List<MemberOutcome> outcomes,
                        String reason) throws Exception {
        ledger.transition(runId, ledger.get(runId).revision(), state,
                JobDefinitionJson.mapper().writeValueAsString(Map.of("errorCode", reason)));
        return new Result(runId, state, outcomes);
    }
}
