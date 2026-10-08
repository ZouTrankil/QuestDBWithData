# Original native publication recovery status

Observed at 2026-10-08 (Asia/Shanghai). Production JVM is managed by the root agent; this audit agent did not launch an application, job, provider request, POST request, database writer, or tests.

## Actual completion and reuse verification

The root operator invoked the explicit recovery once at 02:15 Asia/Shanghai. The original Market run, publication journal and recovered stage became VERIFIED; parent changed to WAITING_UPSTREAM. Original receipt SHA `648f9ad461b6967a40c6a764d2ac6ce406a77ca9103af6241c5e65f6e4c1c3f3` remains unchanged. New receipt `receipt-recovered-21a61c0b-5242-44b6-92d7-72ce693d3d1c.json` and the immutable recovery link bind the original run, source/publication SHA and unchanged source clocks.

After the sole Web restart, startup resumed the same instance using the sixth of twelve recovery claims. All five stages became VERIFIED and Spring Batch execution 7 completed at 02:19:47 in 108.973 seconds. The later native runs were Regime `regime-monitor-f28d7fcd-3a47-4115-847e-42d4af6b611f` and Backtest `backtest-materialize-eb1fb3e8-0cc5-4e4c-8d1e-48b83815c17d`; Market stayed on its original run.

The root made one same-date catch-up with key `acceptance-reuse-20261008-0222`. Independent before/after read-only audit PASS: 37 sync runs, per-owner counts, native journals, execution 7, five certificates and all owner artifact SHAs, six recovery claims, twenty source/target identities and WAL frontiers, and native metadata all remained unchanged. No new owner run, Spring execution or QuestDB publication occurred. Evidence: `same-date-reuse-audit.json`, both before/after ledger captures and both table clock captures in this directory.

Acceptance retains concrete limitations: September 30 margin source lacks SZ/BJ whole exchanges and optional margin-derived fields remain null; ETF holdings presence does not certify weight completeness, and 589330.SH latest disclosure is 127 days old. Deleted L2 temporary output uses historical verified import plus unchanged WAL 11386 and current full 7,888-row × 110-field typed quality/SHA; it is not a repeated raw-source comparison. Main acceptance itself records these distinctions.

The sections below describe the implementation and earlier pending state before these actual checks.
## Current unresolved operation

- Parent instance: `19598fab846fd1c3ecd35ceb77f317e9cc400d268881d5995fdeb4ea96690dd5`.
- Original run: `market-sentiment-49f30758-bfb8-4625-8241-8b6850ea94f8`.
- Sources stage is VERIFIED. Parent and original native run are IN_DOUBT.
- Both renames completed before the immediate WAL metadata snapshot failed. Current formal id 3027 and retained old table id 3019 are stable.
- Independent typed audit established the three-day 159 values and 101 outside rows match, full SHA `0f54a0e164250dcc50a82f3927a119ec13fbe1f50bd20c409c1e230496748e75` matches the journal. This does not itself complete the run.

## Implemented recovery seam (awaiting root build and actual verification)

POST `/api/v1/batch/main-strategy/instances/{instance}/reconcile-publication` accepts `stage`, `runId`, and `writerStopped: true`. An optional Idempotency-Key is recorded. It acquires the same job and instance leases, rejects queued or active owners and stale revisions, revalidates upstream certificates, and calls only the original owner's `finishInterrupted`.

The original IN_DOUBT receipt is retained. A new VERIFIED receipt and immutable recovery link bind the old/new receipt SHA, exact original run and frozen definition, immutable source/publication SHA, same instance/range, and unchanged source clocks. An automatic native resume accepts the original uncertain receipt only through this validated recovery chain. Parent becomes WAITING_UPSTREAM after the recovered physical certificate is stored, so the remaining three stages require a later normal catch-up. It cannot be declared fully VERIFIED here.

Market recovery now delegates to the existing shared NativeDailyWindowPublication, which verifies source rows, all retained outside rows, table identities, full hashes, ledger slices, and writes publication.json before completing the original run. Normal inline publication waits for the exact renamed table identity and settled WAL state.

## Pending actual checks

1. Root compiles classes, safely restarts the sole idle Web runtime, and invokes the explicit recovery request for the original run.
2. Confirm the original sync run/publication journal become VERIFIED; old receipt bytes stay unchanged; linked recovered receipt and stage certificate exist.
3. Root catch-up continues RegimeFeaturesMonitorDaily, BacktestDaily and MainStrategyAcceptance without a new Market run.
4. Record complete five-stage / Spring Batch state, then one root-requested same-day catch-up must be read-only reuse with no new sync run or QuestDB publication.