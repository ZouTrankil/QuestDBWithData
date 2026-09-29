# F016 calendar and durable reentry review

Status: in progress, not verified. Human review: pending_review.

Reference: `D:/work/fund_2/back-monitor/src/quant_platform/common/runtime/job_catalog.py`.

Validation command: `tools/run_local_validation.py --build-name local-F016-boundaries-84ef --test '*SyncScheduleBoundaryTest'` using the reference project's `uv run python` environment.

Three tests passed, zero failures or skips. Evidence: `var/local-F016-boundaries-84ef-build/test-results/test/TEST-com.zoutrankil.questdbwithdata.service.SyncScheduleBoundaryTest.xml`.

- DST nonexistent wall times are skipped; overlapping wall times execute only at their first occurrence.
- Exchange-session filtering retains the local logical date when the corresponding UTC instant is on the previous date. Exhausted calendar coverage returns no next slot.
- SQLite definition round-trip preserves a disabled schedule. After reopening the store, an unresolved IN_DOUBT submission prevents a subsequent slot from executing; duplicate slots and terminal-result rewrites are rejected.

Fixed the persistent reentry predicate to include IN_DOUBT as well as CLAIMED. This prevents a new schedule slot from bypassing an unresolved prior submission. This is not a reconciliation mechanism and does not clear unresolved runs.

These are calendar and SQLite implementation checks, not external sync evidence. No real timer or sync was started. F016 still requires runner integration, management entrypoints, due/misfire/disabled behavior, history linkage and applicable external acceptance before verification.
