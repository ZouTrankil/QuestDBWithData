# D010 implementation review

Status: `implemented_not_verified`. This review records static implementation, not data validation.

## Added APIs

- `StockLimitDataset.DEFINITION`, `.definition(table)`, `.createIsolatedTableSql(table)`; canonical object `stk_limit`, storage partition YEAR/WAL/DEDUP and full key `(ts_code,trade_date)`.
- `TushareStockLimitDto`, `StockLimit`, `StockLimitKey`, and `StockLimitMapper` provide explicit raw DTO, strict `YYYYMMDD` mapping, finite-or-null numeric handling, and full-value persistence encoding.
- `StockLimitReadRepository.findByKey`, `.findRange`, and `.find` require all four columns and use `QuestDbBoundedReader` stable complete-key pagination.
- `StockLimitWritePort` requires a frozen `static-v2-*` target identity, only admits `java_d010_stk_limit_<suffix>`, checks schema/WAL/YEAR/dedup before write, sends batches up to 250 rows / 1 MiB, waits for QWP ACK, and separately performs full-key/all-column readback with WAL settlement. It exposes `.readExistingDates()` and `.readDate(date)` for safe target reconciliation.
- `StockLimitSource.fetch(date, cancelled)` calls Tushare once per exact date, rejects duplicate/out-of-scope rows and the 5800 cap, and stores response/receipt evidence. `.reopen(path, fingerprint, date)` validates persisted source evidence for checkpoint use.
- `StockLimitSyncJobOwner.DEFINITION` declares job `data.stk_limit@1`, modes INCREMENTAL/BACKFILL/RECONCILE, shared `tushare.shared` rate policy, slice policy `stk_limit.trade_date`, full-key verification policy, calendar dependency, and finite 366-day/5800-row-per-slice budgets.
- `StockLimitSyncAdapter` freezes an ascending list of SSE open dates and emits one durable source slice per date, including successful empty dates. It has no all-market code-group or undocumented offset fallback.
- `StockLimitJobService.plan(mode, bootstrapFrom, requestedThrough, logicalDate)`, `.run(plan)`, and `.resume(plan, priorRunId)` provide explicit isolated target binding, bounded bootstrap, verified INCREMENTAL checkpoint, five-calendar-day revision overlap, catch-up to an already present in-range physical maximum, and target identity revalidation.
- `StockLimitCoverage` only advances from contiguous same-target VERIFIED/VERIFIED_EMPTY INCREMENTAL runs anchored at the explicit bootstrap date. It validates date receipts and reconciles all physical business dates and rows to latest receipts, ordered by `RunSummary.updatedAt` and stable run-ID tie-break only.

## Shared integration completed

- `DatasetConfiguration` admits `stk_limit.trade_date`; component discovery registers `StockLimitReadRepository` and `StockLimitSyncJobOwner` through the existing `DatasetImplementation` and `SyncJobOwner` lists.
- `TushareProperties` caps the `stk_limit` endpoint at `min(50, endpointPerMinute, configured stk_limit limit)` while retaining the shared limiter.
- `CommandLineRunner` exposes `plan-stk-limit-job` and `run-stk-limit-job`, requiring explicit `--to` and `--logical-date`; `--from` is accepted for bootstrap/BACKFILL/RECONCILE and run-only `--resume-from` resumes the durable run.
- `StockBasicWriteGroupService` admits `stk_limit` prepared writes using `StockLimitMapper`, `StockLimitWritePort(table, member.targetId(), jdbc, questdb)`, and frozen-target revalidation. Generic `ReadGroupConfiguration` derives read bindings from registered READ definitions, so no separate D010 read-group branch is needed.

The CLI usage is `plan-stk-limit-job|run-stk-limit-job --to YYYY-MM-DD --logical-date YYYY-MM-DD [--from YYYY-MM-DD] [--mode MODE] [--resume-from RUN_ID]`. No integration path targets the formal `stk_limit` table.

Configured property name is `app.sync.stk-limit-table`; default is the isolated-only `java_d010_stk_limit_acceptance`. No user configuration was changed here.

## Not verified here

No Gradle/build/test was run per coordinator instructions. No live source or QuestDB was touched. D009 predecessor confirmation, an isolated YEAR/WAL/dedup target, a nonempty real Tushare sample, full key/value readback, identical rerun, later incremental/checkpoint, 5800 cap handling, cancellation/resume, and BSE calendar parity remain for coordinator acceptance.
