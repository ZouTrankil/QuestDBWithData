# D001 verified coverage and incremental runner

Status: in progress, not admitted as a completed dataset yet.

ExchangeCalendarSyncAdapter freezes the single-dataset job (default INCREMENTAL, explicit bounded BACKFILL/RECONCILE), exchanges, dates, finite page/row budgets and 366-day revision policy. It passes complete source slices to the existing SyncJobRunner and real calendar port, preserving run/attempt/slice states, cancellation and verification gates. The bootstrap lower bound can explicitly shorten the revision lookback.

ExchangeCalendarCoverage rebuilds candidate checkpoints from same-target, expected-version VERIFIED root runs. It merges only contiguous intervals beginning at the explicit anchor and stops at gaps; exchanges stay independent. PARTIAL and other-target runs do not advance candidates. The caller must still query actual target coverage before using a candidate; this helper does not substitute ledger metadata for QuestDB data. Its history scan is bounded at 10,000 runs.

`local-D001-coverage-731b`: five coverage/slice tests passed, including gaps, interval merging, separate exchange progress and rejection of partial/other-target records.

`local-D001-runner-live-f16a`: real source/SQLite/QuestDB test passed. Initial SSE/SZSE September 25–26 sync verified four rows. Reopening SQLite reconstructed each exchange checkpoint at September 26, and actual complete-key readback confirmed the stored days. Incremental planning then reread September 25–28 under the declared overlap policy. The second real source request set verified eight rows; the target contained eight unique rows and both checkpoints advanced to September 28. The test-owned table was removed after success.

Evidence: `runner-d6626c8cac914a59aba2d296eac30986/runner-readback.json`, first/second source folders, and independent-values.json. The independent Python verifier compares the second original source responses to final SQL rows: eight matched, zero missing/mismatched/duplicate keys. This proves overlapping incremental coverage and new-day additions, not a naturally occurring source revision.

Remaining: production owner derives and verifies these checkpoints automatically, binds physical target identity, exposes CLI and read/write/sync composition, verifies recovery/error cases and registers D001 completion. No production table was written in this acceptance.
