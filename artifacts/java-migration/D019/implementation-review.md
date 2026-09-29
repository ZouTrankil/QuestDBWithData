# D019 implementation review

## Coordinator acceptance update — 2026-09-30

Verified on the owned local QuestDB target after shared integration and scoped Java 24 compilation. See [final-review.md](final-review.md) and the task result for actual source, full-field readback, idempotence, incremental, cancellation, fresh-process CLI recovery and empty-window evidence. Human review remains pending; full build/test suite was not run. Earlier implementation-only statements below are preserved as historical worker scope.

- Canonical work unit: one frozen `ts_code` and one inclusive range of at most 366 calendar days. An outer batch scheduler can enumerate `IndexDailyMarketUniverse.CORE57` (57 endpoint `index_daily` codes) and/or `SW2021_L1_31` (31 endpoint `sw_daily` codes). One code's checkpoint never advances another code's checkpoint.
- Job definition: `data.index_daily_market` v1. Modes are bounded `INCREMENTAL`, `BACKFILL`, and `RECONCILE`; only verified incremental runs with exact target, code, route, definition and frozen bootstrap anchor advance that code's checkpoint. Fresh BACKFILL/RECONCILE source receipts can overlay same-day verification by ledger `updatedAt`; immutable random run IDs are only deterministic tie-breakers.
- Bootstrap requires explicit `--from` and an empty physical target for that code when no verified incremental coverage exists. Later incremental plans use a five-calendar-day revision overlap clamped to the original anchor. Every accepted target row for the selected code must be explained by a verified source receipt and match its newest covering receipt; unexplained target rows fail closed.
- The target is required to use the dedicated `java_d019_index_daily_market_<suffix>` isolated table name. Planning/read/write recheck physical target identity. The typed QWP path caps writes at 250 rows / 1 MiB, waits for ACK separately from verification, and exact-key full-field readback includes nullable metrics and frozen `update_time`.
- `IndexDailyMarketIndependentReadback.verify(jdbc, ledgerPath, table, runId)` reads the frozen ledger/receipt directly and independently compares the full twelve-column physical range. It does not call the business source reopen method or mapper.
- Resume restores the prior frozen request through `FrozenRunRequest`; cancellation reads only the new run's cancellation record.
- No tests, compilation, Tushare request, or QuestDB query/write was run by this implementation worker. D018 serial acceptance, shared service/CLI/registry integration, compile/test checks and real isolated acceptance remain coordinator-owned.

## Shared integration required

Coordinator should add `IndexDailyMarketDataset.definition(table)` and `IndexDailyMarketReadRepository` to `DatasetConfiguration` / read-group routing, add `IndexDailyMarketSyncJobOwner` and adapter supported modes to job registration, expose a dedicated `app.sync.index-daily-market-table` setting, and add a precise CLI command that chooses a code from the frozen universe and invokes `plan(mode, tsCode, from, to, logicalDate)`, `run(plan)`, or `resume(priorRunId)`. Do not route this job to the externally owned formal `index_daily_market` table.
