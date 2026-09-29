# D001 owner, CLI and sync group verification

D001 remains running. These checks do not close its read/write composition acceptance.

The registered owner derives per-exchange checkpoint candidates from the durable ledger and checks actual target coverage before planning. A shared request starts at the earliest exchange start, retaining the bounded revision overlap. Plans bind physical target identity; resume requires the original frozen request. Group children share parent cancellation and preserve parent/child ledger linkage. Completed-child recovery rereads source and target through VerifiedRunRecovery before reuse.

- `local-D001-owner-live-803d`: real source and isolated QuestDB owner execution verified four rows, reopened the ledger, checked four target rows, then verified eight rows across the expanded overlap. Final planning checked eight actual rows. Evidence: `owner-f9a4ed4db14a447e9e1e0ce2a0877b92/owner-readback.json`.
- `local-D001-cli-specific-6c72`: calendar CLI bounded planning and incomplete-result handling passed.
- `local-D001-group-wiring-a195`: calendar CLI, Spring startup, group planning and schedule regression selections passed. The preceding a194 build failed on an incorrect package qualification, corrected before this run.
- `local-D001-group-live-e805`: registered calendar group pulled eight actual rows and verified the isolated QuestDB target; the child ledger references its parent. Resuming the completed group revalidated the child and reused it; final independent SQL returned eight rows. Evidence: `group-08b7c5b182c442aaa9dd63efee350783/group-readback.json`. Successful test-owned table removed.

New `run-sync-group` consumes the same bounded typed parameters as `plan-sync-group`, with optional `--resume-from`. Registered manual definitions now include the calendar-only group and ordered calendar/stock-basic reference composition. Only the calendar-only composition received live verification here; the two-dataset composition has not yet received live acceptance. Resumption requires the exact frozen per-member windows, not a newly advanced bootstrap plan.

Source receipts and revalidation evidence are under the run-specific `var/sync-evidence` directories, identified by run IDs in the JSON records. No production table write or background schedule was started. Human comparison remains pending_review; total completed tasks remains 17.
