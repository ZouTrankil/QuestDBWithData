# D030 implementation review

Status: `implemented_not_verified`. This is an implementation handoff; it does not claim live source or QuestDB acceptance. The coordinator owns shared registration and will run the D029 serial gate before live D030 work.

## Code shape

- `MarginSecsDataset` defines the four audited physical fields, the `(trade_date, ts_code)` business/UPSERT key, YEAR partitioning, and WAL/DEDUP enabled isolated DDL.
- `TushareMarginSecsDto`, `MarginSecs`, `MarginSecsKey`, and `MarginSecsMapper` express the raw provider mapping. Date is a business calendar date represented as a UTC-midnight calendar timestamp; `name` is nullable; `exchange` remains source text and is not inferred from the ticker suffix.
- `MarginSecsReadRepository`, `MarginSecsStorage`, and `MarginSecsWritePort` provide explicit typed reads, bounded snapshots, 250-row/1-MiB QWP writes, exact composite-key batch readback and per-date all-field readback. The write path only accepts the D030 isolated table namespace.
- `MarginSecsSource` calls the unpaged source once per date from a frozen SSE-open date list. It persists immutable raw response JSON, binds SHA-256 in the runner FETCHED event, rejects invalid rows/duplicates and fails closed at the conservative 6000-row cap.
- `MarginSecsTradingDates` freezes a complete daily SSE calendar cover and fingerprint. `MarginSecsSyncAdapter` verifies one source receipt and post-write readback per open date. Existing keys must be a subset of the provider response; absence has no safe delete interpretation under the Python contract.
- `MarginSecsCoverage` recomputes the contiguous incremental checkpoint from same-target verified runs and reopens/hash-checks their source receipts. BACKFILL/RECONCILE receipts can supersede per-date value evidence but do not advance checkpoint. A nonempty target without a verified incremental chain is rejected.
- `MarginSecsJobService` exposes plan/run/resume/status/entries/cancel and requires an explicit first bootstrap start. Normal incremental requests overlap five calendar days and are bounded to 31 calendar days. It refuses automatic replay of an IN_DOUBT run.
- `artifacts/java-migration/operations/MarginSecsIndependentReadback.java` is the independent raw JSON→SQL verifier. It parses original source receipt fields and queries the four physical fields directly without invoking the production mapper/source/write port.

## Source and schema facts

See [source-contract-review.md](source-contract-review.md) and the read-only [schema-preflight.json](schema-preflight.json). The source contract has one `pro.margin_secs(trade_date=YYYYMMDD)` call per SSE-open day and no offset pagination. The primary Python connector's decorator states 50 calls/minute; runtime quota and current account access remain unverified. A neighboring Python history helper treats 6000 rows as an unverified threshold, so the implementation rejects 6000 or more rather than claiming that value as the provider's official cap.

The coordinator's 2026-09-30 metadata shows `trade_date TIMESTAMP` designated, YEAR partition, WAL and DEDUP enabled; `trade_date` and `ts_code` are the only upsert-key columns. No production migration is added. Business key omission by a later source response fails closed because neither the Python sync nor its model defines tombstones.

## Integration contract for coordinator

The service's public planner is `MarginSecsJobService.plan(Mode, LocalDate from, LocalDate to, LocalDate logicalDate)`. Run/resume APIs are `run(Plan)`, `resume(String priorRunId)`, and `runAsGroupChild(childRunId,parentRunId,expectedTarget,FrozenRequest)`; status APIs are `status`, `entries`, `cancel`, plus `tableName`/`targetId`/`physicalTargetId`. The owner is `MarginSecsSyncJobOwner`; read registration is `MarginSecsDataset.definition(configuredTable)`; the configured target prefix is `java_d030_margin_secs_`.

Coordinator integration still needs the CLI/registry/config/read-group/write-group wiring and effective endpoint limiter registration. The initial `--from` must stay explicit. Live acceptance must use an isolated target after D029, include at least one nonempty response and independent helper comparison, repeat the same request, then verify an incremental overlap/checkpoint run and cancellation/resume behavior. Unknown writes remain `IN_DOUBT` until a stopped writer and exact physical readback have been reconciled.

## Verification boundary

The D030-owned Java sources and independent helper passed a scoped `javac` compile into `var/d030-agent-classes` using the coordinator-provided D024 dependency classpath. No tests, Gradle/full build, Tushare source call, QuestDB D030 query/write or completion-register edit was performed. The task result is `implemented_not_verified` with all live fields unset. Shared CLI and group registration are intentionally left for the coordinator.
