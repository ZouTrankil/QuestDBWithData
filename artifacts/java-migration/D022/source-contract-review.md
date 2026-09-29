# D022 source and storage contract review

Status: implementation in progress; no Tushare call, database write, test, or build was run by this task. Live acceptance is pending the D021 serial gate and coordinator integration.

## Python source contract

- Python connector: `src/quant_platform/data/adapters/connectors/index/index_monthly_sync.py`.
- Endpoint: `index_monthly`; one request for each frozen provider `ts_code`, with `start_date` and `end_date`. The endpoint has no offset/cursor pagination in this path.
- Decorator/config: 180 requests per 60 seconds and 900 second request timeout, as source-code configuration rather than a verified account quota.
- Monthly-enabled Python universe: 55 provider codes. Preserve provider code aliases exactly (including the CSI `.SH` aliases); the task-specific universe excludes codes absent from `sync_index_monthly`.
- API fields: `ts_code,trade_date,close,open,high,low,pre_close,change,pct_chg,vol,amount`.
- `layer` and `bucket` come from the frozen Python index universe. `update_time` is the frozen Java observation timestamp; it is not returned by Tushare.
- `trade_date` is the monthly observation business date carried at UTC midnight, not an instant. Metrics retain provider units and nulls; non-finite values are rejected.
- Local client cap: 1,000 response rows; hitting it fails closed because there is no next-page contract. One request per code/window, at most 3,660 calendar days, is bounded to approximately 120 monthly observations.

## Storage and revision policy

- Audited physical target is YEAR-partitioned WAL with `DEDUP=false`, no physical UPSERT key. Natural identity is `(ts_code, trade_date)`.
- Formal Flyway DDL is not added. The isolated acceptance table uses the same 14 columns, YEAR/WAL, and `DEDUP=false`.
- Corrections do not append/upsert directly. The implementation copies the complete target minus one frozen code/month window into a uniquely named stage; generic write batches target only that stage. The stage is checked against every preserved outside row plus the full authoritative source window, then target/backup renames are recorded in `reference_publications` and guarded by a publication file lock.
- A frozen request carries both stable endpoint/table `targetId` and current physical `physicalTargetId`; checkpoint key includes logical target and provider code. Before stage writes, a forced run-owned intent binds the frozen-request fingerprint, FETCHED source receipt path/hash/count, target generation, original full snapshot, preserved outside rows, and the exact READY stage generation. `finishInterrupted(runId, writerStopped)` holds the dataset OS lock while it validates either an existing publication journal or, for a stage-only crash, the single READY intent and FETCHED event, reopens the raw receipt, compares the unchanged target and the entire stage against outside rows plus the authoritative source window, and only then creates the normal rename journal and publishes. Normal planning/publication scans stage-intent directories and blocks every orphaned, unverified intent from another run, so release of an OS lock by a crashed process cannot let a disjoint window replace the frozen target first. Missing/torn/ambiguous intent, changed target, or incomplete/extra stage rows fail closed and remain unresolved.
- Existing physical keys absent from a source response fail before stage creation; deletions are not inferred from a partial response.

## Pending acceptance

The coordinator must add shared dataset/read/write/job/CLI wiring, compile, run offline checks, then perform bounded live-source and isolated QuestDB runs after D021 is verified. Acceptance must include full physical-field comparisons, repeated-window replacement, incremental checkpoint, cancellation/recovery, and proof that both formal and isolated targets remain non-DEDUP.
