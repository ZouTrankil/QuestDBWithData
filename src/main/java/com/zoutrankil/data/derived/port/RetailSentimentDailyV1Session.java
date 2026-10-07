package com.zoutrankil.data.derived.port;

import com.zoutrankil.data.domain.RetailSentimentDailyV1;
import com.zoutrankil.data.domain.RetailSentimentDailyV1Snapshot;
import com.zoutrankil.data.derived.domain.RetailSentimentDailyV1FullSourceScope;
import com.zoutrankil.data.calendar.port.ExchangeCalendarReadPort;
import com.zoutrankil.data.service.VerifiedBatchExecutor;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.function.BooleanSupplier;

/** One physical native-refresh operation and its pinned source/readback state. */
public interface RetailSentimentDailyV1Session extends VerifiedBatchExecutor.Port<RetailSentimentDailyV1, LocalDate> {
    RetailSentimentDailyV1Snapshot snapshot();
    String targetId();
    String calendarVersion();
    ExchangeCalendarReadPort newCalendarReader();
    long sourceRawRows(LocalDate start, LocalDate end);
    List<RetailSentimentDailyV1> expected(LocalDate start, LocalDate end);
    List<RetailSentimentDailyV1> actual(LocalDate start, LocalDate end);
    long outputRowCount();
    void bind(LocalDate start, LocalDate end, RetailSentimentDailyV1Snapshot expected);
    void cancellationProbe(BooleanSupplier probe);
    void createIsolatedTarget();
    void configureFullIsolated();
    RetailSentimentDailyV1FullSourceScope fullSourceScope();
    void verifyPrivateInstance();
    @Override void preflight();
    @Override void send(List<RetailSentimentDailyV1> rows);
    @Override List<RetailSentimentDailyV1> readback(List<LocalDate> keys);
    @Override boolean walSettled();
    @Override boolean uncertainSenderStopped();
    boolean unresolved();
    RetailSentimentDailyV1Snapshot verifiedSnapshot();
    Duration visibilityTimeout();
    void requireSourceCoverage(LocalDate start, LocalDate end, long expectedRows, List<RetailSentimentDailyV1> expected);
}
