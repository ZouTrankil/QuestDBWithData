# D007 implementation and source contract review

## Read-only source and audited storage evidence

- Python connector `D:/work/fund_2/back-monitor/src/quant_platform/data/adapters/connectors/stock/price/daily_sync.py` declares `DAILY_FIELDS` at lines 39–55, applies `@api_rate_limit(api_limit=450, period=60)` at line 58, requests `pro.daily(trade_date=..., fields=...)` at line 63, and loops over calendar trading dates at lines 155–178. The module documents that Tushare omits suspended securities rather than emitting filler rows (lines 4–8).
- The Python retry helper returns an empty frame on selected connection/timeout/value failures (lines 68–80). D007 Java does not map such failures to a valid empty date: source exceptions fail the slice; a successful zero-row response remains an empty source page.
- Python `StockDaily.get_questdb_schema()` in `src/quant_platform/data/adapters/questdb/models/stock/market_data.py` declares `trade_date` timestamp/designated timestamp, YEAR partition, WAL, and `(ts_code, trade_date)` dedup keys (around lines 22–88). The model describes `vol`/`ah_vol` in 手 and `amount`/`ah_amount` in 千元.
- The 2026-09-29 storage audit `artifacts/storage-audit-20260929/objects.csv` reports 9,798,989 `daily` rows, metadata range `2018-01-02` through `2026-09-28`, physical columns matching the 13-column contract below, `trade_date` designated timestamp, YEAR/WAL/dedup enabled, and actual UPSERT keys `(ts_code, trade_date)`. These are audit snapshot metadata, not a live D007 preflight or row comparison; acceptance must recheck the isolated target's actual schema and data.
- [Official Tushare daily API documentation](https://tushare.pro/document/1?doc_id=27) documents a maximum of 6000 rows per request and supports date/code filters. The Java request bound is 6001 so a response at the documented cap is detectable. At/above the cap, D007 retains the initial raw response and re-fetches bounded groups of up to 1000 codes from D002 `stock_detail_info`; it does not invent offset paging or accept a possibly truncated response.
- The Python decorator's 450/minute annotation and task-card configuration snapshot are source facts, not account entitlement. The D007 job uses the existing `tushare.shared` budget and does not raise that shared limit. Account authorization and effective budget remain live-acceptance checks.

## Frozen source → domain → storage mapping

All 13 Tushare response columns retain their names in the QuestDB projection. Prices/metrics map to nullable finite Java `Double` values and QuestDB `DOUBLE`; no scale conversion is applied. JSON null remains null; malformed/non-finite values fail closed. The conversion is IEEE-754 `double`, so exact decimal arithmetic is not claimed.

| Source / logical / physical column | Java type | QuestDB type | Meaning and nullability |
| --- | --- | --- | --- |
| `ts_code` | `String` | `SYMBOL` | Required mainland equity code including exchange suffix |
| `trade_date` | `LocalDate` | `TIMESTAMP` | Required exchange business date (`YYYYMMDD`); stored as UTC-midnight calendar carrier, not an event instant |
| `open` | `Double` | `DOUBLE` | Provider unadjusted open; nullable |
| `high` | `Double` | `DOUBLE` | Provider unadjusted high; nullable |
| `low` | `Double` | `DOUBLE` | Provider unadjusted low; nullable |
| `close` | `Double` | `DOUBLE` | Provider unadjusted close; nullable |
| `pre_close` | `Double` | `DOUBLE` | Provider previous close value, passed through unchanged; nullable |
| `change` | `Double` | `DOUBLE` | Provider daily price change, passed through unchanged; nullable |
| `pct_chg` | `Double` | `DOUBLE` | Provider percent change in percentage points, passed through unchanged; nullable |
| `vol` | `Double` | `DOUBLE` | Volume in 手; nullable |
| `amount` | `Double` | `DOUBLE` | Turnover in 千元; nullable |
| `ah_vol` | `Double` | `DOUBLE` | After-hours volume in 手; nullable |
| `ah_amount` | `Double` | `DOUBLE` | After-hours turnover in 千元; nullable |

The business key and audited physical UPSERT key are both `(ts_code, trade_date)`. The designated timestamp is `trade_date`, partition is YEAR, and WAL/dedup stay enabled. Same-key provider corrections replace the prior values; the source has no revision ID/cursor, so D007 uses a finite five-calendar-day overlap and does not claim to detect revisions older than that policy. Tushare does not return suspended-stock filler rows; an exchange open day may therefore legitimately return fewer rows or zero rows.

`DailyDataset.DEFINITION` freezes version 1 and declares dataset dependencies `exchange_calendar` and `stock_detail_info`. The job dependency is `data.exchange_calendar@1` because every request window requires complete SSE calendar coverage. D002 stock inventory is read only for the exact-cap fallback; D006 is the verified prerequisite and no separate D002 refresh is silently started by a daily run.

## Schema compatibility and DDL plan

`daily` is an externally owned audited production table. This task adds no Flyway migration and does not issue production DDL. D007 plan/run now rejects any target not named `java_d007_daily_<suffix>`; the run-owned isolated object must have the 13 columns and types above, `TIMESTAMP(trade_date) PARTITION BY YEAR WAL DEDUP UPSERT KEYS(ts_code, trade_date)`. The suffix is validated as an identifier and runtime preflight resolves the exact physical schema/identity before any write. No isolated table has been created yet.

The existing generated `DailyRow` projection is reused without edits. `DailyMapper` explicitly maps all 13 fields among the Tushare DTO, `DailyMarketBar`, projection and `DatasetValues`. Typed reads select explicit fields and use bounded keyset/range queries. Typed writes use QWP batches capped at 250 rows/1 MiB, wait for ACK, then query all 13 stored fields for each complete key with bounded WAL visibility polling. DDL drift, key mismatch or unknown write delivery must prevent a verified result.

## Implemented source/job behavior and remaining acceptance

- `daily.trade_date` is the exact slice-policy reference: one finite source request per exact, fully covered SSE-open calendar date. The planned 1–366-calendar-day range requires an SSE calendar row for every date; weekdays are never synthesized as trading sessions. The source applies the Python-compatible 20:30 Asia/Shanghai completion ceiling. Per-date source and checkpoint scans are bounded at the D002 inventory maximum of 10,000 codes; the job row budget is 3,660,000 rows for 366 dates.
- The D007 owner is `DailyJobService` (`@Service` / `SyncJobOwner`), job ID `data.daily`, owner key `daily_owner`, default mode INCREMENTAL, with BACKFILL and RECONCILE also admitted. First use requires an explicit bootstrap `--from`; `--to` is bounded by the source completion ceiling. Checkpoint advancement uses only VERIFIED/VERIFIED_EMPTY INCREMENTAL runs whose first frozen interval starts exactly at the requested bootstrap anchor, then extends over continuous/overlapping incremental intervals. BACKFILL/RECONCILE do not move that high-water, but their verified receipts may supply the newest per-date source evidence. Incremental planning reads the target's bounded distinct `trade_date` interval (maximum 10,000 distinct dates) and requires every existing date to fit same-target incremental coverage, be an SSE-open date, and have a reopenable terminal-run source receipt. Missing coverage/receipt, pre-bootstrap rows, dates beyond the incremental high-water, non-session dates, or an over-bound target fail safely. It then validates the latest five-calendar-day overlap against actual complete-day QuestDB values before planning the next range. For each date the latest receipt is selected by verified-run `updatedAt`, with immutable run ID as a deterministic tie-breaker; history pagination by run ID is not treated as chronological.
- `DailySource` keeps raw source response receipts with SHA-256, exact date/field scope, completion information, and any cap-fallback inventory/group evidence. Duplicate business keys, wrong date/code, source errors, cap ambiguity and incomplete calendar coverage fail closed. The shared request layer remains responsible for budget/retry/cancellable HTTP behavior.
- Regression tests exist for isolated-target naming and latest verified-receipt selection, in addition to `DailyMappingTest`, `DailyTradingSessionsTest`, `DailySourceTest`, and `DailyJobDefinitionTest`. The coordinator's prior full project test report predates the D007 safety/checkpoint corrections below; those corrections and the new regression tests have not been run. Shared Gradle output is being checked serially by the coordinator.
- No Tushare request, QuestDB connection/read/write, isolated table creation, or live acceptance was started in this task. Consequently this evidence does not claim production readiness or verified data correctness. Keep status `implemented_not_verified` / `blocked` until valid credentials allow nonempty real-source isolated write + full-key/all-column readback, same-window idempotent rerun, next incremental behavior, and applicable failure/recovery checks.

## Shared integration status

1. `DatasetConfiguration` admits exact slice policy ID `daily.trade_date`, one request slice per exact SSE-open date in the frozen bounded window.
2. `CommandLineRunner` routes `plan-daily-job` and `run-daily-job` to `DailyJobService`. Configure `app.sync.daily-table=java_d007_daily_<suffix>` before invoking either command. Both require `--from YYYY-MM-DD` and `--logical-date YYYY-MM-DD`; both accept optional `--to YYYY-MM-DD` and `--mode INCREMENTAL|BACKFILL|RECONCILE`; `run-daily-job` alone accepts `--resume-from RUN_ID`. Planning emits the frozen request/target ID without a source request; execution/resume uses the frozen bounds. Generic `plan-sync-job --job data.daily --version 1 --logical-date DATE --from DATE --to DATE [--mode MODE]` validates the catalog entry but does not resolve checkpoint-specific bounds.
3. Generic run show/cancel and write-group integration remain in coordinator-owned shared CLI/registries. The D007 write-group adapter now binds its `DailyWritePort` to `dailyTarget.tableName()` so the physical port and target identity use the same configured isolated table. No Flyway migration is added for externally owned `daily`.
