# D002 owner failure boundaries

`local-D002-boundary-a71f` passed StockDetailOwnerBoundaryLiveTest and StockDetailCommandTest (three tests total). The boundary test uses synthetic provider outcomes with a real isolated QuestDB table containing a nonempty preserved row, including the legacy literal `None` date representation. It is failure-boundary evidence, not additional real-source ingestion evidence.

- Three successful empty status responses produce VERIFIED_EMPTY with no publication and preserve the existing row.
- Provider IOException produces FAILED, never VERIFIED_EMPTY.
- A durable cancellation requested during source access produces CANCELLED before publication.
- An existing whole-dataset lease rejects the competing run as DATASET_INTERVAL_BUSY without another provider call, retaining the original lease.
- Run and attempt states agree for the empty, failed and cancelled cases; their owned leases are released.
- Actual QuestDB identity, all physical row values and fingerprint match the pre-run snapshot after all four cases. The successful isolated target was then dropped.

The generated `boundary-*/boundary-readback.json` retains the actual before/after rows, ledger path and all four results. Existing real-source owner evidence remains in owner-review.md. D002 remains running: these checks do not complete prepared-write group admission, all recovery cases or final dataset acceptance.
