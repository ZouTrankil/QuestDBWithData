# D026 implementation handoff — `moneyflow_dc`

Implementation status: `implemented_not_verified`.

## D026-owned Java surface

- `TushareMoneyflowDcDto`, `MoneyflowDc`, `MoneyflowDcKey`, `MoneyflowDcDataset`, `MoneyflowDcMapper`
- `MoneyflowDcSource`, `MoneyflowDcTradingDates`, `MoneyflowDcCoverage`, `MoneyflowDcSyncAdapter`, `MoneyflowDcSyncJobOwner`, `MoneyflowDcJobService`
- `MoneyflowDcReadRepository`, `MoneyflowDcWritePort`
- Independent raw-receipt-to-QuestDB value oracle: `artifacts/java-migration/D026/operations/MoneyflowDcIndependentReadback.java`

## Implemented execution semantics

- Only one source call is made for each frozen open SSE/SZSE date. Each request is `moneyflow_dc(trade_date=YYYYMMDD)`, with no pagination parameter. The 6,000-row cap and 16 MiB evidence bound are enforced; a cap-sized response is considered potentially truncated and fails closed.
- Runs use an explicit isolated table name beginning `java_d026_moneyflow_dc_`; the default candidate is `java_d026_moneyflow_dc_acceptance`. Frozen requests include the QuestDB physical target identity. The target definition is YEAR/WAL/DEDUP and the full `(ts_code,trade_date)` key. No production DDL or Flyway migration was added.
- Default job mode is INCREMENTAL. Bootstrap requires an explicit bounded start on an empty target. Subsequent windows overlap the verified checkpoint by two calendar days and are capped at five calendar days. Only verified INCREMENTAL intervals extend checkpoint coverage. A BACKFILL request must stay within an existing incremental checkpoint and contributes newer verified source receipts for overlapping dates; it cannot initialize or advance the checkpoint.
- Before planning/running against an initialized target, target count/range, actual distinct dates, and every row's full-key/full-field values must match the latest applicable same-target raw source receipts. BACKFILL receipts can refresh the per-date value oracle but cannot advance the checkpoint. Source omissions of existing per-date keys fail closed because the Python connector has no deletion/tombstone contract.
- Failed requests cannot be converted to empty results. Receipts retain raw rows and are reopened by SHA-256 before coverage is reused. Writes use 250-row / 1 MiB batches and full-key readback, with an explicit physical target identity check.
- Planning resolves the completed daily end-date ceiling (Asia/Shanghai, 20:30) and clamps later logical/requested-through dates to that completed ceiling.

## Coordinator-owned acceptance remaining

- The current source tree includes the D026 dataset/read repository, owner/job route, CLI plan/run/resume path, and stock/basic write-group route. Root reports targeted `compileJava` succeeded; live behavior is still unverified.
- Bind `${app.sync.moneyflow-dc-table}` to an explicitly isolated table in the acceptance environment. Shared endpoint rate configuration should cap `moneyflow_dc` at the conservative configured 150/minute until current-account permission and grant are checked (Python table config says 150, decorator says 1,500/minute).
- Complete D025 ordering gate and real isolated D026 source/write/readback, same-window rerun, incremental/revision, cancellation/recovery, and unknown-write acceptance. Verify complete 15-column raw receipts independently with `MoneyflowDcIndependentReadback.verify(jdbc, ledgerPath, table, runId)`.

## Checks performed in this handoff

- Read-only inspection of the Python connector, model, storage declaration, and storage-audit snapshot.
- Static source inspection and `git diff --check` on D026-owned changed paths only.
- Root reports targeted `compileJava` passed after shared integration; no tests or live acceptance were run by this handoff.
- No Gradle/build, tests, Tushare requests, QuestDB query, or QuestDB write was run by this implementation handoff.
