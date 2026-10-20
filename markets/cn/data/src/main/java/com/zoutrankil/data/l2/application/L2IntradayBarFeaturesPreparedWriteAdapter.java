package com.zoutrankil.data.l2.application;

import com.zoutrankil.data.domain.SyncJobDefinition.FrozenRequest;
import com.zoutrankil.data.domain.l2.L2IntradayBarFeatures;
import com.zoutrankil.data.domain.l2.L2IntradayBarFeaturesKey;
import com.zoutrankil.data.l2.mapper.L2IntradayBarFeaturesMapper;
import com.zoutrankil.data.repository.SyncRunLedger;
import com.zoutrankil.data.service.DatasetIntervalLock;
import com.zoutrankil.data.service.PreparedWriteAdapter;
import com.zoutrankil.data.service.SyncJobRunner;
import com.zoutrankil.data.service.WriteGroupMemberAdapter;
import com.zoutrankil.data.service.WriteGroupPlan;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.function.BooleanSupplier;

/** Adds D085 receipt and full-source equality checks to the generic prepared writer. */
public final class L2IntradayBarFeaturesPreparedWriteAdapter implements WriteGroupMemberAdapter {
    private final L2IntradayBarFeaturesJobService owner;
    private final PreparedWriteAdapter<L2IntradayBarFeatures, L2IntradayBarFeaturesKey> delegate;
    private final L2IntradayBarFeaturesMapper mapper = new L2IntradayBarFeaturesMapper();

    public L2IntradayBarFeaturesPreparedWriteAdapter(
            L2IntradayBarFeaturesJobService owner,
            PreparedWriteAdapter<L2IntradayBarFeatures, L2IntradayBarFeaturesKey> delegate) {
        this.owner = Objects.requireNonNull(owner);
        this.delegate = Objects.requireNonNull(delegate);
    }

    @Override
    public WriteGroupPlan.Member member() {
        return delegate.member();
    }

    @Override
    public FrozenRequest request() {
        return delegate.request();
    }

    private void verifySource() throws Exception {
        List<L2IntradayBarFeatures> rows =
                delegate.member().batch().rows().stream().map(mapper::fromValues).toList();
        owner.verifyPreparedWriteRows(rows);
    }

    @Override
    public void preflight(FrozenRequest request) throws Exception {
        verifySource();
        delegate.preflight(request);
    }

    @Override
    public SyncJobRunner.Result execute(
            SyncRunLedger ledger,
            DatasetIntervalLock locks,
            String child,
            String parent,
            String prior,
            String target,
            FrozenRequest request,
            BooleanSupplier cancelled)
            throws Exception {
        verifySource();
        return delegate.execute(ledger, locks, child, parent, prior, target, request, cancelled);
    }

    @Override
    public String revalidate(
            SyncRunLedger ledger,
            String prior,
            String target,
            FrozenRequest request,
            BooleanSupplier cancelled,
            Path evidence)
            throws Exception {
        verifySource();
        return delegate.revalidate(ledger, prior, target, request, cancelled, evidence);
    }
}
