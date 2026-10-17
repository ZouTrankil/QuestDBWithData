package com.zoutrankil.data.derived.port;

import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.domain.table.*;
import java.time.*;
import java.util.*;
import com.zoutrankil.data.derived.domain.MarketSentimentDailyRows;
import com.zoutrankil.data.sync.port.VerifiedWriteSession;
import com.zoutrankil.data.service.VerifiedBatchExecutor;
/** Independent typed writer session; public row/snapshot types retain their original names. */
public interface MarketSentimentDailyWriteSession extends VerifiedWriteSession<MarketSentimentDailyRow,Instant> {
    VerifiedBatchExecutor.Codec<MarketSentimentDailyRow,Instant> CODEC=new NativeDailyCodec<>(MarketSentimentDailyRows.projection());
    default VerifiedBatchExecutor.Codec<MarketSentimentDailyRow,Instant> codec(){return CODEC;}
    NativeDailyWindowSession<MarketSentimentDailyRow> nativePort();String table();String stage();
    MarketSentimentDailyTargetSnapshot snapshot(String table);MarketSentimentDailyTargetSnapshot formalSnapshot();
    void requireSame(MarketSentimentDailyTargetSnapshot expected);
    MarketSentimentDailyTargetSnapshot prepare(MarketSentimentDailyTargetSnapshot before,LocalDate from,LocalDate to)throws Exception;
    void preflight();List<MarketSentimentDailyRow> readback(List<Instant> keys);boolean walSettled();boolean uncertainSenderStopped();
    List<MarketSentimentDailyRow> readAll(String table);void requireSchema(String table);
}
