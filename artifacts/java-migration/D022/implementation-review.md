# D022 implementation review

Implementation status: `implemented_not_verified`. No Gradle, javac, test, Tushare request, or QuestDB operation was run by this worker. D021 remains the serial predecessor and live acceptance is deferred to the coordinator.

## D022 entry points and behavior

- `IndexMonthlyJobService.plan(mode, code, from, to, logicalDate)` freezes provider code, source window, observation timestamp, logical endpoint/table ID, current physical table ID and incremental anchor.
- `IndexMonthlyJobService.run(plan)` executes one code/window. `resume(priorRunId)` restores the frozen request and refuses to replay a run that has a stage-only artifact. `finishInterrupted(runId, writerStopped)` requires stopped-writer proof, then delegates to the exact-layout publication reconciler or stage-only recovery.
- `IndexMonthlySyncAdapter.fetch` performs one bounded source request. Nonempty pages are written by the generic batch executor to a run-owned stage while an OS publication lock serializes target cloning, writing, checking and both renames across every code/date window. Empty pages are a no-op only after the source-omission guard confirms there are no existing keys to remove.
- `IndexMonthlyStaging` copies the full target except the frozen code/date interval into a YEAR/WAL stage with no dedup keys. It compares the full target snapshot against preserved outside rows plus the complete source window before publication.
- `IndexMonthlyPublication` records exact physical IDs, directories, fingerprints and source/stage evidence in `reference_publications`. It only finishes original, old-moved or published layouts that match those exact receipts; conflicts fail closed. `IndexMonthlyStaging` durably records a PREPARING intent before clone and atomically promotes it to READY with the stage's exact physical identity before the adapter can send a row. When no publication journal exists, `finishInterrupted` requires one READY intent, one immutable FETCHED source event, exact frozen request fingerprint, raw source receipt SHA/shape, unchanged target physical generation and full before/outside snapshot proofs. It then compares every stage row to preserved outside rows plus the reopened source window, writes a fresh verified stage receipt, and uses the ordinary journaled rename path under the same whole-dataset OS lock. Planning and publication reject other unverified stage intents, preventing a later disjoint window from invalidating a crashed run's target snapshot. Missing or incomplete stage rows cannot publish. `IndexMonthlyIndependentReadback.verify(jdbc, ledger, table, runId)` independently parses raw receipts and compares all 14 physical columns.
- The formal table remains read-only; no Flyway DDL or formal target mutation is part of this implementation.

## Static constraints

- The complete source contract is documented in `source-contract-review.md`.
- Isolated and stage tables both declare `DEDUP=false`; source natural key duplicates, row-cap truncation, unknown physical rows, changed target identity, and source omissions fail before publication.
- Snapshots are bounded to 250,000 rows and 256 MiB canonical data; source windows are bounded to 3,660 days and 1,000 rows; every Tushare call is one unpaged code/window request.
- Incremental checkpoints remain receipt-backed and code/target scoped; verified BACKFILL/RECONCILE receipts are readback overlays only.

## Coordinator integration and acceptance still required

- Register the D022 read implementation and `IndexMonthlySyncJobOwner` in shared configuration/registry; add the exact single-job CLI plan/run/resume/finish entry points and service injection. Do not route D022 through direct formal-table writes.
- Ensure any group route freezes both `targetId` and `physicalTargetId` and retains dataset-level serialization for the entire stage-to-publish operation.
- Compile and run scoped offline checks, then after D021 verification perform real source/isolated-target first write, same-frozen-request replacement, fresh-backfill overlay, incremental checkpoint, empty response, cancellation and stopped-writer recovery. Independently verify all 14 columns and outside-window snapshot preservation.
- `results/D022.json` intentionally leaves implementation and data validation unverified; do not advance the serial gate until these coordinator checks pass.
- Before live recovery acceptance, exercise both existing-journal recovery and READY stage-only crash recovery with the writer stopped; confirm a deliberately incomplete stage remains unresolved and that a complete stage preserves the full outside-table snapshot.
