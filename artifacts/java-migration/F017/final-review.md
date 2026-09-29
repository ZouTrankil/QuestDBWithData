# F017 CLI acceptance

Implementation: verified. Applicable validation: verified. Human review: pending_review.

| Requirement | Entry and evidence |
| --- | --- |
| list/show | list-sync-jobs/groups and version-specific show-sync-job/group; ManagementJsonCommandTest |
| validate/plan | validate-sync-job/group, plan-sync-job/group; strict typed parameters, frozen definitions and windows, no execution; planning and JSON command tests |
| run/resume | Existing run-stock-basic-job/group with explicit codes/date and resume-from; SyncJobCommandTest and WriteGroupCommandTest verify target service/prior identity and reject uncertain outcomes |
| status/history | show-sync-run and bounded read-only show-sync-history; actual SQLite rows, revisions and states checked in SyncRunHistoryTest and ManagementJsonCommandTest |
| cancel | cancel-sync-run persists the cancellation request; CLI test reopens SQLite and verifies state has not falsely changed to stopped or verified |
| group/schedule | Group list/show/plan/validate/run/resume plus F016 schedule put/status/enable/tick; no background timer enabled |
| non-writing planning | Real Spring startup and real Java child process leave the configured ledger/WAL absent; no runner owned by the pure planner |
| output/exit | Actual child processes verify 0 completed, 1 runtime failure, 2 invalid input, 3 incomplete outcome; result JSON on stdout, diagnostic stream on stderr |

`local-F017-full-a817`: 189 tests, 169 passed, 20 conditional external tests skipped, zero failures/errors. Final cancellation/status route check: `local-F017-cancel-final-657b`. `git diff --check` passed. Task-specific process, parsing, history and control tests do not claim new source writes.

Latest enabled full suite `local-F017-final`: 189 tests, 181 passed, 8 conditional skips, zero failures/errors. A later narrow CLI boundary fix rejects unknown options on legacy write/schema commands before calling their services; `CommandOptionBoundaryTest` passed after that edit. Group planning also accepts bounded `--overrides-file` for reliable Windows input, verified by `SyncGroupPlanningTest`.

Per the common functional-task contract, external source/write verification reuses the established single-dataset and group runners: F013 actual group recovery, F015 actual prepared-write group, and F016 scheduled runner readback. In particular `../F016/21766a9c38434835abd627e069706fcd/independent-values.json` proves one actual stock_basic row and seven SQL fields; it is prior evidence, not a new F017 data run. CLI routing tests use controlled services as the task card explicitly requests. No new dataset has been admitted by F017.

Definitions, PLANNED/VALIDATED/configured state, cancellation requests and VERIFIED outcomes remain distinct. Planning is local validation, not source permission/preflight proof. Legacy demonstration commands retain their documented legacy presentation; managed job/group/read/write/schedule routes provide structured results. A future dataset task must register its actual owner, bounded source adapter and invocation route; catalog presence alone does not authorize execution.

Examples and detailed boundaries are in plan-history-review.md, json-startup-review.md and process-output-review.md. Use parameters-file for reliable Windows JSON input. All earlier in-progress notes are retained as chronology and superseded by this acceptance. The full migration remains active; D001 is next.
