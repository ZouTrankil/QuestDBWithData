package com.zoutrankil.data.index.port;

import com.zoutrankil.data.domain.index.IndexDailyBasic;
import com.zoutrankil.data.domain.index.IndexDailyBasicKey;
import com.zoutrankil.data.index.domain.IndexDailyBasicTargetRange;
import com.zoutrankil.data.sync.port.VerifiedWriteSession;

import java.time.LocalDate;
import java.util.List;

/** One frozen physical generation and one execution attempt's writer state. */
public interface IndexDailyBasicWriteSession
        extends VerifiedWriteSession<IndexDailyBasic, IndexDailyBasicKey> {
    IndexDailyBasicTargetRange readExistingRange(String code);

    List<IndexDailyBasic> readExistingRows(String code);

    List<IndexDailyBasic> readRange(String code, LocalDate from, LocalDate to);
}
