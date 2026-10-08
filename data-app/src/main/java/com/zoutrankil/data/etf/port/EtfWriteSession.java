package com.zoutrankil.data.etf.port;

import com.zoutrankil.data.sync.port.VerifiedWriteSession;
import java.time.LocalDate;
import java.util.List;

/** A fresh writer session is required for each plan or execution attempt. */
public interface EtfWriteSession<T, K> extends VerifiedWriteSession<T, K> {
    List<LocalDate> readExistingDates();
    List<T> readDate(LocalDate date);
}
