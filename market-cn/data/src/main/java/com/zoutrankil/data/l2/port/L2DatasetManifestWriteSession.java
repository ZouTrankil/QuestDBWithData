package com.zoutrankil.data.l2.port;

import com.zoutrankil.data.domain.L2DatasetManifest;
import com.zoutrankil.data.domain.L2DatasetManifestKey;
import com.zoutrankil.data.l2.domain.L2DatasetManifestRows;
import com.zoutrankil.data.service.VerifiedBatchExecutor;
import com.zoutrankil.data.sync.port.VerifiedWriteSession;
import java.time.LocalDate;
import java.util.List;

/** A fresh bounded D085 writer and its full-key encoding/readback contract. */
public interface L2DatasetManifestWriteSession extends VerifiedWriteSession<L2DatasetManifest,L2DatasetManifestKey> {
    VerifiedBatchExecutor.Codec<L2DatasetManifest,L2DatasetManifestKey> CODEC = new VerifiedBatchExecutor.Codec<>() {
        @Override public L2DatasetManifestKey key(L2DatasetManifest row) { return L2DatasetManifestRows.key(row); }
        @Override public byte[] canonicalBytes(L2DatasetManifest row) { return L2DatasetManifestRows.canonicalBytes(row); }
        @Override public int estimatedTransportBytes(L2DatasetManifest row,byte[] canonical) { return L2DatasetManifestRows.estimatedTransportBytes(row,canonical); }
    };
    String tableName();
    int countRows(LocalDate date);
    List<LocalDate> readExistingDates();
}
