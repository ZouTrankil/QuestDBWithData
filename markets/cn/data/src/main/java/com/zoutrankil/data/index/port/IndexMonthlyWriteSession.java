package com.zoutrankil.data.index.port;

import com.zoutrankil.data.domain.index.IndexMonthly;
import com.zoutrankil.data.domain.index.IndexMonthlyKey;
import com.zoutrankil.data.index.domain.IndexMonthlyState;
import com.zoutrankil.data.sync.port.VerifiedWriteSession;

import java.time.LocalDate;
import java.util.List;

public interface IndexMonthlyWriteSession
        extends VerifiedWriteSession<IndexMonthly, IndexMonthlyKey> {
    @FunctionalInterface
    interface PublicationCallback {
        String publish() throws Exception;
    }

    void useStagingTarget(String stage, String physicalId);

    void configureFinalPublication(List<IndexMonthly> rows, PublicationCallback callback);

    boolean publicationComplete();

    List<IndexMonthly> readExistingRows(String code);

    IndexMonthlyState.TargetRange readExistingRange(String code);

    List<IndexMonthly> readRange(String code, LocalDate from, LocalDate to);
}
