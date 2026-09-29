# F017 JSON management output and planning startup

Status: in progress, not complete.

Added version-specific `show-sync-job --job ID --version N` and `show-sync-group --group ID --version N`, plus `list-sync-jobs` / `list-sync-groups` aliases. Listing rejects extra options; unknown versions fail before execution. Existing definition-list commands remain available.

ManagementJsonCommandTest parses captured command output as exactly one JSON document. Planning and validation show PLANNED/VALIDATED, executed=false, dataVerified=false and the frozen logical date/parameters. Listing and individual job lookup return registered definitions. History reads an actual SQLite ledger and preserves its unexecuted state and pagination cursor. No mocked job or legacy writer is invoked.

Review found that eager construction of StockBasicScheduleService created SQLite control tables even for planning. The service and its CLI injection now use lazy initialization. PlanningStartupTest launches the actual Spring application with plan-sync-job and a fresh ledger path, asserts the schedule service singleton is absent, and confirms neither the ledger nor its WAL is created before or after context shutdown.

Validation:

- `local-F017-json-1fc8`: targeted CLI, planning and history checks passed.
- `local-F017-startup-bf13`: 13 tests passed, zero failures/errors/skips, including actual application startup and command regressions.
- `git diff --check`: passed (line-ending notices only).

These checks establish command output and planning startup behavior, not additional source/write acceptance. Stable process exit codes and complete group planning remain outstanding for F017.
