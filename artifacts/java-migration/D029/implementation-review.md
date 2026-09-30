# D029 implementation handoff — `margin_detail`

Implementation status: `implemented_not_verified`.

## D029-owned Java surface

- `TushareMarginDetailDto`, `MarginDetail`, `MarginDetailKey`, `MarginDetailDataset`, `MarginDetailMapper`
- `MarginDetailReadRepository`, `MarginDetailWritePort`
- `MarginDetailSource`, `MarginDetailTradingDates`, `MarginDetailCoverage`, `MarginDetailSyncAdapter`, `MarginDetailSyncJobOwner`, `MarginDetailJobService`
- Independent value oracle: `artifacts/java-migration/operations/MarginDetailIndependentReadback.java`, API `verify(JdbcTemplate, Path ledgerPath, String table, String runId)`.

## Implemented execution contract

- The physical schema projection is 11 columns: `trade_date TIMESTAMP`, `ts_code SYMBOL`, nullable `name STRING`, required finite nonnegative financing balance columns `rzye`/`rzmre`, and six optional finite DOUBLE provider measures. Values stay in provider units. Natural and QuestDB UPSERT key are `(ts_code,trade_date)`.
- Only tables matching `java_d029_margin_detail_<explicit suffix>` are writable. The provided isolated DDL is YEAR/WAL/DEDUP UPSERT; no production DDL or Flyway migration is added. Root must bind `${app.sync.margin-detail-table}` to the explicit isolated table.
- Each SSE open session becomes one unpaged `margin_detail(trade_date=YYYYMMDD)` request. API cap 6,000, raw receipt cap 32 MiB, run window 14 calendar days/14 source slices, network retries inherit the shared three-attempt bounded job policy, and writes are limited to 250 rows/1 MiB. Exact-cap, malformed fields, duplicate/out-of-range keys, invalid numbers, request errors, and empty source sessions fail closed with retained unverified raw evidence where possible.
- The exact Python date-footer rule is applied before mapping. Source maps are sorted by `(ts_code,trade_date)` and JSON map keys are sorted, making SHA receipts stable across JVMs.
- Default mode is incremental. Empty-target bootstrap begins at the Python table configuration start `2026-01-01` (caller may choose another explicit bounded start); each plan covers at most 14 calendar days. Subsequent plans overlap the verified checkpoint by seven calendar days. Planning caps `to` to the latest SSE open date strictly before frozen `logicalDate`, matching Python's one-session source publication delay.
- Only contiguous verified incremental runs advance the receipt-backed checkpoint. BACKFILL requires an existing same-target incremental checkpoint and stays inside it; its later verified receipts overlay date-level expected values but do not advance checkpoint coverage.
- Before planning, all rows in the explicitly configured isolated target must be explained by latest verified raw receipts for the same static physical target generation. New runs freeze target ID and count/range baseline. Resume restores the prior frozen request, retains the physical ID, and lets the generic runner reuse prior verified slices only after refetching the identical receipt and full-key/full-value readback.
- A write whose outcome remains unknown is left `IN_DOUBT` with its interval lease retained by the shared runner. D029 does not blindly replay or release that lock; separate stopped-writer evidence and exact reconciliation are required before another run. No D029-specific automatic IN_DOUBT closer has been added.
- The adapter refuses a source partition that omits existing natural keys because Python has no deletion/tombstone contract. A data deletion or endpoint correction that removes a code therefore remains a visible manual reconciliation boundary, not a synthesized zero or silent delete.
- The independent oracle verifies run receipt SHA and raw response contract, then directly queries each requested date and compares all 11 physical values by `(ts_code,trade_date)`. It does not call D029 source, mapper, or write port.

## Root integration API

`MarginDetailJobService.planDetailed(mode, from, to, logicalDate)`, `run(Plan)`, `run(FrozenRequest)`, `resume(priorRunId)`, `restorePlan(runId)`, `status(runId)`, `entries(runId, afterId, limit)`, and `cancel(runId)` are ready for shared CLI dispatch. Root owns the shared dataset/read registration, CLI route, explicit isolated table configuration, endpoint policy, and write-group route.

## Acceptance still required

- Confirm Tushare account permission/effective limit and execute a nonempty isolated run below 6,000 rows per date.
- Query and verify all 11 fields with the independent helper, rerun the same frozen window, and run the next 7-day-overlap incremental window.
- Exercise cancellation/resume against an isolated target. Inject or observe an unknown write and confirm it remains `IN_DOUBT` until the coordinator performs stopped-writer exact reconciliation; do not treat a retained lock as resumable.
- Confirm the D028 ordering gate before actual D029 live acceptance; the current handoff contains no D029 source/QuestDB evidence.
- Coordinator compile/integration and completion-register update remain pending. User review remains `pending_review`.

No Gradle/full build, test, source request, or QuestDB operation has been run by this handoff. A D029-only `javac` compile completed to `var/d029-agent-classes`.
