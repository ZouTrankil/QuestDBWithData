# D020 source and storage contract review

## Coordinator acceptance update — 2026-09-30

Verified on the owned local QuestDB target after shared integration and scoped Java 24 compilation. See [final-review.md](final-review.md) and the task result for actual source, full-field readback, idempotence, incremental, cancellation, fresh-process CLI recovery and empty-window evidence. Human review remains pending; full build/test suite was not run. Earlier implementation-only statements below are preserved as historical worker scope.

Status: implementation only; source, target, and independent QuestDB acceptance remain unverified.

## Python call path

The Python connector is `D:/work/fund_2/back-monitor/src/quant_platform/data/adapters/connectors/index/index_market_sync.py`. `fetch_index_dailybasic(pro, ts_code, start_date, end_date)` calls `pro.index_dailybasic(ts_code=..., start_date=..., end_date=...)`. Its sync function iterates the exact `CORE_INDICES` sequence `000300.SH`, `000016.SH`, `399006.SZ`, `000905.SH`, `000852.SH`; first sync defaults to `20180101`, later sync starts at the stored last date inclusively. The connector decorator is 50 calls per 60 seconds, which is source-code evidence rather than current account authorization. Java uses the shared credential and endpoint budget and does not raise that budget.

Java preserves the exact five-code universe and the endpoint's single request route: one code and one bounded `[start_date,end_date]` interval per source slice. Its bootstrap requires an explicit start and covers at most 366 calendar days. After bootstrap, only receipt-backed contiguous incremental runs advance the code-specific checkpoint; the next range re-fetches up to five calendar days before that checkpoint, clamped to the frozen bootstrap anchor. BACKFILL and RECONCILE require explicit bounded dates and do not advance that checkpoint.

## Official source contract

Official [Tushare `index_dailybasic` documentation, document 128](https://tushare.pro/document/2?doc_id=128) declares the fields `ts_code`, `trade_date`, `total_mv`, `float_mv`, `total_share`, `float_share`, `free_share`, `turnover_rate`, `turnover_rate_f`, `pe`, `pe_ttm`, and `pb`. It supports `ts_code`, `trade_date`, `start_date`, and `end_date` query parameters and documents at most 3,000 rows in one request. No `offset` or cursor protocol is documented. Java requests exactly the 12 persisted fields with the `ts_code/start_date/end_date` route. A one-code interval is capped at 366 calendar days; any response reaching 3,000 rows is rejected as potentially truncated and retained as unverified raw response evidence.

The official documentation describes market values in yuan, share amounts in shares, turnover rates in provider percentage units, and PE/PB as ratios. Java preserves all numeric values without scaling. Null provider metrics remain null; nonfinite or nonnumeric metrics fail closed. The provider fields/items protocol exposes no source snapshot identifier; the receipt records `sourceVersion: null` rather than inventing one.

The documentation's current index examples/listing does not establish support for every configured Python code, in particular `000852.SH`. Java retains that exact Python universe but does not fabricate empty rows or silently omit a code; the coordinator must verify provider support for each code during bounded live acceptance. A provider business error is a failed source run.

## Storage mapping

The audited physical key and Java business key are `(ts_code, trade_date)`. `trade_date` is the designated TIMESTAMP, but its semantic type is a calendar business date carried at UTC midnight, not an instant. Physical storage remains YEAR partitioned, WAL enabled, with DEDUP UPSERT on `(ts_code, trade_date)`. The D020 isolated DDL helper is restricted to `java_d020_index_daily_basic_<suffix>`; no formal-table/Flyway migration is included because the formal table is externally owned.

Each source receipt is a stable, sorted raw response with SHA-256 and a complete frozen request scope. `IndexDailyBasicCoverage` advances checkpoints only from complete VERIFIED/VERIFIED_EMPTY receipts for the same target, job definition, code, and contiguous interval. Before planning it checks every physical row for source-receipt coverage and full field equality. The receipt and actual-target checks are implementation code only until the coordinator runs them against an isolated QuestDB target.

## Coordinator integration and acceptance

The D020 classes are intentionally not wired through shared CLI/config/group files in this implementation pass. The coordinator still needs to register `IndexDailyBasicDataset.DEFINITION` and `IndexDailyBasicReadRepository`, add the D020 owner to the shared job registry if owner scanning is not automatic, expose a plan/run/status/resume command, and include the data object in read/write group dispatch. Use an explicitly provisioned `java_d020_index_daily_basic_<suffix>` target and freeze its `static-v2` target identity in every run.

No Java build, tests, provider request, or QuestDB operation was run for this implementation pass. `results/D020.json` records the remaining compile, source-support, isolated write/readback, idempotency, incremental, cap, recovery, and CLI acceptance.
