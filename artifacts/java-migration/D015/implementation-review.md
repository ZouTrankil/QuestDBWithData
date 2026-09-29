# D015 `etf_adj` implementation review

Implementation status: `implemented_not_verified` (source, compile, and isolated data acceptance deferred to the coordinator).

## Added Java surface

- `TushareEtfAdjDto`, `EtfAdj`, `EtfAdjKey`, `EtfAdjDataset`, and `EtfAdjMapper` freeze the three-field contract and map source `trade_date` to logical date `trade_date` and physical QuestDB carrier `timestamp`.
- `EtfAdjReadRepository` provides typed complete-key and half-open date-range reads with all three logical fields.
- `EtfAdjWritePort` sends batches of at most 250 rows and 1 MiB, checks the frozen isolated table identity before sends/readbacks, checks QWP ACK separately, and returns full-key/full-value readback. Date inventory is limited to 10,000; per-date reconciliation is limited to 10,000 rows.
- `EtfAdjSource` requests one exact SSE-session date at a time, pages with documented `limit=2000` and `offset`, rejects repeated keys/pages, date drift, page/row-budget exhaustion and source errors, and only emits a runner page after observing a short terminal page. Complete receipts preserve sorted raw source rows, field list, page offsets/counts, parameters, source completion, and a content fingerprint. Incomplete responses preserve all completed raw pages and attempted offsets as unverified evidence.
- `EtfAdjSyncAdapter` freezes the sorted calendar date list and emits one runner slice per trade date. `EtfAdjSyncJobOwner` declares the incremental, bounded backfill and reconcile modes, dependency on the exchange calendar, `etf_adj.trade_date` slice policy, five-calendar-day repair overlap, 366-calendar-day window, 10,000 rows/date and 6 source pages/date bounds.
- `EtfAdjCoverage` advances only same-target verified INCREMENTAL receipt chains with the original bootstrap anchor. It chooses the latest date receipt by verified summary `updatedAt` (run ID only breaks equal timestamps) and compares the complete actual target date inventory and every covered date's full rows to source receipts. BACKFILL/RECONCILE receipts can be inspected through the ledger but do not advance the checkpoint.
- `EtfAdjJobService` requires an explicit `java_d015_etf_adj_*` target, freezes target identity/date window/checkpoint/physical range in the run, applies completed-source end-date resolution, includes actual target min/max in catch-up planning, rechecks the physical range and incremental receipt/value baseline immediately before a fresh run, exposes `plan`, `run`, `resume`, `status`, `entries`, and `cancel`, and restores the exact old frozen request on `resume(priorRunId)`. A prior run's cancellation flag is not inherited by its recovery run.

## Shared integration required from the coordinator

This task intentionally did not edit shared registries, CLI, read/write groups, or endpoint config. Integration must:

1. Register `EtfAdjDataset`/`EtfAdjReadRepository`, `EtfAdjSyncJobOwner`, and `EtfAdjJobService` in shared dataset/job/read/write wiring; add slice policy ID exactly `etf_adj.trade_date`.
2. Add `fund_adj` endpoint limiting no higher than 20 requests/minute (Python connector decorator is 20/min; the YAML table setting is 25/min; shared global request budget remains in force).
3. Add single-dataset CLI commands following existing daily-job shape: `plan-etf-adj-job` and `run-etf-adj-job`, with `--logical-date`, optional `--from` (required for initial bootstrap and bounded BACKFILL/RECONCILE), optional `--to`/`--mode`, and run-only `--resume-from` that calls `EtfAdjJobService.resume(priorRunId)` to restore exact saved scope.
4. Add exact typed dispatch for `etf_adj` in the shared group facade if that facade is the selected application read/write entry point.

## Deferred live acceptance

- Compile D015 classes together with coordinator wiring and run only relevant offline checks.
- Use the D015 isolated target prefix; do not alter the external table. Capture at least one real `fund_adj` date including every offset page and an independent all-field source-vs-QuestDB exact-key comparison.
- Prove repeated same-plan idempotency, checkpoint-backed increment with actual target-range reconciliation, a repaired overlap/revision run, cancellation/resume, and QWP unknown-ACK reconciliation. Record real retry/rate outcomes without claiming incidents that did not occur.
- Resolve/record source-account entitlement and shared endpoint quota before source calls. Keep human review `pending_review`.
- Tushare exposes no source snapshot token through the shared client; offset consistency is checked through duplicate/page/date validation and must be independently reconfirmed during real acceptance.
