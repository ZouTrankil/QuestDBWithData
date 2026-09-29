# D020 implementation snapshot

## Coordinator acceptance update — 2026-09-30

Verified on the owned local QuestDB target after shared integration and scoped Java 24 compilation. See [final-review.md](final-review.md) and the task result for actual source, full-field readback, idempotence, incremental, cancellation, fresh-process CLI recovery and empty-window evidence. Human review remains pending; full build/test suite was not run. Earlier implementation-only statements below are preserved as historical worker scope.

`index_daily_basic` now has a standalone typed dataset/domain/key/mapper, exact five-code Python source universe, bounded `index_dailybasic` source adapter, deterministic complete/incomplete raw receipts, target-identity-bound batch writer, typed reader, canonical job owner, bounded planner/runner and receipt-backed checkpoint/reconciliation. Source requests are one code × at most 366 calendar days; the documented 3,000-row cap is fail-closed. Incremental runs require a bounded explicit bootstrap while no receipt-backed checkpoint exists and subsequently overlap the last five calendar days.

The isolated-table DDL helper preserves the audit snapshot (`trade_date` designated timestamp, YEAR partition, WAL, DEDUP `(ts_code,trade_date)`). It cannot target the formal table. There is no formal Flyway migration.

Implementation status is `implemented_not_verified`. Shared CLI/config/read-group/write-group integration has not been performed by this task. No build, tests, Tushare request, or QuestDB operation has been run. Do not use this snapshot as evidence of live source support or data validation.
