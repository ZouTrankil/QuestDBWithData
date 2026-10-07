package com.zoutrankil.data.service;

import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.mapper.ThsMemberMapper;
import com.zoutrankil.data.repository.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.function.BooleanSupplier;
import static com.zoutrankil.data.domain.SyncJobDefinition.*;

/** A prepared one-board write uses the same stage and publication owner as a source run. */
public final class ThsMemberPreparedWriteAdapter implements WriteGroupMemberAdapter {
    private final WriteGroupPlan.Member member;
    private final FrozenRequest request;
    private final ThsMemberJobService owner;
    private final Path evidenceRoot;
    private final boolean resumed;
    private final ThsMemberMapper mapper = new ThsMemberMapper();
    private final String board;

    public ThsMemberPreparedWriteAdapter(WriteGroupPlan plan, String memberId,
                                         ThsMemberJobService owner, Path evidenceRoot, boolean resumed) {
        member = plan.members().stream().filter(m -> m.memberId().equals(memberId)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown THS member write group member"));
        if (!member.definition().equals(ThsMemberDataset.DEFINITION))
            throw new IllegalArgumentException("THS member definition required");
        this.owner = Objects.requireNonNull(owner);
        this.evidenceRoot = Objects.requireNonNull(evidenceRoot);
        this.resumed = resumed;
        var typed = materialize();
        board = typed.getFirst().boardCode();
        var parameter = new Parameter(ParameterType.STRING, true, 128, 1, Set.of());
        var definition = new SyncJobDefinition("write.ths_member", 1, member.definition().datasetId(),
                member.definition().schemaVersion(), member.definition().owner(), Set.of(Mode.INGEST), Mode.INGEST,
                Map.of("groupBatch", parameter, "memberBatch", parameter, "planFingerprint", parameter,
                        "payloadFingerprint", parameter, "board_code", parameter),
                "prepared.local", "prepared.static", "questdb.full_key_values",
                new RetryPolicy(1, Duration.ofSeconds(1), Duration.ofSeconds(30)),
                Duration.ofMinutes(30), new Budget(1, 1, 1, Math.max(1, typed.size()), 16 * 1024 * 1024),
                0, List.of(), Frequency.MANUAL, ZoneOffset.UTC, true, false);
        request = definition.freeze(null, Map.of("groupBatch", plan.batchId(), "memberBatch", member.batchId(),
                "planFingerprint", plan.fingerprint(), "payloadFingerprint", member.batch().fingerprint(),
                "board_code", board), null, null, plan.logicalDate());
    }

    @Override public WriteGroupPlan.Member member() { return member; }
    @Override public FrozenRequest request() { return request; }

    private List<ThsMember> materialize() {
        var typed = member.batch().rows().stream().map(mapper::fromValues).toList();
        if (typed.isEmpty() || typed.size() >= ThsMemberBoardStorage.MAX_BOARD_ROWS)
            throw new IllegalArgumentException("One bounded nonempty THS board batch required");
        String board = typed.getFirst().boardCode();
        if (typed.stream().anyMatch(row -> !board.equals(row.boardCode())))
            throw new IllegalArgumentException("THS member write batch spans boards");
        var roundTrip = DatasetWritePreparation.prepareWalReplace(member.definition(), typed, mapper::values,
                new DatasetWritePreparation.Limits(10000, 16 * 1024 * 1024));
        if (!roundTrip.fingerprint().equals(member.batch().fingerprint()))
            throw new IllegalArgumentException("THS member mapper changes frozen values");
        return typed;
    }

    @Override public void preflight(FrozenRequest actual) {
        if (!SyncRequestIdentity.fingerprint(request, member.targetId())
                .equals(SyncRequestIdentity.fingerprint(actual, member.targetId())))
            throw new IllegalArgumentException("THS member write request differs from frozen member");
        materialize();
        if (!resumed && !owner.targetId().equals(member.targetId()))
            throw new IllegalStateException("THS member target identity changed");
    }

    @Override public SyncJobRunner.Result execute(SyncRunLedger ledger, DatasetIntervalLock locks,
            String child, String parent, String prior, String target, FrozenRequest actual,
            BooleanSupplier cancelled) throws Exception {
        if (prior != null) {
            var entry = ledger.get(prior);
            if (!Set.of(SyncRunState.FAILED, SyncRunState.CANCELLED).contains(entry.state())
                    || locks.findOwned(prior, DatasetIntervalLock.Scope.allDates("ths_member")) != null)
                throw new IllegalStateException("Uncertain THS member write requires explicit reconciliation");
        }
        preflight(actual);
        if (!target.equals(member.targetId()) || !owner.targetId().equals(member.targetId())
                || cancelled.getAsBoolean())
            throw new java.util.concurrent.CancellationException("THS member write cancelled or changed target");
        var typed = materialize();
        Path folder = evidenceRoot.resolve(child); Files.createDirectories(folder);
        Path receipt = folder.resolve(member.memberId() + "-prepared-input.json");
        FileEvidenceStore.writeNewUtf8(receipt,JobDefinitionJson.mapper().writeValueAsString(Map.of(
                "sourceKind", "prepared-write-request", "memberId", member.memberId(),
                "batchId", member.batchId(), "targetId", member.targetId(), "board", board,
                "fingerprint", member.batch().fingerprint(), "rows", member.batch().rows())));
        var result = owner.executePrepared(child, parent, actual, typed, receipt);
        return new SyncJobRunner.Result(result.runId(), result.state(), result.sourceRows(),
                result.verifiedBoardRows(), result.errorCode());
    }

    @Override public String revalidate(SyncRunLedger ledger, String prior, String target, FrozenRequest actual,
                                       BooleanSupplier cancelled, Path evidence) throws Exception {
        if (cancelled.getAsBoolean()) throw new java.util.concurrent.CancellationException();
        return owner.revalidateGroupChild(prior, target, actual);
    }
}
