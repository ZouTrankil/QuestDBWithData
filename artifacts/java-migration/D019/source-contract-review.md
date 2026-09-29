# D019 source contract review

## Coordinator acceptance update — 2026-09-30

Verified on the owned local QuestDB target after shared integration and scoped Java 24 compilation. See [final-review.md](final-review.md) and the task result for actual source, full-field readback, idempotence, incremental, cancellation, fresh-process CLI recovery and empty-window evidence. Human review remains pending; full build/test suite was not run. Earlier implementation-only statements below are preserved as historical worker scope.

## Python call path and frozen universe

Read-only review of `D:/work/fund_2/back-monitor/src/quant_platform/data/adapters/connectors/index/index_daily_market_sync.py` and `data/contracts/index_universe.py`:

- `sync_index_daily_market` calls `_sync_definitions(get_index_definitions())`; the current default universe has 57 canonical codes and routes through `index_daily`.
- `sync_sw2021_l1_index_daily_market` is a separate explicit source operation over 31 SW2021 level-one codes. These use `.SI` canonical codes and `sw_daily`.
- Python resolves each code's date range independently from `index_daily_market.trade_date` filtered by that `ts_code`. It uses `20150101` as the configured fallback start and requests completed dates through `resolve_completed_sync_end_date`.
- Both Python fetchers are decorated with `@api_rate_limit(api_limit=180, period=60)`. Java sends every request through the shared Tushare budget; this source does not raise or duplicate the shared budget.
- Python sets each returned frame's `ts_code` to the definition's canonical code, concatenates the source frames, sorts by `update_time`, and keeps the last row by `(ts_code,trade_date)`. The Java task instead freezes the canonical code per run and rejects any response code drift.
- Python's `preprocess_dataframe` supplies `update_time` from a UTC observation instant; it is not an upstream Tushare field. Java freezes it once in the run request at microsecond precision so retries/resume preserve the exact technical value.

## Endpoint contracts

The request contract is one canonical `ts_code` and one inclusive `start_date..end_date` window, capped at 366 calendar days. No unsupported offset or cursor parameter is sent.

| Canonical route | Endpoint fields requested | Normalization | Unit handling |
| --- | --- | --- | --- |
| Core 57 | `ts_code,trade_date,close,open,high,low,pre_close,change,pct_chg,vol,amount` | None | Preserve endpoint values; no scaling |
| SW2021 L1 31 | `ts_code,trade_date,close,open,high,low,change,pct_change,vol,amount` | `pct_change` → `pct_chg`; `pre_close` is explicitly null because doc327 does not return it | Preserve SW endpoint values; no scaling or synthetic previous-close derivation |

Tushare official documentation: [`index_daily` doc95](https://tushare.pro/document/1?doc_id=95) identifies the index OHLC fields, says it does not cover Shenwan indexes, and reports `vol` in hands and `amount` in thousand yuan. [`sw_daily` doc327](https://tushare.pro/document/2?doc_id=327) documents the SW2021 endpoint and a 4000-row maximum; its volume/turnover units are endpoint-specific (ten-thousand shares / ten-thousand yuan) and are not converted. The source contract rejects an exact 4000-row response. `index_daily` doc95 publishes no row cap; one-code windows are bounded to 366 calendar days (at most 366 distinct daily dates) and use the same 4000-row client guard, with exact-cap responses rejected.

## Field mapping and time semantics

The core endpoint has 11 source fields; `sw_daily` has 10 and does not return `pre_close`. Its D019 row maps the absent SW `pre_close` to explicit SQL null; it is never inferred from adjacent daily rows. All numerical values are nullable finite `DOUBLE`s; null remains null, non-finite values and invalid cell types are rejected. `pct_change` → `pct_chg` is the only source alias. No normalization rescales point changes, percentages, volume, or amount. `trade_date` is exact `YYYYMMDD` business-date text, represented as `LocalDate` and stored as a UTC-midnight `TIMESTAMP` carrier at microsecond storage precision. `update_time` is derived from the frozen run's `observedAt` and stored as a required UTC observation timestamp at microsecond precision.

The full business key is `(ts_code,trade_date)` and the audited physical dedup key is `(ts_code,timestamp)`. Duplicate source keys fail before writing. Rows use `index_daily_market`'s existing YEAR/WAL/DEDUP layout; this task adds no Flyway migration or formal-table DDL because the production object is externally owned.

## Physical snapshot and acceptance boundary

Read-only baseline snapshot `artifacts/java-migration/F001/baseline.json` identifies `index_daily_market` as YEAR partitioned, WAL, DEDUP, timestamp=`trade_date`, physical key `(ts_code,timestamp)`. The recorded snapshot has 234292 rows with dates 2010-01-04 through 2026-09-21; these are audit facts and must be rechecked during isolated acceptance. Acceptance must use a dedicated `java_d019_index_daily_market_<suffix>` table and exact `targetId`; formal table mutation is forbidden.

D019 is `serial_after: D018`. The implementation is prepared, but result status stays `implemented_not_verified` until the coordinator records D018 acceptance and performs bounded real source plus isolated QuestDB write/readback acceptance. This review does not claim live verification.
