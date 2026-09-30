package com.zoutrankil.questdbwithdata.service;

import com.zoutrankil.questdbwithdata.domain.L2EventResponseFeatures;
import com.zoutrankil.questdbwithdata.mapper.L2EventResponseFeaturesMapper;
import com.zoutrankil.questdbwithdata.repository.SyncRunLedger;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.function.BooleanSupplier;

/** Adds D085 receipt and full-source equality checks to the generic prepared writer. */
final class L2EventResponseFeaturesPreparedWriteAdapter implements WriteGroupMemberAdapter {
    private final L2EventResponseFeaturesJobService owner;
    private final PreparedWriteAdapter<L2EventResponseFeatures,
            com.zoutrankil.questdbwithdata.domain.L2EventResponseFeaturesKey> delegate;
    private final L2EventResponseFeaturesMapper mapper = new L2EventResponseFeaturesMapper();

    L2EventResponseFeaturesPreparedWriteAdapter(L2EventResponseFeaturesJobService owner,
            PreparedWriteAdapter<L2EventResponseFeatures,
                    com.zoutrankil.questdbwithdata.domain.L2EventResponseFeaturesKey> delegate) {
        this.owner = Objects.requireNonNull(owner);
        this.delegate = Objects.requireNonNull(delegate);
    }

    @Override public WriteGroupPlan.Member member() { return delegate.member(); }
    @Override public com.zoutrankil.questdbwithdata.domain.SyncJobDefinition.FrozenRequest request() {
        return delegate.request();
    }

    private void verifySource() throws Exception {
        List<L2EventResponseFeatures> rows = delegate.member().batch().rows().stream()
                .map(mapper::fromValues).toList();
        owner.verifyPreparedWriteRows(rows);
    }

    @Override public void preflight(com.zoutrankil.questdbwithdata.domain.SyncJobDefinition.FrozenRequest request)
            throws Exception {
        verifySource();
        delegate.preflight(request);
    }

    @Override public SyncJobRunner.Result execute(SyncRunLedger ledger, DatasetIntervalLock locks,
            String child, String parent, String prior, String target,
            com.zoutrankil.questdbwithdata.domain.SyncJobDefinition.FrozenRequest request,
            BooleanSupplier cancelled) throws Exception {
        verifySource();
        return delegate.execute(ledger, locks, child, parent, prior, target, request, cancelled);
    }

    @Override public String revalidate(SyncRunLedger ledger, String prior, String target,
            com.zoutrankil.questdbwithdata.domain.SyncJobDefinition.FrozenRequest request,
            BooleanSupplier cancelled, Path evidence) throws Exception {
        verifySource();
        return delegate.revalidate(ledger, prior, target, request, cancelled, evidence);
    }
}
