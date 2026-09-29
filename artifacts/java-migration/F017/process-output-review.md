# F017 process output and exit acceptance

`local-F017-exits-91d2` passed CliProcessTest: three tests, zero failures/errors/skips. Four actual application child processes establish:

| Case | Exit | Evidence |
| --- | --- | --- |
| Valid bounded plan | 0 | Standard output parses as one JSON document, PLANNED and executed=false; no ledger created |
| Invalid logical date | 2 | Empty standard output, failure exit status on standard error; no ledger created |
| Missing read-only history ledger | 1 | Runtime failure, absent ledger remains absent |
| Unresolved prior schedule claim | 3 | SKIPPED_REENTRY JSON, null new run ID, no source dispatch |

Application logging is routed to standard error via logback-spring.xml and the startup banner is disabled in main. Standard output belongs to command results. Errors keep diagnostics and a small failure-status JSON on stderr; stderr as a whole is a diagnostic stream, not a single JSON document. This supersedes the earlier process-review limitation regarding runtime/incomplete exit-code coverage and stdout logging.

Schedule import and enable commands now also produce JSON with executed=false and dataVerified=false. They describe configuration changes rather than successful data runs.

Group planning additionally supports explicit `from:null,to:null` member overrides to clear a shared range for a snapshot member, preserving the existing SyncGroupPlan contract. The complete regression including this follow-up is `local-F017-full-a817`; final results must be checked before F017 registration.
