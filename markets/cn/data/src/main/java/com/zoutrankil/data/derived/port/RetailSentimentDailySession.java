package com.zoutrankil.data.derived.port;

import com.zoutrankil.data.calendar.port.ExchangeCalendarReadPort;
import com.zoutrankil.data.derived.domain.RetailSentimentDailyFullSourceScope;
import com.zoutrankil.data.derived.domain.RetailSentimentDailyLayout;
import com.zoutrankil.data.domain.derived.RetailSentimentDaily;
import com.zoutrankil.data.domain.derived.RetailSentimentDailySnapshot;
import com.zoutrankil.data.service.VerifiedBatchExecutor;

import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.function.BooleanSupplier;

/** One physical native-refresh operation and its pinned source/readback state. */
public interface RetailSentimentDailySession
        extends VerifiedBatchExecutor.Port<RetailSentimentDaily, LocalDate> {
    interface Versioned extends RetailSentimentDailySession {
        RetailSentimentDailyLayout layout();
    }

    static RetailSentimentDailyLayout layoutOf(RetailSentimentDailySession session) {
        return session instanceof Versioned selected
                ? selected.layout()
                : RetailSentimentDailyLayout.LEGACY;
    }

    RetailSentimentDailySnapshot snapshot();

    String targetId();

    String calendarVersion();

    ExchangeCalendarReadPort newCalendarReader();

    long sourceRawRows(LocalDate start, LocalDate end);

    List<RetailSentimentDaily> expected(LocalDate start, LocalDate end);

    List<RetailSentimentDaily> actual(LocalDate start, LocalDate end);

    long outputRowCount();

    void bind(LocalDate start, LocalDate end, RetailSentimentDailySnapshot expected);

    void cancellationProbe(BooleanSupplier probe);

    void createIsolatedTarget();

    void configureFullIsolated();

    RetailSentimentDailyFullSourceScope fullSourceScope();

    void verifyPrivateInstance();

    @Override
    void preflight();

    @Override
    void send(List<RetailSentimentDaily> rows);

    @Override
    List<RetailSentimentDaily> readback(List<LocalDate> keys);

    @Override
    boolean walSettled();

    @Override
    boolean uncertainSenderStopped();

    boolean unresolved();

    RetailSentimentDailySnapshot verifiedSnapshot();

    Duration visibilityTimeout();

    void requireSourceCoverage(
            LocalDate start, LocalDate end, long expectedRows, List<RetailSentimentDaily> expected);
}
