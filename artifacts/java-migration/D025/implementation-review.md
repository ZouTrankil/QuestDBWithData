# D025 implementation review

Status: `implemented_not_verified`. This is a static implementation handoff only. No Tushare request, QuestDB operation, build, or test was run by this task worker.

## Source contract checked

- Python `sync_moneyflow_ths` requests `pro.moneyflow_ths(trade_date=YYYYMMDD)` once per SSE trading date; it has no offset paging. The inspected connector/model documents a 6000-row per-call ceiling.
- The configured rate is 150 calls/min while the connector decorator is 1500/min. The Java job advertises the conservative 150/min policy, `moneyflow_ths.trade_date`; the actual account grant remains to be confirmed.
- Python's first-run floor is `20260101`; later runs start at `last_update + 1`. The Java implementation uses that floor and receipt-backed incremental checkpoints with a five-calendar-day overlap to cover revisions.
- Source errors that Python catches into empty frames are treated as errors in Java. Successful raw responses are retained with a SHA-256 fingerprint; incomplete evidence does not turn a failed request into a verified empty date.
- The 13 physical fields map explicitly. Business key and DEDUP key are `(ts_code,trade_date)`. `trade_date` is a UTC-midnight calendar-date carrier. Amounts remain in 10,000 CNY units; percentage and price values are not rescaled; null values remain null.

## Java implementation surface

- Dataset/domain/DTO/mapper: `MoneyflowThsDataset`, `MoneyflowThsKey`, `MoneyflowThs`, `TushareMoneyflowThsDto`, `MoneyflowThsMapper`.
- Source and run path: `MoneyflowThsSource`, `MoneyflowThsSyncJobOwner`, `MoneyflowThsSyncAdapter`, `MoneyflowThsCoverage`, `MoneyflowThsJobService`.
- Read/write: `MoneyflowThsReadRepository`, `MoneyflowThsWritePort`. Writer bounds batches to 250 rows/1 MiB, performs ACK and full-key/all-field readback, and requires an isolated physical target.
- Independent acceptance oracle: `artifacts/java-migration/operations/MoneyflowThsIndependentReadback.java`, entry point `verify(JdbcTemplate, Path ledger, String table, String runId)`. It parses original receipt JSON independently, checks receipt SHA and run evidence, confirms the physical SSE calendar window, then queries the isolated table directly and compares all 13 columns. Invocation cap is ten SSE open dates, 60,000 raw rows, and 160 MiB of receipt data.

## Shared integration requested from coordinator

- Register `MoneyflowThsDataset.DEFINITION` and `MoneyflowThsSyncJobOwner.DEFINITION` in shared catalog/registry wiring.
- Register CLI plan/run/resume/status handling. `MoneyflowThsJobService` exposes `plan(Mode, LocalDate bootstrapFrom, LocalDate requestedTo, LocalDate logicalDate)`, `run(Plan)`, `resume(String priorRunId)`, and `runAsGroupChild(String childRunId, String parentRunId, String expectedTarget, FrozenRequest request)`.
- Configure the isolated target property `app.sync.moneyflow-ths-table`, defaulting to `java_d025_moneyflow_ths_acceptance` (must be renamed to a unique acceptance suffix when executing against a nonempty shared environment).
- Attach endpoint policy `moneyflow_ths.trade_date` to the shared Tushare limiter at no more than the conservative configured 150/min ceiling; also preserve `endpointPerMinute` and per-endpoint override restrictions.
- Add D025 to the write-group dispatcher using the configured table name and freeze the physical target identity before the child run.

## Remaining acceptance

D024 must pass its serial acceptance first. Then build/test the integrated workspace and execute bounded real Tushare requests against a unique isolated YEAR/WAL/DEDUP table. Record first nonempty write/readback, idempotent same-window replay, later increment with overlap, and resume/cancel/unknown-write evidence. A 6000-row date must remain rejected as potentially truncated until a separately evidenced complete paging contract exists. No formal production DDL or Flyway migration is proposed.
