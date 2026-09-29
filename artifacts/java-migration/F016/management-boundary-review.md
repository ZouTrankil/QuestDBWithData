# F016 management boundary review

Status: in progress. Human review: pending_review.

`local-F016-service-728c` passed 11 tests, with zero failures, errors or skips:

- `StockBasicScheduleServiceTest`: 2 tests.
- `SyncScheduleBoundaryTest`: 4 tests.
- `SyncScheduleManagerTest`: 5 tests.

Results are in `var/local-F016-service-728c-build/test-results/test/`.

The service tests import a disabled JSON definition, reopen SQLite, explicitly enable and disable it, and verify no job is dispatched merely by registration or status inspection. Duplicate JSON properties, unknown properties, unsupported exchange-calendar scheduling and duplicate stock codes are rejected without creating a schedule.

The manager tests use fixed clocks. RUN_ONCE preserves the original scheduled logical date, while lateness beyond the declared window is MISSED. Dispatcher exceptions persist IN_DOUBT, block same-slot replay and subsequent-day dispatch after reopening SQLite. PARTIAL remains PARTIAL and retains its run ID.

Two independently opened SQLite stores concurrently claim the same slot. Exactly one claim succeeds and only one history record exists. DST and local-date boundaries remain covered by the earlier review.

This is implementation verification using SQLite and controlled dispatchers. No source request or QuestDB write occurred in these checks. The initial service-test build failed because its fixture instantiated the static StockBasicDataset catalog; the fixture now supplies DatasetImplementation through the registered definition and the final build passes.

F016 is not yet verified: command routing, actual same-runner linkage and applicable live acceptance still require completion. No real background schedule has been enabled.
