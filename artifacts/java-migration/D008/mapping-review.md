# D008 implementation mapping review

## Source and storage evidence

- Python connector: `D:/work/fund_2/back-monitor/src/quant_platform/data/adapters/connectors/stock/basic/daily_basic_sync.py` calls `pro.daily_basic` with a single `trade_date` window and writes each trading day independently. Its `get_daily_basic_window` currently catches selected network/value exceptions and returns an empty DataFrame; the Java adapter lets request failures propagate and records explicit empty responses separately.
- Python model: `D:/work/fund_2/back-monitor/src/quant_platform/data/adapters/questdb/models/stock/market_data.py`, `DailyBasic` and `get_questdb_schema()` declare the same 18 columns, `trade_date` timestamp, YEAR partition, WAL, and `(ts_code, trade_date)` dedup key.
- Storage audit snapshot: `D:/work/fund_2/back-monitor/artifacts/storage-audit-20260929/objects.csv`, `daily_basic` row: `ts_code,trade_date` business/UPSERT keys, `trade_date` timestamp, YEAR/WAL/dedup, physical types listed below.
- Official API reference: [Tushare daily_basic](https://tushare.pro/document/2?doc_id=32) describes a single-request maximum of 6000 rows and date-based extraction. The implementation treats exactly 6000 rows as potentially truncated and does not claim offset pagination.
- Python limiter annotation is 20 calls/60 seconds; YAML `rate_limit` snapshot says 25. These are recorded as conflicting source facts, not as an account entitlement. Java calls go through the shared Tushare request budget; the current `daily_basic` ceiling is `min(20, endpointPerMinute, configured endpoint override)`. The actual account entitlement remains to be confirmed during live acceptance.

## Frozen column mapping

All source fields map to same-named physical columns. Decimal values are converted to finite Java `Double` values for the audited QuestDB `DOUBLE` columns. No unit conversion is applied. Null or blank optional numeric values remain null; malformed or non-finite values fail the row.

| Source / logical / physical | Java type | QuestDB type | Meaning / nullability |
| --- | --- | --- | --- |
| `ts_code` | `String` | `SYMBOL` | Required Tushare stock identity |
| `trade_date` | `LocalDate` | `TIMESTAMP` | Required exchange business date, stored as UTC midnight carrier |
| `close` | `Double` | `DOUBLE` | Daily close price; nullable |
| `turnover_rate` | `Double` | `DOUBLE` | Turnover percent; nullable |
| `turnover_rate_f` | `Double` | `DOUBLE` | Free-float turnover percent; nullable |
| `volume_ratio` | `Double` | `DOUBLE` | Volume ratio; nullable |
| `pe` | `Double` | `DOUBLE` | Price/earnings; nullable for unavailable or loss-making cases |
| `pe_ttm` | `Double` | `DOUBLE` | Trailing price/earnings; nullable |
| `pb` | `Double` | `DOUBLE` | Price/book; nullable |
| `ps` | `Double` | `DOUBLE` | Price/sales; nullable |
| `ps_ttm` | `Double` | `DOUBLE` | Trailing price/sales; nullable |
| `dv_ratio` | `Double` | `DOUBLE` | Dividend yield percent; nullable |
| `dv_ttm` | `Double` | `DOUBLE` | Trailing dividend yield percent; nullable |
| `total_share` | `Double` | `DOUBLE` | Total shares in 10,000 shares; nullable |
| `float_share` | `Double` | `DOUBLE` | Tradable shares in 10,000 shares; nullable |
| `free_share` | `Double` | `DOUBLE` | Free-float shares in 10,000 shares; nullable |
| `total_mv` | `Double` | `DOUBLE` | Total market value in 10,000 CNY; nullable |
| `circ_mv` | `Double` | `DOUBLE` | Circulating market value in 10,000 CNY; nullable |

The business key and physical UPSERT key are both `(ts_code, trade_date)`. Key collisions within or across source pages fail closed. Source dates must exactly match the requested `YYYYMMDD` trade date. The designated timestamp is `trade_date`; partition is YEAR; WAL and dedup remain enabled. No source snapshot version or upstream revision cursor is declared.

## DDL and compatibility

The task targets an externally owned production table, so this task adds no formal Flyway migration and does not create or alter the formal table. The equivalent DDL for a test-owned isolated table is in `daily_basic-isolated.sql`; runtime preflight requires exact column types, designated timestamp, YEAR partition, WAL, and UPSERT keys. Reads use bounded explicit-column queries; writes use QWP in bounded batches and require complete-key/full-column PGWire readback plus settled WAL.

The source adapter resolves dates from a complete SSE/SZSE `exchange_calendar` range, not from `daily` rows, so suspended securities are not excluded by an `INNER JOIN` or daily-price presence check. Incremental planning starts at the end of the latest contiguous chain of verified incremental runs, grouped by bootstrap anchor and exact job route/schema/target, minus a 30-calendar-day revision overlap. Backfill and reconcile runs do not establish an incremental checkpoint. First bootstrap requires an explicit start date. The overlap is a bounded operational policy, not a claim that Tushare never revises older history.

The CLI/service default an omitted `--to` to the frozen `logicalDate` and reject a later end date. A first incremental bootstrap requires an empty target; existing rows need an explicit verified reconcile before they can establish incremental history. For an existing checkpoint, the planner checks the target's actual `trade_date` min/max against receipt-backed source rows from the same-target verified incremental chain. It reopens every frozen trade-date source receipt under the ledger evidence root, checks the recorded fingerprint and complete business keys, and derives the first/last dates that actually returned rows. The physical range must match those endpoints exactly, so a run may end with valid empty trading dates while missing or ledgerless boundary rows fail closed. The target also fails closed if it becomes empty after a nonempty verified run. These physical checks do not promote untracked table dates to a checkpoint; each checkpoint still comes only from a fully verified contiguous incremental interval.

## Verification boundary

The root coordinator reports the serialized project suite passed before this static-review repair; the new checkpoint, receipt-boundary, CLI default, and bounds regression tests added here have not been run. Live D008 acceptance remains blocked at QuestDB read-only authentication preflight; no D008 Tushare request or isolated DDL/write was started. Human review remains pending.

Checkpoint scope: only complete verified incremental intervals for the same physical target, canonical source route, job/dataset schema, and frozen bootstrap anchor contribute. Each included run must have one in-root, fingerprint-matching source receipt per frozen trade date; empty trading dates are recorded as complete receipts and do not imply rows. The planner compares physical bounds with receipt-backed first/last row dates and refuses ledgerless ranges rather than inferring them from `MAX(trade_date)`. A populated target must be explicitly reconciled before first incremental bootstrap; live acceptance should verify checkpoint movement and these fail-closed boundaries on an isolated QuestDB target.
