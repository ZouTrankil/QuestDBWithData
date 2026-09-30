# D028 source contract review — `margin_all`

Status: local Python connector/model and repository schema were inspected. No Tushare request or QuestDB operation was made for this implementation handoff.

## Python source path and request scope

- `src/quant_platform/data/adapters/connectors/stock/margin/margin_sync.py` defines `fetch_margin_all_tushare(pro, start_date, end_date)` and calls `pro.margin(start_date=start_date, end_date=end_date)`. `sync_margin_all` starts from configured/fallback `20260101`, moves to `MAX(trade_date)+1` unless forced, resolves a completed end date, and writes a nonempty frame. Python catches selected request errors as empty frames; D028 deliberately keeps failed requests as failures and never promotes them to verified-empty.
- `margin` also supports an exact `trade_date` request. D028 issues one exact calendar-date request per frozen day, avoiding a wide source response and making empty/nonempty coverage explicit. It sends no undocumented offset. Responses at 4,000 rows fail closed; the request has a 4 MiB raw receipt bound.
- Python table configuration records 150 requests/minute, daily cadence, and a 19:00 schedule. The actual Java endpoint budget must remain at or below 150/minute until account entitlement is independently checked.
- Requested fields are exactly `trade_date, exchange_id, rzye, rzmre, rzche, rqye, rqmcl, rzrqye, rqyl`. The physical model declares `trade_date` TIMESTAMP, `exchange_id` SYMBOL, and seven DOUBLE metrics. `MarginAll` uses the provider numeric values without rescaling. Python's seven non-optional `float` model fields reject explicit nulls during model validation; Java likewise rejects missing, null, malformed, or nonfinite metrics instead of silently manufacturing zeroes.
- The Tushare `margin` endpoint documents `trade_date`, `start_date`, `end_date`, and `exchange_id`; returned exchange identifiers are constrained here to `SSE`, `SZSE`, or `BSE`. The complete natural key is `(trade_date, exchange_id)`—one aggregate per provider exchange and date.

## Physical contract

- The task audit snapshot and root's read-only `tables()` metadata check agree: `margin_all` is YEAR-partitioned, WAL enabled, and DEDUP disabled. There is no physical upsert key. `trade_date` is a BASIC ISO business date stored as the QuestDB UTC-midnight microsecond timestamp carrier; exchange and all seven metric columns are explicitly mapped.
- `MarginAllDataset.definition` is read-only for the formal object. The writable capability is available only for a `java_d028_margin_all_<explicit suffix>` target and is `WAL_REPLACE`, preserving YEAR/WAL/non-DEDUP. No Flyway change or formal-table migration is proposed.
- Because the Python path appends and has no delete/tombstone contract, the Java window publisher refuses to replace a date if the new provider response omits an existing `(trade_date,exchange_id)` key. It does not infer deletion from an empty response.

## Bounds and evidence

- Default bootstrap uses the Python fallback anchor 2026-01-01 and at most 366 calendar-day slices per frozen request. Subsequent incremental windows reread five calendar days from the receipt-backed checkpoint (clamped to the original bootstrap anchor) and are also capped at 366 days. Only contiguous VERIFIED/VERIFIED_EMPTY INCREMENTAL runs advance checkpoint; BACKFILL/RECONCILE receipts can overlay per-date validation only within already verified checkpoint coverage.
- The completed-day ceiling is resolved in Asia/Shanghai at 20:30. Explicit and default `--to` values are clamped to the latest completed day, including when a future logical date is passed.
- Every day receives one raw receipt, including normal zero-row responses. The receipt freezes source endpoint, exact date parameter, nine fields, source cap, raw row order, SHA-256, and source version when returned. Duplicate exchange keys, changed date, unexpected fields, cap hits, missing/invalid values, and request failures are rejected.
- Non-DEDUP writes never target the requested table directly. A bounded full-table snapshot is staged under the same YEAR/WAL layout, preserved outside the frozen window, compared against every reopened daily source receipt, and published under a dataset-scoped OS lock plus durable publication journal and target/backup rename. Stage-only recovery may discard only after frozen-request and original-target identity checks; it never publishes an incomplete stage.

## Outstanding acceptance

The task's serial D027 acceptance gate remains coordinator-owned. D028 is `implemented_not_verified`: no current account permission, live source response, isolated-table create/write, publication, or independent QuestDB readback is claimed here.
