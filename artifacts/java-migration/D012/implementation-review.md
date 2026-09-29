# D012 Java implementation review

Status: `implemented_not_verified`; static review only. Build, tests, source access, and isolated QuestDB acceptance are deferred to the coordinator.

## Added D012 files

- `src/main/java/com/zoutrankil/questdbwithdata/client/dto/StockStNameChangeDto.java`
- `src/main/java/com/zoutrankil/questdbwithdata/domain/StockStPeriod.java`
- `src/main/java/com/zoutrankil/questdbwithdata/domain/StockStDailyKey.java`
- `src/main/java/com/zoutrankil/questdbwithdata/domain/StockStDaily.java`
- `src/main/java/com/zoutrankil/questdbwithdata/domain/StockStDailyDataset.java`
- `src/main/java/com/zoutrankil/questdbwithdata/mapper/StockStDailyMapper.java`
- `src/main/java/com/zoutrankil/questdbwithdata/repository/StockStDailyReadRepository.java`
- `src/main/java/com/zoutrankil/questdbwithdata/repository/StockStDailyWritePort.java`
- `src/main/java/com/zoutrankil/questdbwithdata/repository/StockStDailyStorage.java`
- `src/main/java/com/zoutrankil/questdbwithdata/repository/StockStDailyStaging.java`
- `src/main/java/com/zoutrankil/questdbwithdata/service/StockStTradingDates.java`
- `src/main/java/com/zoutrankil/questdbwithdata/service/StockStDailySource.java`
- `src/main/java/com/zoutrankil/questdbwithdata/service/StockStDailySyncJobOwner.java`
- `src/main/java/com/zoutrankil/questdbwithdata/service/StockStDailySyncAdapter.java`
- `src/main/java/com/zoutrankil/questdbwithdata/service/StockStDailyCoverage.java`
- `src/main/java/com/zoutrankil/questdbwithdata/service/StockStDailyJobService.java`
- `src/main/java/com/zoutrankil/questdbwithdata/service/StockStDailyPublication.java`
- `src/main/java/com/zoutrankil/questdbwithdata/service/StockStDailyRunRecovery.java`

## Static behavior

- The canonical business/physical key is `(ts_code,timestamp)`, and stored `is_st` must equal `1`. Absence denotes no positive ST membership.
- The dataset definition describes only the audited external YEAR/WAL/DEDUP shape. `createIsolatedTableSql` is for a D012-owned isolated acceptance table; this implementation adds no Flyway migration and never defaults to formal `stk_st_daily`.
- Bootstrap is bounded to at most 366 calendar days from an explicit `--from`; incremental runs require same-target receipt-backed contiguous coverage and re-fetch the last 30 calendar days, clipped to the bootstrap anchor. BACKFILL is finite and does not advance the incremental checkpoint.
- When incremental `--to` is omitted, planning freezes the supplied logical date; BACKFILL requires an explicit `--to`.
- One validated daily page is streamed to the shared durable runner for each SSE session, including explicitly verified empty sessions. Output and source evidence have per-request bounds, raw annual responses are persisted before derived pages, and cap/incomplete source conditions cannot become successful empty slices.
- The stable logical checkpoint identity is separate from the frozen QuestDB physical generation. Every execution checks its frozen physical target; incremental checkpoints continue only after the full bounded physical table is explained by the latest raw-source receipts.
- Annual raw rows are ordered by the complete namechange fields and serialized deterministically. Daily receipts reference annual evidence by safe relative filename plus hash, so identical source content receives the same fingerprint across run directories. Reopen still rehashes and re-derives every daily row from the raw annual responses.
- For any nonempty SSE session window, the adapter buffers the complete bounded source window, creates an isolated stage containing the untouched rows outside the window plus the authoritative positive ST rows inside it, verifies every staged key/value, then asks the generic ledger consumer to verify each page against that stage before publication.
- `StockStDailyPublication` serializes table swaps with a D012-specific durable SQLite mutex and records both table generations, complete content fingerprints, bounded window, retained outside rows, source fingerprint, and the hashed stage receipt. The old table remains under an isolated backup name. `StockStDailyRunRecovery.finishInterrupted` can finish only a journal-owned exact original/old-moved/published layout after stopped-writer confirmation, then independently checks raw receipts, the complete window, preserved outside rows, backup, and ledger slices before finalizing the run.
- Receipt-backed checkpoints accept only VERIFIED/VERIFIED_EMPTY INCREMENTAL runs with the exact D012 definition, stable logical target, anchor, calendar, trade-date receipt set, source hashes, and re-derived physical rows. BACKFILL does not advance them.
- A shortened/revoked ST interval is corrected automatically by the same authoritative-window stage publication used for incremental runs. No `is_st=0` rows are synthesized and the formal table is not deleted or renamed.
- `restorePlan(priorRunId)` reconstructs the exact failed/cancelled frozen request without replanning; it refuses changed physical generations or a run with an unresolved publication. `finishInterrupted(runId, writerStopped)` exposes D012 publication/ledger recovery separately.

## Coordinator integration needed

1. Connect CLI `--resume-from` through `restorePlan(priorRunId)` and expose stopped-writer publication recovery through `finishInterrupted(runId, writerStopped)`; the coordinator owns the shared CLI/write-group lines.
2. Ensure D012 write-group members freeze and construct their adapter with `physicalTargetId()`; stable logical IDs are for sync checkpoints across journaled table generations.
3. Configure an isolated `java_d012_stk_st_daily_<suffix>` table using `StockStDailyDataset.createIsolatedTableSql`; never mutate the audited formal table.
4. Compile and run targeted tests after shared edits settle. Confirm D011 actual acceptance before any D012 live run, then perform bounded Tushare source → isolated stage → complete `(ts_code,timestamp)` / `is_st` readback, same-range idempotence, a later incremental, empty-session evidence, cap/failure behavior, cancellation/resume, and a deliberate interval-retraction plus interrupted-publication recovery.
