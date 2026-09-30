# D030 source contract review

Status: static, implemented-not-verified. This review records Python source and coordinator-provided read-only QuestDB metadata; it contains no live Tushare response or D030 write/readback.

## Provider request and transformation

- Primary Python entry is `sync_margin_secs` in `D:/work/fund_2/back-monitor/src/quant_platform/data/adapters/connectors/stock/margin/margin_secs_sync.py`. It obtains a completed-date ceiling, reads the SSE trading calendar, then calls `pro.margin_secs(trade_date=YYYYMMDD)` once for each open date. The request has no offset/page argument.
- Python connector decorator states 50 requests per 60 seconds. This is source-code evidence, not proof of the current account's grant. Java references the shared Tushare budget plus a daily trade-date slice policy; coordinator-owned endpoint registration/effective pacing remains pending.
- Provider fields are exactly `trade_date`, `ts_code`, `name`, `exchange`. Python normalizes trade date to the requested date, requires valid `ts_code`, rejects duplicate date/code keys, preserves the exchange label, and permits nullable name. The Java mapper accepts both BASIC and dashed source dates, maps them to the business calendar date at UTC midnight, and preserves nullable name and exchange text.
- Natural key and audited physical UPSERT key are both `(trade_date, ts_code)`. Membership revisions are re-fetched over the trailing five calendar days. The Python path does not define deletion/tombstone behavior: Java fails closed if source omits any existing key on a date; it does not delete stale membership rows.
- Python's `2026-01-01` fallback is not used. First Java INCREMENTAL requires an explicit bounded `--from`; subsequent incremental planning derives its start from same-target verified receipt coverage. `--to` defaults to the completed Shanghai source date and cannot exceed the frozen logical date or completed-date ceiling. BACKFILL/RECONCILE require explicit bounds inside verified coverage and never advance the incremental checkpoint.

## Physical schema evidence

Read-only metadata captured by the coordinator at `artifacts/java-migration/D030/schema-preflight.json` reports `margin_secs` as `trade_date` designated timestamp, YEAR partition, WAL enabled, DEDUP enabled, and columns `trade_date TIMESTAMP`, `ts_code SYMBOL`, `name STRING`, `exchange SYMBOL`. The only upsert-key columns are `trade_date` and `ts_code`.

The isolated DDL matches that observed physical layout. No Flyway migration is added for the existing production table. Only explicit `java_d030_margin_secs_*` targets are writable.

## Bounded execution and evidence

- A request spans at most 31 natural calendar days, includes a complete frozen SSE calendar cover, and creates at most one unpaged source call for each SSE-open date.
- The adjacent Python historical helper treats 6,000 rows as an unverified cap. Java uses this only as a conservative per-call ceiling and rejects `>= 6,000`; it is not represented as an official `margin_secs` API guarantee.
- Each complete response is saved as immutable raw JSON; the FETCHED ledger event binds its path, SHA-256, returned-row count and YYYYMMDD cursor. Incomplete responses are retained without replacing the original fetch/validation exception.
- Typed QWP writes are limited to 250 rows / 1 MiB per batch. ACK is not verification: each date is selected back from the isolated target and all four fields compared before completion. Existing keys must be included in the current source key set.
- Coverage only advances for a terminal verified incremental run with a contiguous calendar range and complete per-session receipts. Verified BACKFILL/RECONCILE receipts may overlay value checks but do not move checkpoint. A nonempty target with no receipt-backed incremental bootstrap fails closed.
- Cancellation is checked between daily calls. Generic runner recovery can reuse a verified date only when refetched source fingerprint matches. An unknown write remains IN_DOUBT; this D030 implementation does not auto-close such a run or issue speculative replay.

## Current implementation boundary

Owned files include D030 domain/DTO/mapper/read repository, source/calendar/adapter/owner/service, physical storage/write port, coverage, and independent raw readback helper. Shared CLI, DatasetConfiguration/read-group/write-group registration and completion-register updates are coordinator work. No test, build, Tushare operation or D030 QuestDB read/write was run for this implementation.
