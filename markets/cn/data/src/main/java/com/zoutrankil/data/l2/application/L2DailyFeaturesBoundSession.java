package com.zoutrankil.data.l2.application;

import com.zoutrankil.data.domain.l2.L2DailyFeatures;
import com.zoutrankil.data.domain.l2.L2DailyFeaturesKey;
import com.zoutrankil.data.l2.port.L2DailyFeaturesTarget;
import com.zoutrankil.data.l2.port.L2DailyFeaturesWriteSession;
import com.zoutrankil.data.service.VerifiedBatchExecutor;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/** 每次读写前后固定原物理对象；只读再验证不能退回写入路径。 */
final class L2DailyFeaturesBoundSession implements L2DailyFeaturesWriteSession.Versioned {
    private final L2DailyFeaturesTarget target;
    private final String identity;
    private final L2DailyFeaturesWriteSession delegate;
    private final int version;
    private final boolean writable;

    L2DailyFeaturesBoundSession(L2DailyFeaturesTarget target, String identity, boolean writable) {
        this.target = Objects.requireNonNull(target);
        this.identity = Objects.requireNonNull(identity);
        this.version = L2DailyFeaturesTarget.versionOf(target);
        this.writable = writable;
        requireIdentity();
        this.delegate = Objects.requireNonNull(target.newWriter());
        int actual =
                delegate instanceof L2DailyFeaturesWriteSession.Versioned selected
                        ? selected.datasetVersion()
                        : 1;
        if (!Objects.equals(delegate.tableName(), target.tableName()))
            throw new IllegalArgumentException("L2 会话物理表与原作用域不同");
        if (actual != version) throw new IllegalArgumentException("L2 会话与原目标布局不同");
        requireIdentity();
    }

    private void requireIdentity() {
        try {
            if (!identity.equals(target.targetId())) throw new IllegalStateException("L2 原物理目标已变化");
        } catch (RuntimeException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new IllegalStateException("无法核对 L2 原物理目标", failure);
        }
    }

    @Override
    public int datasetVersion() {
        return version;
    }

    @Override
    public String tableName() {
        return delegate.tableName();
    }

    @Override
    public VerifiedBatchExecutor.Codec<L2DailyFeatures, L2DailyFeaturesKey> codec() {
        return delegate.codec();
    }

    @Override
    public void preflight() {
        requireIdentity();
        delegate.preflight();
        requireIdentity();
    }

    @Override
    public void submissionRecorded(VerifiedBatchExecutor.Submission submission) throws Exception {
        if (!writable) throw new IllegalStateException("L2 来源再验证只读");
        requireIdentity();
        delegate.submissionRecorded(submission);
        requireIdentity();
    }

    @Override
    public void send(List<L2DailyFeatures> rows) throws Exception {
        if (!writable) throw new IllegalStateException("L2 来源再验证只读");
        requireIdentity();
        delegate.send(rows);
        requireIdentity();
    }

    @Override
    public List<L2DailyFeatures> readback(List<L2DailyFeaturesKey> keys) throws Exception {
        requireIdentity();
        var rows = delegate.readback(keys);
        requireIdentity();
        return rows;
    }

    @Override
    public int recoveryReadbackBatchRows() {
        return delegate.recoveryReadbackBatchRows();
    }

    @Override
    public boolean walSettled() throws Exception {
        requireIdentity();
        boolean settled = delegate.walSettled();
        requireIdentity();
        return settled;
    }

    @Override
    public boolean uncertainSenderStopped() throws Exception {
        return delegate.uncertainSenderStopped();
    }

    @Override
    public int countRows(LocalDate date) {
        return countRows(date, List.of());
    }

    @Override
    public int countRows(LocalDate date, List<String> symbols) {
        requireIdentity();
        int count = delegate.countRows(date, symbols);
        requireIdentity();
        return count;
    }

    @Override
    public String stableVersion() {
        requireIdentity();
        String result = delegate.stableVersion();
        requireIdentity();
        if (result == null || result.isBlank()) throw new IllegalStateException("L2 稳定版本缺失");
        return result;
    }

    @Override
    public List<LocalDate> readExistingDates() {
        requireIdentity();
        var rows = delegate.readExistingDates();
        requireIdentity();
        if (rows.size() > 32) throw new IllegalStateException("L2 日期扫描超过原上限");
        return rows;
    }
}
