# D024 implementation review

Status: D024 implementation is wired in the current source tree but remains `implemented_not_verified`; root controls compilation and serial live acceptance after D023.

## Implemented files

- `MoneyflowDataset`, `MoneyflowKey`, `Moneyflow`, `TushareMoneyflowDto`, and `MoneyflowMapper`: exact 20-column contract, (ts_code, trade_date) key, date as UTC-midnight calendar carrier, volumes in hands, amounts in 10,000 CNY, Python-compatible null-volume-to-zero behavior.
- `MoneyflowReadRepository` and `MoneyflowWritePort`: explicit typed reads, DEDUP=true isolated schema, frozen QuestDB physical identity, 250-row/1 MiB send bound, 10-second ACK wait, and generic full-key/full-value batch readback.
- `MoneyflowSource`, `MoneyflowSyncAdapter`, `MoneyflowSyncJobOwner`, `MoneyflowTradingDates`, `MoneyflowCoverage`, and `MoneyflowJobService`: one unpaged request per open date; 6,000 cap; 5-calendar-day/30,000-row run bounds; explicit initial incremental bootstrap; 2-calendar-day revision overlap clamped to its bootstrap anchor; receipt-backed incremental chain; restart/status/cancel API.
- `MoneyflowIndependentReadback.verify(jdbc, ledger, table, runId)`: independently reopens raw JSON receipts and compares all 20 fields for the frozen date window without using the production mapper/source/write port.

## Safety behavior

Rows are sorted by full key before deterministic receipt creation. Source failures, missing fields, duplicate keys, malformed numerics, out-of-range dates, and cap hits cannot produce a verified source page. Generic runner slices preserve cancellation, retries, attempts, and unknown-write full-key readback. Upserts are idempotent for the audited full business key. Before a date is sent, the adapter compares existing keys against the full source set and fails closed on a source-side key disappearance, because the audited policy has no proven delete/tombstone semantics.

Only verified `INCREMENTAL` intervals advance the checkpoint. `BACKFILL` requires an existing receipt-backed incremental checkpoint and must stay inside its covered range; its newer per-date receipts can supply the latest verified source snapshot for physical-value validation. Before planning/running, the target's actual distinct `trade_date` inventory, row count, min/max, and every row's full-key/full-field values must match the latest applicable raw source receipts. Planning applies the Python completed-source ceiling through `DailySyncEndDate` (20:30 Asia/Shanghai), and an existing later target date blocks incremental planning but does not block a historical BACKFILL.

## Shared integration and remaining acceptance

The current source tree exposes `MoneyflowReadRepository` as a `DatasetImplementation`, registers the owner via Spring component scanning, includes the D024 CLI plan/run/resume commands, and routes write-group members through `MoneyflowWritePort`. This is a static observation only; it does not establish that the shared wiring compiles or runs. The standard write group must not race an active D024 run for the same dataset/window.

The dependency on the verified calendar job is `data.exchange_calendar`. No Java build, tests, source request, or QuestDB operation were run for this static correction. `results/D024.json` remains `implemented_not_verified`. D023 serial acceptance remains a prerequisite to D024 live acceptance. Acceptance must include source permission/account sample, first nonempty bootstrap, same-range idempotent rerun, incremental overlap/revision sample, independent 20-field readback, and recovery/unknown-write behavior. Source key removals are deliberately fail-closed rather than tested by deleting rows.
