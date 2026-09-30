package com.zoutrankil.data.service;

import com.zoutrankil.data.domain.L2T0TrainingLabels;
import com.zoutrankil.data.mapper.L2T0TrainingLabelsMapper;
import com.zoutrankil.data.repository.SyncRunLedger;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.function.BooleanSupplier;

/** Adds D085 receipt and full-source equality checks to the generic prepared writer. */
final class L2T0TrainingLabelsPreparedWriteAdapter implements WriteGroupMemberAdapter {
    private final L2T0TrainingLabelsJobService owner;
    private final PreparedWriteAdapter<L2T0TrainingLabels,
            com.zoutrankil.data.domain.L2T0TrainingLabelsKey> delegate;
    private final L2T0TrainingLabelsMapper mapper = new L2T0TrainingLabelsMapper();

    L2T0TrainingLabelsPreparedWriteAdapter(L2T0TrainingLabelsJobService owner,
            PreparedWriteAdapter<L2T0TrainingLabels,
                    com.zoutrankil.data.domain.L2T0TrainingLabelsKey> delegate) {
        this.owner = Objects.requireNonNull(owner);
        this.delegate = Objects.requireNonNull(delegate);
    }

    @Override public WriteGroupPlan.Member member() { return delegate.member(); }
    @Override public com.zoutrankil.data.domain.SyncJobDefinition.FrozenRequest request() {
        return delegate.request();
    }

    private void verifySource() throws Exception {
        List<L2T0TrainingLabels> rows = delegate.member().batch().rows().stream()
                .map(mapper::fromValues).toList();
        owner.verifyPreparedWriteRows(rows);
    }

    @Override public void preflight(com.zoutrankil.data.domain.SyncJobDefinition.FrozenRequest request)
            throws Exception {
        verifySource();
        delegate.preflight(request);
    }

    @Override public SyncJobRunner.Result execute(SyncRunLedger ledger, DatasetIntervalLock locks,
            String child, String parent, String prior, String target,
            com.zoutrankil.data.domain.SyncJobDefinition.FrozenRequest request,
            BooleanSupplier cancelled) throws Exception {
        verifySource();
        return delegate.execute(ledger, locks, child, parent, prior, target, request, cancelled);
    }

    @Override public String revalidate(SyncRunLedger ledger, String prior, String target,
            com.zoutrankil.data.domain.SyncJobDefinition.FrozenRequest request,
            BooleanSupplier cancelled, Path evidence) throws Exception {
        verifySource();
        return delegate.revalidate(ledger, prior, target, request, cancelled, evidence);
    }
}
