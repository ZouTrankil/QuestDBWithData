# D001 typed calendar mapping

Status: in progress. Dataset has not been admitted to the runtime registry yet.

ExchangeCalendar defines a business LocalDate, open/closed boolean and nullable prior trading LocalDate. Its key contains exchange and calendar date. The initial owner admits SSE/SZSE, matching the verified Python connector's scope. TushareTradeCalendarDto preserves wire text/Integer values, and ExchangeCalendarMapper explicitly converts the four fields to domain, DatasetValues and the existing physical projection.

Logical names are `calendar_date` and `previous_trade_date`; physical storage remains `cal_date` TIMESTAMP and `pretrade_date` STRING. Calendar timestamps are UTC-midnight carriers only, not event instants. Source dates must be exact valid YYYYMMDD; flags must be 0 or 1; a previous trade date, when provided, must precede the calendar day. Null/empty previous dates normalize to null. Intraday storage timestamps are rejected rather than silently truncated.

ExchangeCalendarDataset declares the existing YEAR partition, WAL and exchange+cal_date dedup contract. This declaration does not prove a source adapter or writer exists and is not yet registered as an executable dataset.

`local-D001-mapping-e429`: three tests passed, no failures/skips. Tests cover all-field typed/storage round trips, nullable prior date, exchange identity, invalid dates/flags/intraday values, and round trips of the 30 actual QuestDB sample rows captured in physical-baseline.json. These are mapping checks against captured read evidence; no new Tushare request or write occurred.

Remaining: source slicing/rate limit/coverage, incremental checkpoint with revision overlap, actual write port and read owner, management/group admission, live source-write-read and idempotent incremental acceptance.
