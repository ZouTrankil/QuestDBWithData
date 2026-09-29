# D010 `stk_limit` source and storage contract

Review date: 2026-09-30. Status: implementation evidence only; no live Tushare or QuestDB acceptance was run by this task.

## Source contract

- Python source: `D:/work/fund_2/back-monitor/src/quant_platform/data/adapters/connectors/stock/price/stk_limit_sync.py`.
  `sync_stk_limit` resolves its end date, reads QuestDB trading sessions, then calls `get_stk_limit_with_retry(pro, trade_date=...)` once per date and writes each nonempty response. If the calendar is absent it falls back to a date-range request. The Java path requires complete D001 SSE calendar coverage and does not use that range fallback.
- The Python helper has `@api_rate_limit(api_limit=60, period=60)` and three attempts for selected errors. `config/yaml/data/data_quality_rules.yaml` declares `stk_limit` rate limit 50/minute and a 300 second timeout. These are source-code observations, not the current account quota. Java refers to `tushare.shared` and does not raise the shared budget.
- The Python helper currently retries an empty response and can return an empty frame after network errors. Java keeps a successful empty API response distinct from transport/validation failure; transport errors fail the slice and cannot produce a verified empty receipt.
- The Python source advances from `last_update + 1 day`, which can miss old revisions. Java uses only same-target contiguous verified incremental history, keeps a five-calendar-day revision overlap, and re-fetches/re-reads overlap dates.
- Python `StkLimit` declares `trade_date`, `ts_code`, nullable `up_limit` and `down_limit`, plus a model-only `created_at`. The D010 audited physical schema has only `ts_code SYMBOL`, `trade_date TIMESTAMP`, `up_limit DOUBLE`, and `down_limit DOUBLE`; `created_at` is not persisted and is intentionally excluded.

## Official API evidence

Tushare documents `stk_limit` as all-market (including A/B shares and funds), with fields `trade_date`, `ts_code`, optional `pre_close`, `up_limit`, `down_limit`, `asset_type`, and `exchange`. A date request is shown in the API example. Each request is limited to 5800 rows; the docs say the total can be obtained by repeated calls and document `ts_code` plus start/end date as query parameters, but do not document offset/cursor paging. Java requests exactly `ts_code,trade_date,up_limit,down_limit` for one date. At 5800 rows it preserves an unverified raw response and fails closed. It does not split through an A-share-only inventory because that cannot prove coverage of the documented all-market population (including funds).

Official source: [Tushare `stk_limit` API documentation](https://tushare.pro/document/2?doc_id=183).

## Frozen Java mapping

| API / physical field | Java field | Type | Null/unit/meaning |
| --- | --- | --- | --- |
| `ts_code` | `StockLimitKey.tsCode` | `String` / `SYMBOL` | Required six-digit exchange-qualified code; full identity component |
| `trade_date` | `StockLimitKey.tradeDate` | `LocalDate` / `TIMESTAMP` | Required BASIC `YYYYMMDD` business date; UTC-midnight storage carrier, not an instant |
| `up_limit` | `StockLimit.upLimit` | `Double` / `DOUBLE` | Nullable, finite if present; source currency per share, no unit/scale conversion |
| `down_limit` | `StockLimit.downLimit` | `Double` / `DOUBLE` | Nullable, finite if present; source currency per share, no unit/scale conversion |

Business key and physical UPSERT key both remain `(ts_code, trade_date)`. Nullable price fields are not keys. The D010 audited physical target is YEAR/WAL/DEDUP; Python's model declaration says DAY. Java freezes the audited YEAR layout, checks schema before use, and contains no Flyway migration for the external production table. `StockLimitDataset.createIsolatedTableSql` only returns D010-prefixed acceptance DDL; no DDL was executed here.

## Boundaries and remaining source acceptance

- Maximum plan: 366 calendar days / 366 date slices. Each source call is one exact trading date, no offset, with the official 5800-row cap treated as possible truncation. A full response at the cap is rejected, raw rows retained as `unverified_raw_response`, and no rows are written.
- Calendar scope was checked against the local D001 QuestDB target read-only. `SELECT exchange, count(), min(cal_date), max(cal_date) FROM exchange_calendar GROUP BY exchange` returned exactly SSE (13,432 rows; 1990-12-19 through 2027-09-27) and SZSE (13,236 rows; 1991-07-03 through 2027-09-27); there is no BSE calendar. A bounded 2026-09-28..2026-09-30 read returned both SSE and SZSE as open on all three dates, with zero BSE rows. This snapshot does not establish historical or future parity with BSE.
- Python `get_trading_dates_between(start, end)` is explicitly documented as returning SSE open dates only. It requires a complete, unique SSE row for every calendar day in the inclusive range and returns only rows with `is_open == 1`; `sync_stk_limit` makes one unfiltered all-market `stk_limit(trade_date=...)` request on each such date. D010 keeps that legacy schedule and, like Python, does not discard `.BJ`/BSE securities from the response. SSE/SZSE holidays are not unioned, and no BSE-only dates can be scheduled from the current D001 data. Thus the implemented parity is with the actual Python contract; independent BSE session parity remains unproven and must stay visible before `verified` status. Extending D001 with a BSE calendar and deliberately changing D010's schedule would be a separate contract change.
- D010's source end now follows Python `resolve_completed_sync_end_date`: in Asia/Shanghai, before 20:30 the ceiling is yesterday; from 20:30 onward it is today. An omitted programmatic `requestedThrough` defaults from the frozen logical date and is clamped to this ceiling; an explicit date is also clamped, and the final end never exceeds `logicalDate`. Backfill/reconcile still require explicit finite `--from` and `--to`. Incremental catch-up refuses a physical target maximum beyond the same completed-source ceiling rather than reintroducing a partial current day.
- First incremental use requires an explicit `--from` bootstrap and `--to` bounded by a frozen logical date. Later plans need a verified incremental checkpoint for the same isolated target, job/schema definition and anchor. BACKFILL/RECONCILE receipts do not advance that checkpoint. Physical dates and all values in the checkpoint chain are checked against the latest date-specific verified source receipts, ordered by run `updatedAt` (run IDs are only a deterministic tie-breaker).
- No actual API request, QuestDB DDL, write, readback, test, or build was run by this implementation task.
