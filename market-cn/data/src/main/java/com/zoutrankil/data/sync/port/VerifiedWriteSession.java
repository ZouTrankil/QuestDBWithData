package com.zoutrankil.data.sync.port;

import com.zoutrankil.data.service.VerifiedBatchExecutor;

/** A writer and its encoding contract, scoped to one plan or execution attempt. */
public interface VerifiedWriteSession<T, K> extends VerifiedBatchExecutor.Port<T, K> {
    @Override void preflight();
    VerifiedBatchExecutor.Codec<T, K> codec();
}
