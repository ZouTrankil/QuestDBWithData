# D018 implementation review

Status: `implemented_not_verified` as of 2026-09-30. The coordinator reported shared wiring and an earlier compile before the latest owned-file edits. The 256,000-row cap and BACKFILL receipt overlay changes below still require compilation. No test or isolated QuestDB readback was run by this implementation pass.

## D018-owned implementation

- Added `TushareEtfPortfolioDto`, `EtfPortfolioKey`, `EtfPortfolio`, `EtfPortfolioDataset`, and `EtfPortfolioMapper` for eight source fields plus derived `update_time`.
- Added `EtfPortfolioReadRepository` with full-key and bounded half-open announcement-date reads.
- Added `EtfPortfolioWritePort` with isolated-target identity checks, 250-row / 1 MiB sends aligned with the shared runner, acknowledged WAL writes, full-key readback, and complete announcement-date inventory/readback.
- Added `EtfPortfolioSource`, `EtfPortfolioSyncAdapter`, `EtfPortfolioSyncJobOwner`, `EtfPortfolioCoverage`, and `EtfPortfolioJobService` for bounded offset fetches, immutable raw receipts, runner-sized chunks, frozen plans, checkpoint/overlap, cancellation, resume, and physical/source reconciliation.
- No D018 Flyway migration was added. The formal table remains external-owned and read-only for this implementation.

## Bounded execution and evidence

- One source request is made per calendar `ann_date` over a maximum 45-day frozen interval. Incremental overlap is 30 calendar days and is clamped to the initial bootstrap anchor.
- Initial incremental bootstrap requires explicit bounds and an empty isolated physical target. A nonempty target with no matching verified receipt chain is rejected.
- Per announcement date: source paging uses Python-observed `limit=8000` and `offset`; Java caps at 256,000 rows and 33 requests and requires terminal short-page evidence. Per-run total rows are bounded at 1,000,000. The complete raw response is SHA-256-addressed (96 MiB per receipt). Each complete response is split into up to 26 ledger/write chunks, at most 10,000 rows each, with per-chunk receipts linked to the complete receipt. Resume reopens and hashes both receipts and checks chunk sequence/count/scope. This finite cap follows an observed 18,718-row source date and a separate source-only probe that captured nine full pages/72,000 rows before the former 64,000-row policy failed; the complete date total remains unknown and the cap is not a Tushare guarantee.
- After all chunks for a date are handled, the adapter compares every physical row and nullable value for that date to the full source result. Checkpoint loading repeats this comparison across the verified interval and rejects any unexplained physical date/key/value. A later verified BACKFILL may supply the latest per-date receipt for this physical comparison, while only contiguous verified INCREMENTAL runs advance the checkpoint. Source revocation that leaves old physical keys fails closed; safe deletion/publication is not implemented.
- Physical schema is created only by isolated acceptance setup using the `java_d018_etf_portfolio_` prefix; no official target DDL is applied by the job service.

## Shared integration required

The root coordinator owns these shared files and should wire:

1. `DatasetConfiguration`: register `EtfPortfolioDataset`, `EtfPortfolioReadRepository`, and `EtfPortfolioSyncJobOwner`; attach `data.etf_portfolio` to exact slice policy `fund_portfolio.ann_date`.
2. `TushareProperties`: configure `fund_portfolio` at no more than the task-card 200/minute setting, still bounded by credential-wide rate controls.
3. `CommandLineRunner`: add plan/run/status/entries/cancel and prior-only `--resume-from` dispatch. `EtfPortfolioJobService` exposes `plan(Mode, LocalDate bootstrapFrom, LocalDate requestedThrough, LocalDate logicalDate)`, `run(Plan)`, `resume(String priorRunId)`, `status(String)`, `entries(String,String,int)`, and `cancel(String)`.
4. Existing read/write group facades, if task dispatch requires them, must use the frozen configured isolated target. No shared wiring was edited here.

## Deferred acceptance

- Recompile after the latest cap, receipt, and BACKFILL-overlay changes; run targeted regressions by coordinator.
- Repeat bounded source paging on the selected real `fund_portfolio` dates under the current cap; confirm response schema and numeric/null semantics against raw receipt. A source-only diagnostic already observed 18,718 rows on 2026-08-27 and nine full pages/72,000 captured rows before the former 64,000-row policy stopped 2026-08-28; evidence is in `artifacts/java-migration/D018/provider-diagnostic/00cd6407-a7e9-46ab-b463-c2286a70ef46`.
- Create an explicitly isolated QuestDB target and compare all nine physical fields by the complete key; do not mutate formal `etf_portfolio`.
- Verify same-window rerun, checkpoint-backed next increment with the 30-day overlap, empty announcement dates, cancellation/resume, and failure behavior when the source is truncated, duplicated, or exceeds the local cap.
- D017 remains the plan's serial execution predecessor; D018 has not been run or marked verified.
