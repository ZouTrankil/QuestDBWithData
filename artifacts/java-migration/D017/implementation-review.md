# D017 implementation review

Review date: 2026-09-30. No build, test, source request, or QuestDB write/readback was run for this review.

## Owned implementation

- Full explicit source DTO, domain key/value type, 89-column dataset contract, same-name mapper, typed bounded read repository, and isolated verified write port.
- One `fund_factor_pro(trade_date=YYYYMMDD)` request per frozen SSE trade date. The 8,000 response cap, duplicate/out-of-range keys, required source fields, finite numerics, immutable raw response receipt, and successful empty-response distinction are enforced by the source contract.
- Bounded 366-calendar-day planning with explicit bootstrap on an empty target, a five-calendar-day revision overlap clamped to the original anchor, physical max-date ceiling checks, receipt-backed contiguous checkpoint construction, target inventory/full-row reconciliation, and frozen physical target identity.
- `EtfFactorJobService` has plan/run/prior-only resume/status/entries/cancel entry points. New runs validate the frozen baseline before the first source/write action; resume restores the durable frozen request and requires its request/target fingerprint to match.
- `artifacts/java-migration/operations/EtfFactorIndependentReadback.java` is a coordinator-runnable independent verifier. It reads immutable raw receipts and compares all 89 physical fields per `(ts_code,trade_date)` without calling the source, mapper, or write port. It has not been run.
- Formal `etf_factor` remains externally owned. The only DDL helper enforces the prefix `java_d017_etf_factor_`.

## Shared integration requested from coordinator

1. Add `EtfFactorDataset`, `EtfFactorReadRepository`, and `EtfFactorSyncJobOwner` to the shared dataset/job configuration and provide the adapter mode set to `SyncJobRegistry`.
2. Add typed D017 read and write dispatch in the existing read-group and write-group facades. Do not duplicate D017 business logic in those facades.
3. Add D017 `plan`, `run`, `resume`, `status`, `entries`, and `cancel` dispatch to `CommandLineRunner`, including prior-only recovery via `EtfFactorJobService.resume(String priorRunId)`.
4. Configure `fund_factor_pro` at the currently authorized endpoint/credential rate in shared Tushare configuration; do not use the Python 30/min decorator as an account entitlement.
5. Provision a disposable, explicitly isolated `java_d017_etf_factor_<suffix>` target matching `EtfFactorDataset.createIsolatedTableSql`; do not add formal-table migration SQL.

## Acceptance still outstanding

Compilation after shared integration, targeted offline mapping/planning regressions, a nonempty bounded real `fund_factor_pro` response, exact 89-column isolated-table readback, idempotent same-window rerun, a later checkpoint/overlap run, and cancellation/unknown-write recovery are not established by this review. Keep the task `implemented_not_verified` until the coordinator records those results.
