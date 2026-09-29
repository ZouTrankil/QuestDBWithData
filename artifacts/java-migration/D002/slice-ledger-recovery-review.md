# D002 publication slice and observed recovery

The static owner now exposes run → attempt → snapshot slice through the existing management ledger. The slice represents one merged static publication; finite upstream requests remain individually evidenced, but partial fetching is not promoted into a completed publication checkpoint. Slice completion precedes attempt and run completion. Initialization is inside the protected failure path, so an initialization error does not automatically abandon an acquired ordinary lease.

`local-D002-slice-ledger-45a3` passed the real QuestDB prepared-write group and owner boundary tests. First prepared input publishes; replay keeps the same physical target and all values. Slice/run/attempt states are checked for verified, empty, failed and cancelled outcomes. The write input here is copied from production readback, not a new provider observation; the production table is unchanged.

`local-D002-slice-recovery-239c` deliberately rejected the slice completion update after successful real-source publication. All three entries correctly remained IN_DOUBT with a retained lease. The subsequent recovery exposed a real missing Instant deserializer in JobDefinitionJson. Its serializer already produced ISO values; deserialization now explicitly supports Instant, LocalDate, Duration and ZoneId and rejects numeric or invalid dates.

After that Gradle process returned terminal exit 1, `local-D002-observed-slice-recovery-821a` recovered the original retained failure scene through the application recovery API, with zero new provider requests and zero QuestDB writes. A separate read-only SQLite query confirmed RUN, ATTEMPT and SLICE all VERIFIED and zero remaining locks. The stopped-writer check still rejects false proof. No ledger state was manually edited.

- Retained ledger: `var/D002-slice-recovery-407b4bffa2764c65a96b30dca67dfe41.sqlite`.
- Run: `stock-detail-77b9d408-b3ea-45cf-bb88-1d8112776b51`.
- Actual recovery evidence: `observed-recovery-stock-detail-77b9d408-b3ea-45cf-bb88-1d8112776b51.json`.
- Original failure target and backup remain for inspection, now verified and unlocked.
- `local-D002-regression-slices-78cf` passed 252 tests total: 207 passed, 45 conditional skips, zero failures. This full suite preceded the temporal deserializer fix; the fix subsequently passed the temporal round-trip/rejection test and observed live recovery test. Final full regression remains required before dataset closure.

D002 remains running. Recovery before a completion receipt, full acceptance reconciliation, and retained evidence consolidation still need review; a recovered slice alone is not completion of the dataset task.
