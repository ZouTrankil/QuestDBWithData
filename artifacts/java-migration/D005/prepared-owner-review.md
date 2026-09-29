# D005 prepared membership write evidence (2026-09-29)

Status: isolated prepared-write path verified for the cases below. D005 remains running until the full task card and final registry are reviewed.

The `write.index_member` path accepts an explicit local full-field batch for one L2 industry. Its receipt says `prepared-write-request`; it does not claim to be a Tushare `index_member_all` response. The source sync job remains `data.index_member` with separately verified classification and Y/N response receipts. Both paths use the same bounded QuestDB stage, publication journal, full-table snapshot and interval lease, but they recompute different merges: prepared input owns all 14 fields including observation time, while a source response preserves fields that endpoint does not supply.

The write owner rejects the literal formal `index_member` target. All tests below used uniquely named isolated YEAR/WAL tables. Each passing test compared the complete formal table snapshot before and after.

| Validation | Result | What it proves |
| --- | --- | --- |
| `local-D005-prepared-matrix-build` | 5 passed, 0 skipped, 0 failed | One and two member write groups, serial THS then membership publication, partial failure recovery and frozen child reuse against live QuestDB. |
| `local-D005-prepared-idempotent-build` | 5 passed, 0 skipped, 0 failed | Same full-field batch on a fresh run yields zero submitted stage rows; different observation or local legacy fields are treated as a revision in the prepared merge. |
| `local-D005-prepared-recovery-phases-build` | 2 passed, 0 skipped, 0 failed | A stopped writer after verified stage and a stopped writer after publication both recover from the frozen local receipt with explicit `writerStopped=true`; full rows and ledger are verified. |
| `local-D005-group-first-build` | 1 passed, 0 skipped, 0 failed | Registered source sync group publishes a saved real source sample to live QuestDB and reuses the verified child without another source request. This is fixture replay, not a fresh live Tushare call. |
| `local-D005-broad-no-source-build` | 28 passed, 15 opt-in source tests skipped, 0 failed | D005 unit and enabled isolated QuestDB paths compile and pass together. The skipped tests are not live-source evidence. |

The first prepared recovery run, `local-D005-prepared-recovery-first-build`, failed before mutation because frozen request JSON string order differed after reconstruction. Recovery now compares parsed JSON trees, while retaining exact run/target/receipt checks. The second and phase tests passed; the earlier failure artifact remains. The first `local-D005-prepared-write-first-build` process lost its output channel during compilation, so it is not counted as a pass.

Prepared publication remains non-atomic across multiple datasets. A write group publishes its members serially, and a partial group must resume from the frozen targets and revalidate completed children. Formal consumer cutover and use of historical `is_new=N` rows remain separate review decisions.
