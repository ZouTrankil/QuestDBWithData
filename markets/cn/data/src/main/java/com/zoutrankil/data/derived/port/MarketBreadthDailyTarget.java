package com.zoutrankil.data.derived.port;

/** Shared configuration; a new mutable session is required for every operation. */
@FunctionalInterface
public interface MarketBreadthDailyTarget { MarketBreadthDailySession newSession(); }
