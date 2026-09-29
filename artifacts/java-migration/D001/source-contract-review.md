# D001 bounded source contract check

At 2026-09-29 05:02 UTC, two read-only Tushare `trade_cal` requests for 2026-09-01 through 2026-09-07 returned code 0, four requested fields and seven rows each for SSE and SZSE. The source response includes closed days (September 5 and 6), with `is_open=0` and `pretrade_date=20260904`. Source order is descending by date, so a stable Java reader must sort by the complete `(exchange, cal_date)` key before advancing a slice.

A separate SSE request for 2026-01-01 through 2026-12-31 returned 365 distinct days with matching minimum and maximum dates. `source-year-capability.json` records its request, count and payload digest without retaining a second full payload. This supports one calendar-year network slice for the observed account and endpoint; the runtime still must reject a short or over-cap response.

The Java live source check in `source-84aebc9e-ed57-4d0a-88d9-524e7c52b9a3/source-validation.json` observes September 25 (a Friday) closed for both exchanges, followed by the weekend and a September 28 reopening. Its first test attempt assumed two closed days and failed; the corrected test passed in `var/local-D001-source-live-727b-build`. This is concrete evidence that weekday arithmetic cannot replace the source calendar.

An independent bounded PGWire query of the existing `exchange_calendar` table returned the same 14 complete four-field rows; every `cal_date` storage value was midnight. `source-week.json` records the source responses and `source-physical-compare.json` records query, row counts and differences. The credential is absent from both artifacts.

`wal-preflight.json` captures a separate read-only physical check: the table is YEAR partitioned with designated `cal_date`, WAL enabled, not suspended, zero pending and buffered rows, and matching writer/sequencer transaction counters at observation time. This confirms only the observed table state; the Java writer still needs to preflight its isolated target before each send.

`physical-ddl.txt` records the live `SHOW CREATE TABLE` result, including `DEDUP UPSERT KEYS(exchange,cal_date)`. That statement omits an explicit WAL keyword; the separate `tables()`/`wal_tables()` read above is the WAL evidence.

This establishes source access and field/date semantics only. It is not evidence of Java sync, isolated QuestDB write, idempotent replay, or revision handling. Those remain required for D001 acceptance.
