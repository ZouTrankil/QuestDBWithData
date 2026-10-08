package com.zoutrankil.data.derived.port;

import java.nio.file.Path;
/** The native sentiment target and its physical publication table operations. */
public interface MarketSentimentDailyTarget {
    String table();void requireTarget();MarketSentimentDailyWriteSession newWriter();
    void requireNoPendingPublication(Path ledgerPath)throws Exception;
    boolean tableExists(String name);boolean identityMatches(String name,long id);void rename(String from,String to);
}
