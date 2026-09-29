# D013 `etf_basic` implementation review

## Implemented source scope

- `TushareEtfBasicDto`, `EtfBasic`, and `EtfBasicKey` separate the 25 source fields from the fixed-epoch physical key and Java observation timestamp.
- `EtfBasicDataset` declares the 27-column external contract, YEAR/WAL/DEDUP, `(ts_code,timestamp)`, a D013-only isolated DDL helper, and a hard isolated-table name check.
- `EtfBasicMapper` maps all fields by name, handles only the source's three date null sentinels, preserves null status, and rejects invalid dates, numeric values, market and key values.
- `EtfBasicSource` calls the shared `TusharePageService` once with `market=E`, applies the 15,000-row no-page contract, validates response completeness/uniqueness, and persists source or unverified raw evidence before emitting rows.
- `EtfBasicReadRepository` exposes full-row reads by exact complete key and a bounded half-open Java-observation-time range.
- `EtfBasicWritePort` writes only a `java_d013_etf_basic_<suffix>` target, freezes/checks static target identity, limits batches to 250 rows/1 MiB, awaits QWP acknowledgement, and exposes exact-key full-field readback plus WAL settlement.
- `EtfBasicSyncJobOwner` and `EtfBasicSyncAdapter` provide the `data.etf_basic@1` full SNAPSHOT owner and split the single complete source response into at most two runner pages of 10,000 rows.
- `EtfBasicJobService` provides `plan(logicalDate)`, `run(plan)`, `resume(plan, priorRunId)`, `restorePlan(priorRunId)`, `resume(priorRunId)`, `status(runId)`, `entries(runId, afterId, limit)`, and `cancel(runId)`. Resume reconstructs the exact frozen request (including original target and observation time) from the ledger; a cancelled prior run's cancellation flag is not inherited by its new resume run.

## Shared integration requested

The shared policy catalog needs the exact slice ID `etf_basic.market_snapshot` (owner definition uses existing `tushare.shared` and `questdb.full_key_values`). The endpoint budget should cap `fund_basic` at no more than the Python connector's 20 requests/60 seconds and remain under the global budget. The shared CLI should expose `data.etf_basic` plan/run/status/cancel and resume-from through `EtfBasicJobService.restorePlan` / `resume(priorRunId)`; this job accepts no date window or incremental mode. Read/write group routing is optional for this single-object deliverable; if added, resolve `EtfBasicReadRepository` and `EtfBasicWritePort` for the isolated table only.

No shared registry, CLI, endpoint configuration, read/write group, generated projection, Flyway, README/manifest, completion register, or user configuration file was edited by this worker.

## Static checks and remaining acceptance

Static review only. No Gradle/build, tests, Tushare source request, or QuestDB write/read was run. The new Java classes remain uncompiled in this task; coordinator compilation is required after shared wiring.

Before D013 can be marked verified, D012 must pass its serial live-acceptance prerequisite. Then integrate the policy/endpoint budget/CLI, compile, create and identify an isolated `java_d013_etf_basic_<suffix>` table, obtain a nonempty real source response below the cap, write and independently read back every physical field by full key, verify a same-frozen-plan retry is idempotent, and run a fresh snapshot to confirm `update_time` changes while source values are reconciled. Also verify cap/empty/error/cancel/resume behavior without ever mutating formal `etf_basic`. A cap hit must retain raw evidence and fail closed. Codes absent from a later source response remain retained; safe deletion is deliberately not inferred from absence.
