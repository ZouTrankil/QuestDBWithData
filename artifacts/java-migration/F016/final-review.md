# F016 acceptance

Implementation and applicable data validation: verified. Human review: pending_review.

SyncScheduleDefinition, SyncScheduleStore and SyncScheduleManager provide explicit job/group targets and versions, parameters, local time zones, daily/weekly/month-end/minute interval rules, calendar predicates, enable/disable, bounded misfire and persistent reentry prevention. Status includes next candidate time and bounded history. No background timer is installed. The application service routes explicit ticks to the existing job/group services; no duplicate connector or writer was introduced.

Commands and disabled example are described in `live-dispatch-review.md` and `schedule-example.json`. Import validates the registered target and finite sample parameters. Exchange-session scheduling at the sample application boundary remains rejected until an authoritative calendar owner is admitted; the core injected calendar predicate and local-date behavior are tested. A disabled definition can show a next calendar candidate but does not execute.

`local-F016-closeout-6e42` completed successfully with zero failures/errors. XML results and totals are recorded in `docs/migration-tasks-20260929/results/F016.json`. Coverage includes due/repeated tick, disabled registration, explicit enable/disable, bounded catch-up and skip, original logical date, DST gap/overlap, month-end weekday selection, minute intervals over the seven-day maximum window, concurrent SQLite claims, persisted run IDs, PARTIAL and IN_DOUBT outcomes, reopening storage, malformed JSON and CLI routing.

Real source/write/read evidence: `21766a9c38434835abd627e069706fcd/schedule-readback.json`, verified separately by `tools/verify_schedule_evidence.py` into `independent-values.json`. One real stock_basic row passes seven-column SELECT comparison through the existing runner. Reopening the ledger and ticking the same slot makes no additional dispatch. The owned isolated table was removed only after successful verification. This establishes the scheduling seam; stock_basic remains current-snapshot-only and no historical incremental capability is claimed.

An unresolved CLAIMED or IN_DOUBT slot blocks subsequent runs of that schedule. No time-based lock release or automatic replay is allowed. Generic unknown-write reconciliation is not added by this task. Schedule cancellation means disabling future dispatch; a running task retains the existing runner's cancellation and recovery controls.

Earlier in-progress reviews are retained as chronology and superseded by this acceptance. F017 remains the next serial task; the full migration is not complete.

Latest enabled full regression `local-F016-final`: 173 tests, 165 passed, 8 conditionally skipped, 0 failures/errors. This includes both isolated live schedule tests. A separate independent comparison of the `java_f016_schedule_*` sample is saved at [independent-values.json](afd79db68c8d4e1dbf7a67bba431426c/independent-values.json), also 1 row and 7 fields matched.
