package com.zoutrankil.data.derived.port;

import com.zoutrankil.data.domain.derived.MarketBreadthDaily;
import com.zoutrankil.data.domain.derived.MarketBreadthDailySnapshot;
import com.zoutrankil.data.derived.domain.MarketBreadthDailyFullSourceScope;
import com.zoutrankil.data.calendar.port.ExchangeCalendarReadPort;
import com.zoutrankil.data.service.VerifiedBatchExecutor;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.function.BooleanSupplier;

/** One physical native-refresh operation and its pinned source/readback state. */
public interface MarketBreadthDailySession extends VerifiedBatchExecutor.Port<MarketBreadthDaily, LocalDate> {
    MarketBreadthDailySnapshot snapshot();
    String targetId();
    String calendarVersion();
    ExchangeCalendarReadPort newCalendarReader();
    long sourceRawRows(LocalDate start, LocalDate end);
    List<MarketBreadthDaily> expected(LocalDate start, LocalDate end);
    List<MarketBreadthDaily> actual(LocalDate start, LocalDate end);
    long outputRowCount();
    void bind(LocalDate start, LocalDate end, MarketBreadthDailySnapshot expected);
    void cancellationProbe(BooleanSupplier probe);
    void createIsolatedTarget();
    void configureFullIsolated();
    MarketBreadthDailyFullSourceScope fullSourceScope();
    void verifyPrivateInstance();
    @Override void preflight();
    @Override void send(List<MarketBreadthDaily> rows);
    @Override List<MarketBreadthDaily> readback(List<LocalDate> keys);
    @Override boolean walSettled();
    @Override boolean uncertainSenderStopped();
    boolean unresolved();
    MarketBreadthDailySnapshot verifiedSnapshot();
    Duration visibilityTimeout();
}
