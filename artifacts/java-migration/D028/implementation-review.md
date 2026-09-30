# D028 implementation handoff — `margin_all`

Implementation status: `implemented_not_verified`.

## D028-owned Java surface

- DTO/domain: `TushareMarginAllDto`, `MarginAll`, `MarginAllKey`, `MarginAllDataset`, and `MarginAllMapper`.
- Source and job: `MarginAllSource`, `MarginAllSyncJobOwner`, `MarginAllCoverage`, `MarginAllSyncAdapter`, `MarginAllJobService`, and `MarginAllTargetIdentity`.
- Typed read/write and non-DEDUP publication: `MarginAllReadRepository`, `MarginAllWritePort`, `MarginAllStorage`, `MarginAllStaging`, and `MarginAllPublication`.
- Independent raw-receipt-to-physical-value oracle: `artifacts/java-migration/operations/MarginAllIndependentReadback.java`, callable as `MarginAllIndependentReadback.verify(jdbc, ledgerPath, isolatedTable, runId)`.

## Implemented semantics

- Candidate table prefix is `java_d028_margin_all_`; the default job-service candidate is `java_d028_margin_all_acceptance`. The frozen request has both a stable logical target ID and the current physical generation ID. The formal `margin_all` target remains read-only.
- The isolated DDL helper creates `trade_date TIMESTAMP, exchange_id SYMBOL, rzye/rzmre/rzche/rqye/rqmcl/rzrqye/rqyl DOUBLE`, with `TIMESTAMP(trade_date) PARTITION BY YEAR WAL`; it deliberately omits DEDUP and any invented UPSERT key.
- Job ID is `data.margin_all`, version 1. Supported modes are bounded INCREMENTAL/BACKFILL/RECONCILE. Default INCREMENTAL uses explicit bounded bootstrap from 2026-01-01 on an empty target; a nonempty target without same-logical-target receipt coverage fails closed. Later runs overlap five calendar days and cap to 366 days. The caller's `logicalDate` stays frozen as supplied; only the source `to` date is clamped to the completed-day ceiling at 20:30 Asia/Shanghai.
- Source slices are one `margin(trade_date=YYYYMMDD)` request per calendar day, rate policy reference `tushare.shared`, cap 4,000/request, one page, no offset, and 4 MiB maximum receipt. Cap-sized responses and provider errors fail closed. Provider metrics stay in source units and must be finite/non-null.
- Full-target validation compares every physical row and every date against the latest verified same-target receipt (ordered by verified `updatedAt`, with run ID as deterministic tie-break). Only INCREMENTAL intervals with the same explicit anchor form the checkpoint chain. BACKFILL/RECONCILE cannot initialize or advance it; they may provide later receipt values for already checkpoint-covered dates.
- The isolated write path batches at most 250 rows/1 MiB, uses complete `(trade_date,exchange_id)` readback, and only appends into a journal-owned stage. The stage copies all target rows outside the frozen window, replaces only the requested window, and is checked against every raw source receipt and complete all-field physical snapshot before journaled publication. Target/stage IDs, full snapshot fingerprints, frozen request fingerprint, and source receipts are bound into durable evidence. A source omission of any existing key aborts rather than deleting it.
- `finishInterrupted` finishes a publication only from a validated journal layout and receipt; stage-only state can be safely discarded only when the writer is stopped and the frozen request/physical baseline still match. Incomplete stage evidence is never promoted to the target.

## Coordinator integration still required

Do not wire D028 into shared files from this task branch. Root should add:

1. `MarginAllDataset.definition(configuredTable)` to shared dataset registration and the read group.
2. `MarginAllSyncJobOwner`/`MarginAllJobService` as the `data.margin_all` route and `CommandLineRunner` D028 dispatch, including plan/run/status/cancel/resume/finish-interrupted.
3. An explicit `app.sync.margin-all-table` isolated acceptance target; keep the formal `margin_all` object out of write routing.
4. A shared endpoint budget no higher than 150/minute and a write-group entry that references this canonical job rather than duplicating its source logic.

## Checks and remaining acceptance

- Read-only inspection of D028 task card, common contract, Python connector/model, schema audit, and root's 2026-09-30 physical metadata check.
- The D028 Java surface and independent readback helper passed a scoped `javac` compile into the isolated `var/d028-agent-classes` output directory. No Gradle build, tests, Tushare source request, or QuestDB operation was run by this handoff. Root owns integration and live acceptance after D027's serial gate.
- Remaining: coordinator integration; targeted compile; real nonempty isolated first write; identical frozen-window rerun; incremental overlap; independent nine-field readback; full stage/journal recovery and cancellation/unknown-write checks; confirm current account permission and shared 150/minute pacing; then update result/status only from captured evidence.
