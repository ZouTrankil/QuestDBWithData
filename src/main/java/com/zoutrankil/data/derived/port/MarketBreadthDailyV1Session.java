package com.zoutrankil.data.derived.port;

import com.zoutrankil.data.domain.MarketBreadthDailyV1;
import com.zoutrankil.data.domain.MarketBreadthDailyV1Snapshot;
import com.zoutrankil.data.derived.domain.MarketBreadthDailyV1FullSourceScope;
import com.zoutrankil.data.calendar.port.ExchangeCalendarReadPort;
import com.zoutrankil.data.service.VerifiedBatchExecutor;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.function.BooleanSupplier;

/** One physical native-refresh operation and its pinned source/readback state. */
public interface MarketBreadthDailyV1Session extends VerifiedBatchExecutor.Port<MarketBreadthDailyV1, LocalDate> {
    MarketBreadthDailyV1Snapshot snapshot();
    String targetId();
    String calendarVersion();
    ExchangeCalendarReadPort newCalendarReader();
    long sourceRawRows(LocalDate start, LocalDate end);
    List<MarketBreadthDailyV1> expected(LocalDate start, LocalDate end);
    List<MarketBreadthDailyV1> actual(LocalDate start, LocalDate end);
    long outputRowCount();
    void bind(LocalDate start, LocalDate end, MarketBreadthDailyV1Snapshot expected);
    void cancellationProbe(BooleanSupplier probe);
    void createIsolatedTarget();
    void configureFullIsolated();
    MarketBreadthDailyV1FullSourceScope fullSourceScope();
    void verifyPrivateInstance();
    @Override void preflight();
    @Override void send(List<MarketBreadthDailyV1> rows);
    @Override List<MarketBreadthDailyV1> readback(List<LocalDate> keys);
    @Override boolean walSettled();
    @Override boolean uncertainSenderStopped();
    boolean unresolved();
    MarketBreadthDailyV1Snapshot verifiedSnapshot();
    Duration visibilityTimeout();
}
