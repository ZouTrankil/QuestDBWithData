# D031 implementation handoff — `margin_zrz`

Status: `implemented_not_verified`.

## D031-owned Java surface

- `TushareMarginZrzDto`, `MarginZrz`, `MarginZrzKey`, `MarginZrzDataset`, `MarginZrzMapper`
- `MarginZrzSource`, `MarginZrzCoverage`, `MarginZrzSyncAdapter`, `MarginZrzSyncJobOwner`, `MarginZrzJobService`, `MarginZrzTargetIdentity`
- `MarginZrzReadRepository`, `MarginZrzStorage`, `MarginZrzStaging`, `MarginZrzWritePort`, `MarginZrzPublication`
- Independent raw-receipt readback helper: `artifacts/java-migration/operations/MarginZrzIndependentReadback.java`

## Implemented semantics

- `MarginZrzJobService.plan(mode, from, to, logicalDate)` requires an explicit frozen logical date and explicit `to`. It preserves the caller’s logical date while constraining this retired owner’s source `to` to a completed date and the audited historical ceiling, 2025-07-25. An empty isolated target requires an explicit historical `from` and bootstraps an INCREMENTAL receipt chain from that anchor.
- Subsequent INCREMENTAL plans overlap the same-target verified checkpoint by five calendar days and are bounded to 366 days. The checkpoint is built only from verified INCREMENTAL runs. BACKFILL/RECONCILE do not advance it. Receipt selection by `summary.updatedAt` provides per-date latest-value validation, with run ID as a stable tie-breaker.
- A nonempty isolated target without a verifiable same-target incremental chain is rejected. The full physical snapshot (identity, row count and canonical all-field digest) is frozen. Existing dates must be represented by the latest applicable raw source receipt before a replacement can proceed.
- `slb_len` is called once per frozen inclusive range, with one range cursor `YYYYMMDD..YYYYMMDD`. The source response is immutable SHA-256 evidence. An all-empty response is recorded as a failed/unverified operation and cannot create a stage or checkpoint.
- The six-column natural key is `trade_date`. Because the observed object is YEAR/WAL/DEDUP=false, writes go only to a same-layout isolated staging table. The stage copies the full target outside the frozen window, replaces the window, verifies all six values and preserved outside rows, then publishes using a per-dataset OS lock and journaled QuestDB `RENAME TABLE ... TO ...`. Unknown publication state remains recoverable; the formal `margin_zrz` object is never a write target.
- Cancellation is checked around source/stage/publication. `resume(priorRunId)` restores the exact frozen request via `FrozenRunRequest.restore`; it does not replan dates or target identity. Unpublished stage-only evidence requires explicit stopped-writer reconciliation.
- `MarginZrzIndependentReadback.verify(jdbc, ledgerPath, table, runId)` independently reads the raw source receipt, selects all six physical columns for the frozen range, and reports missing, extra, duplicate or value-mismatched natural dates. Null metric values are compared as nulls.

## Shared integration still needed

No shared config or CLI file was edited. Coordinator integration should:

1. Register `MarginZrzDataset`/`MarginZrzReadRepository` in the dataset registry and add the owner’s slice/verification policy references (`slb_len.start_end.bounded_range`, `questdb.year_wal_full_row_stage_replace`) to `DatasetConfiguration` policy allow-lists.
2. Add an explicit manual D031 plan/run/resume/status/cancel/publication-recovery CLI route and bind `app.sync.margin-zrz-table` to a specifically isolated table. Keep this owner out of daily scheduling. An empty-target historical bootstrap must pass explicit `--from` and `--to`.
3. Ensure the write-group path does not direct-append this DEDUP=false dataset; D031 uses its own full-snapshot staged publication path.

## Acceptance not performed here

No Gradle build, tests, Tushare source request, or QuestDB operation was run by this implementation handoff. A scoped `javac` check may be performed separately as reported in `results/D031.json`. Real-source permission/current endpoint availability, nonempty historical retrieval, same-window rerun, incremental progression, cancellation/recovery, unknown-publication recovery, and six-column isolated QuestDB readback remain coordinator acceptance items. Do not mark the task verified until those complete.

