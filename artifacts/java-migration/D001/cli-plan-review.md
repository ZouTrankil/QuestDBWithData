# D001 process-level plan check

The actual application process ran `plan-exchange-calendar --exchanges SSE,SZSE --from 2026-09-25 --to 2026-09-28 --logical-date 2026-09-29` against the existing production calendar table using PGWire for read-only preflight. Gradle `run` exited 0 and the CLI returned `PLANNED`, `executed=false`, `dataVerified=false`, both exchanges, an incremental frozen request and a hashed physical target ID. It reported zero candidate checkpoints and zero checked target rows in the configured run ledger. No source request or QuestDB write was issued by this plan command.

The first attempt exposed a schedule-only SQLite control file at the default ledger path: it contained schedule tables but no `sync_runs`. `ExchangeCalendarCoverage.hasHistorySchema` now treats a file with no run-ledger tables as empty history and rejects a partial run-ledger schema. `ExchangeCalendarScheduleOnlyLedgerTest` passed. Repeating the same process command then passed, and the existing SQLite file retained its original size and last-write time.

This proves the plan entry and startup path, not the CLI run entry or D001 completion.
