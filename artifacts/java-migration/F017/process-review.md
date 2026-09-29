# F017 process outcome contract

Still in progress; full task acceptance remains pending.

The application main now closes its Spring context after a finite command, releasing owned HTTP/database resources. Exit codes are 0 for a completed command, 1 for runtime failure, 2 for invalid input and 3 for an execution outcome that did not pass verification. IncompleteCommandException distinguishes failed/partial/unknown read, write, sync, group and schedule outcomes from startup failures. Wrapped exceptions are classified through their cause chain. Error termination emits a small JSON status to stderr without copying exception messages into that status.

CliProcessTest starts the real Java main in child JVMs. Planning exits 0, malformed logical dates exit 2, and neither creates a schedule ledger. Classification tests cover wrapped incomplete outcomes (3) versus runtime failures (1). Existing command tests verify incomplete outcomes throw the typed exception through its IllegalStateException base. Codes 1 and 3 have not yet been demonstrated as separate real child-process execution cases.

Initial child-process validation exposed Windows stripping quotes from inline JSON arguments. Added mutually exclusive `--parameters-file PATH` for job and group planning, read with a 64 KiB bound and the same strict typed JSON validation. This avoids relying on shell-specific JSON quoting. Inline JSON remains available when the caller preserves its quotes.

Example: write `{"codes":["000001.SZ"]}` to a UTF-8 parameters file, then pass:

```text
plan-sync-job --job data.stock_basic --version 2 --logical-date 2026-09-29 --parameters-file parameters.json
```

`local-F017-process-file-e84c` passed targeted process, command and job/group planning tests with zero failures/errors/skips. Earlier failing process builds are diagnostic history, superseded by the successful file-input process test. Spring diagnostics may still accompany command output; only the command JSON is currently validated as a single document. Complete process-output acceptance remains outstanding.
