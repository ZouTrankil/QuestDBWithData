# D023 implementation review

Status: implementation snapshot only; root coordinator owns shared CLI/config/group wiring and live acceptance.

## Field contract

| Source/physical field | Java type | Handling |
|---|---|---|
| `ts_code` | `String` / SYMBOL | Required DC-qualified board code; exact text retained |
| `trade_date` | `LocalDate` / TIMESTAMP | BASIC `YYYYMMDD` business date, stored at UTC midnight only as a calendar carrier; YEAR partition |
| `name` | nullable `String` | Exact source text |
| `leading` | nullable `String` | Exact source text |
| `leading_code` | nullable `String` | Exact source text |
| `pct_change` | nullable `Double` | No percent/fraction conversion |
| `leading_pct` | nullable `Double` | No percent/fraction conversion |
| `total_mv` | nullable `Double` | No scaling; Python model says 万元, source unit remains to be confirmed |
| `turnover_rate` | nullable `Double` | No conversion; unit remains to be confirmed |
| `up_num` | nullable nonnegative `Integer` | Integral count only |
| `down_num` | nullable nonnegative `Integer` | Integral count only |

All 11 physical columns are declared explicitly. Natural identity is `(ts_code,trade_date)`. DEDUP stays false and the definition has no physical upsert keys. No formal DDL or Flyway migration was added; isolated target creation SQL is prefixed `java_d023_dc_index_` and leaves DEDUP disabled.

## Bounded sync and publication

- One non-paged `dc_index(trade_date=YYYYMMDD)` request per open SSE/SZSE date, with one source request at most per date and the Python-documented 5,000-row ceiling. Responses at the ceiling are retained as unverified raw evidence and rejected as potentially truncated.
- A run spans at most five calendar days. First incremental bootstrap requires an explicit start and an empty isolated target; later incremental plans require a contiguous receipt-backed checkpoint and re-read two calendar days before it. BACKFILL requires an explicit bounded range.
- Source pages are buffered before stage publication. The DEDUP=false stage copies all rows outside `[from,to]`, receives the source window once, and is compared as a full multiset snapshot before a `ReferencePublicationJournal`-tracked target/backup/stage rename. Empty daily receipts also replace their dates. Rows found on closed dates without a source receipt fail closed.
- Frozen requests contain both stable logical target identity and physical table generation. Current physical generation is checked against the verified D023 publication lineage. Publication recovery inspects only the recorded target/backup/stage layouts, reopens each frozen FETCHED raw receipt, checks the complete source/date/fingerprint contract, and compares the stage or published full snapshot plus source window before the first recovery rename. Conflicting layouts fail closed. `DcIndexRunRecovery` then closes a run only after the reopened raw receipts and published full snapshot agree; it releases only that run's uncertain interval lease.
- `DcIndexIndependentReadback.verify(jdbc,ledger,table,runId)` is the separate receipt-to-QuestDB oracle; it does not invoke the D023 source, mapper, or writer.

## Not yet accepted

No D023 provider request, QuestDB D023 isolated write/readback, build, or tests were run in this implementation turn. The official endpoint/schema mismatch is a mandatory source probe item. Root must wire `DcIndexDataset` into dataset/read registries, `DcIndexSyncJobOwner` into `SyncJobRegistry`, the CLI/job service into `CommandLineRunner`, and the pending-publication guard into the write group before any controlled acceptance. Keep `results/D023.json` at `implemented_not_verified` until that work and the serial D023 live acceptance finish.
