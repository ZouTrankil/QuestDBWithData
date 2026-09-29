# F017 non-executing planning and read-only history

Status: in progress; not yet verified as a complete task.

Added `plan-sync-job` and `validate-sync-job` with explicit job ID/version/logical date, optional typed JSON parameters, supported mode and paired finite window. Both freeze the same registry definition used by execution. Output explicitly includes `executed=false` and `dataVerified=false`, with PLANNED or VALIDATED status. SyncJobPlanning owns no source, writer, target connection or ledger. Parameter types are checked without string/number/boolean coercion; duplicate JSON fields, extra documents, unknown parameters, unsupported modes and incomplete windows are rejected.

Added `show-sync-history` using the SQLite read-only API. It returns bounded run summaries, parent/job/version/logical date, actual ledger state and revision. Pagination uses immutable run IDs in ascending lexical order, explicitly labelled `runIdAscending`; it is not chronological sorting. `nextAfter` is the last returned ID, and clients stop on an empty page. Frozen payloads are omitted from the summary. Missing ledgers are not created by inspection.

Example application arguments:

```text
plan-sync-job --job data.stock_basic --version 2 --logical-date 2026-09-29 --parameters '{"codes":["000001.SZ"]}'
validate-sync-job --job data.stock_basic --version 2 --logical-date 2026-09-29 --parameters '{"codes":["000001.SZ"]}'
show-sync-history --ledger var/sync-ledger.sqlite3 --job data.stock_basic --limit 20
```

`local-F017-plan-713d`: 14 targeted tests passed, no failures/errors/skips, covering pure planning, strict input rejection, actual SQLite filtering/paging/read-only behavior and existing CLI boundaries. These checks do not perform new external sync or writes. End-to-end command output/exit-code acceptance and remaining unified management behavior are still required before F017 completion.
