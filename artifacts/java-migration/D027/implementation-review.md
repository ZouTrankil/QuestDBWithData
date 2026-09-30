# D027 implementation review

Status: `implemented_not_verified`. This note records code-level and source-contract facts only. No Tushare request, QuestDB query/write, Gradle build, or test was run by this implementation pass. The coordinator owns the serial D026 gate, shared CLI/registry/write-group integration, live evidence and completion-register changes.

## Source and schema contract

- Python source: `moneyflow_hsgt_sync.py` calls `pro.moneyflow_hsgt(start_date, end_date)` once per range and declares `@api_rate_limit(api_limit=150, period=60)`. It has no offset contract; the implementation requests at most 31 calendar days at a time and rejects a response of 300 rows as possibly capped/truncated.
- Source fields: `trade_date`, `ggt_ss`, `ggt_sz`, `hgt`, `sgt`, `north_money`, `south_money`. The natural business key is one aggregate row per `trade_date`; no physical UPSERT key is declared or invented.
- All six metrics are nullable doubles in provider million-CNY units. Null stays null; no zero fill or scale conversion is applied.
- The Python model declares YEAR partitioning, while the coordinator-provided read-only physical schema evidence confirms the target layout is `trade_date` / DAY / WAL / DEDUP=false. This implementation preserves the physical layout and does not add a Flyway migration or change the audited production table.

## Write and recovery model

`MoneyflowHsgtWritePort` only appends to a same-schema isolated stage. It caps ILP batches at 250 rows/1 MiB and reads every business field back. The stage is created as a full snapshot of target rows outside the requested date window; source rows authoritatively replace the whole window, including a zero-row window. Before publication, immutable raw receipts are reopened and checked against the staged rows and the outside-window snapshot.

`MoneyflowHsgtPublication` serializes publication with a file lock and a SQLite journal. The journal proves the original and replacement snapshots before the target→backup and stage→target renames, including each side's recorded QuestDB `directoryName`; the replacement directory is frozen from the stage receipt and is compared without recomputing a static identity under the post-rename formal table name. Recovery accepts only journal-recognized layouts and revalidates the receipt-to-stage/target values. A journaled publication remains recovery-required until its owning ledger run reaches `VERIFIED`/`VERIFIED_EMPTY`, even if table publication already reached `VERIFIED`; later runs remain blocked until `finishInterrupted` reconciles that ledger entry and lease. A stage-only failed run can be discarded only after writer-stop confirmation and an unchanged original target proof. Normal resume replays the frozen range into a new run after that proof; it does not claim reuse of the discarded staged slice.

## Planning and limits

- Initial INCREMENTAL requires an explicit user `--from`; `--to` defaults to the completed Shanghai calendar date and cannot exceed either the frozen logical date or the completed-source ceiling.
- The maximum frozen run is 366 calendar days, split into no more than twelve <=31-day unpaged source requests. The API cap is 300; cap hits fail closed.
- INCREMENTAL advances only from same-logical-target, same-definition VERIFIED contiguous raw-receipt coverage, and refetches the trailing five calendar days. BACKFILL/RECONCILE require explicit bounds within already verified coverage and do not advance the checkpoint.
- Planning rejects nonempty physical dates not backed by verified source coverage and compares those rows with the newest receipt for each covered date. Full-table snapshots are bounded to 100,000 rows and 64 MiB.
- Owner policy references are `tushare.shared`, `moneyflow_hsgt.range31`, and `questdb.full_row_stage_replace`; the coordinator must register the endpoint slice and limiter policies without increasing the actual authorized quota.

## Independent readback

`artifacts/java-migration/operations/MoneyflowHsgtIndependentReadback.java` reads the run's verified journal and FETCHED ledger receipts directly, checks receipt SHA-256 and frozen request/field bounds, parses raw JSON independently of the production DTO/mapper, then SELECTs all seven physical fields in each source window. It detects duplicate dates, missing/extra rows, schema drift and null/numeric mismatches. It is an operational helper and has not yet been run against a live target.

## Coordinator integration still required

1. Register `MoneyflowHsgtDataset.definition(configuredTable)` as the dataset's read definition and provide the configured isolated table to `MoneyflowHsgtJobService`.
2. Register `MoneyflowHsgtSyncJobOwner` with the job registry, and add D027 plan/run/resume/finish/status CLI branches. Preserve explicit first-run `--from`; no hard-coded bootstrap date.
3. In the write group, bind D027's member to the configured target and its stage-only `MoneyflowHsgtWritePort`; do not write directly to the non-DEDUP target.
4. Register the shared source limiter and D027 `range31` slicing contract using the verified effective quota.
5. After the D026 serial gate, execute bounded nonempty source→isolated write/readback, same-window rerun, later incremental/revision, empty-window replacement, failure/cancellation and journal recovery checks. Run the independent helper on each successful acceptance run and record actual checkpoint/row evidence.

## Static check

A targeted `javac` compile of the D027-owned Java sources and independent helper passed into `var/d027-agent-classes` using the coordinator-provided D024 dependency classpath. Javac reported an unchecked-operation warning in `MoneyflowHsgtStaging`. No tests, full build, source request, or QuestDB operation were run.
