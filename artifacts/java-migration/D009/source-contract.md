# D009 source and storage contract

## Selected source contract v2

The Java owner explicitly selects the legacy Tushare `stk_factor` endpoint for this physical contract. It has no automatic fallback to `stk_factor_pro`, and a run never combines rows or columns from both endpoints. This source-contract change increments `data.stk_factor` job version from 1 to 2; the physical schema remains version 1. Failed `stk_factor_pro` v1 runs and receipts cannot advance or seed the v2 checkpoint chain.

## Evidence and field mapping

- Python sync: `D:/work/fund_2/back-monitor/src/quant_platform/data/adapters/connectors/stock/price/stk_factor_sync.py`. Its current caller uses `stk_factor_pro`, iterates `trade_date`, and optionally supplies `ts_code`; a Python unit contract also disallows the legacy endpoint. Java does not silently port that source choice: the current official `stk_factor_pro` docs and exact-code response omit physical `pre_close_hfq`, `pre_close_qfq`, and `kdj_j`, use `pct_chg` rather than physical `pct_change`, and expose indicator values as explicit BFQ/QFQ/HFQ variants. The official legacy [`stk_factor` documentation](https://tushare.pro/document/2?doc_id=296) supports the required fields and routes. A successful exact-code/date probe returned all 35 required fields and an exact 35-column match to the physical row. Evidence: `legacy-complete-probe-20260930.json`, `physical-readback-20260930.json`, and `source-physical-comparison-20260930.json`; the multi-basis `stk_factor_pro` response is retained in `source-basis-probe-20260930.json`.
- The 2026-09-24 sample confirms the unqualified legacy MACD/KDJ/RSI/BOLL/CCI values match the existing physical row. The legacy API documentation says its technical indicators are based on forward-adjusted prices; where `stk_factor_pro` supplied QFQ candidates, those values matched directly or at the legacy output's observed three-decimal precision. The pro response omitted `kdj_j` and both adjusted pre-close fields. The Java mapper uses the observed legacy fields verbatim and makes no guessed adjustment-basis remapping. This is one source/schema comparison, not broad source parity across dates.
- The official [`stk_factor` documentation](https://tushare.pro/document/2?doc_id=296) documents `pct_change`, volume in lots/手, amount in thousands of RMB/千元, and a 10,000-row per-call cap with supported `ts_code`, `trade_date`, `start_date`, and `end_date` parameters. Java preserves these raw values without rescaling. The app's configured 10 calls/minute remains below the documentation's listed request tiers. The saved one-key physical comparison does not replace the remaining multi-row isolated acceptance.
- [Tushare `stk_factor_pro` documentation](https://tushare.pro/document/2?doc_id=328) is retained only as a field-basis comparator; its distinct `pct_chg` and explicit adjustment suffixes are not used by the selected Java source.
- Java source mapping is one-to-one for all 35 selected legacy fields: `pct_change` maps directly to physical `pct_change`; no `pct_chg` rename or BFQ/QFQ/HFQ indicator substitution occurs.
- Physical field list, types, designated timestamp, YEAR partition, WAL, and `DEDUP UPSERT KEYS(ts_code,trade_date)`: `D:/work/fund_2/back-monitor/artifacts/storage-audit-20260929/object-dictionary.md`, `stk_factor` section; task card snapshot cross-check.

The Java dataset covers exactly the audited 35 physical columns (two key columns plus 33 nullable DOUBLE values). Every value is typed as `Double` or nullable `Double`; non-finite input is rejected because QuestDB DOUBLE uses non-finite values as its NULL sentinel.

## Bounds, keys, and time

- Business key and physical UPSERT key: `(ts_code, trade_date)`. `trade_date` is a `LocalDate` calendar day; QuestDB TIMESTAMP uses a UTC-midnight carrier. It is not interpreted as an event instant.
- Full-market route: one `trade_date` request per calendar date. Exact-code route: one `ts_code + start_date + end_date` request for a maximum five-calendar-day window. The official docs list both parameter families and the exact-code date-range route is confirmed by the saved probe. The D007 daily endpoint's 5,557-row observation is a different source and is not used as evidence for this endpoint's result size; D009 all-market acceptance remains required. The adapter never combines both routes in one run.
- The official legacy endpoint cap is 10,000 rows per call and it supports pagination/cycling; Java deliberately uses one bounded, non-paged call per slice. A response at the 10,000-row cap is rejected as possibly truncated, and its raw rows, request parameters, source version, cap, endpoint and source-contract version are retained as unverified evidence. Repeated complete keys and rows outside the frozen scope are rejected.
- The page writer enforces a finite 250-row batch, a 1 MiB application payload ceiling, complete-key uniqueness, ACK/readback separation, and exact all-column readback with WAL-settled checks.
- Shared Tushare request budget defaults to 10 calls/minute in the Java app, below the task card's 25/minute config and the Python connector decorator's 98/minute. The shared credential budget counts retries as separate HTTP attempts.

## Incremental checkpoint and overlap planning

- `INCREMENTAL` is the default mode. Before planning, the owner identifies the exact QuestDB target and reads the actual `trade_date` minimum/maximum for the selected route (all-market or the exact `ts_code`). The target identity is frozen into the request as `parameters.targetId`; planning checks it again after reading the scoped range, execution requires the live identity to match it, and the writer rechecks that same identity around verification. The resolved target interval is returned with the plan for review.
- Checkpoints come only from `VERIFIED` or `VERIFIED_EMPTY` ledger runs for the same target identity, job/schema version, route, and frozen bootstrap anchor. The frozen request also retains `checkpointBefore` and the scoped target min/max observed at planning time. `BACKFILL`, failed, partial, cancelled, and in-doubt runs do not advance coverage. The planner unions only contiguous verified windows beginning at that anchor; a history scan is capped at 10,000 runs.
- The first incremental plan requires an explicit bootstrap start. Its catch-up ceiling is `max(requestedThrough, scoped physical MAX(trade_date))`; the planner rejects either ceiling input after `logicalDate` and caps the resolved window at five calendar days from the explicit bootstrap start. If the ceiling is farther ahead, `Plan.cappedByBudget` is true and the caller can plan the next step after verification. Existing physical rows do not replace the explicit bootstrap anchor and do not establish verified coverage.
- Later plans start two calendar days before the verified checkpoint (`revisionDays=2`, inclusive), and advance by no more than two calendar days per five-day request window. A later actual target maximum is included in that catch-up ceiling. The planner never treats `MAX(trade_date)` alone as verified coverage. Full-market and per-code histories have separate checkpoint chains.
- Checkpoint advancement remains a consequence of the shared runner's complete verified run state. This implementation does not infer a checkpoint from a submitted request, ACK, physical max date, or partial run.

## DDL and target policy

The audited `stk_factor` production object already exists with the declared schema. This task adds no Flyway migration and runs no DDL. If a new isolated QuestDB test object must be created, use this schema only in that explicitly isolated database:

```sql
CREATE TABLE stk_factor (
  ts_code SYMBOL,
  trade_date TIMESTAMP,
  close DOUBLE, open DOUBLE, high DOUBLE, low DOUBLE, pre_close DOUBLE, change DOUBLE, pct_change DOUBLE,
  vol DOUBLE, amount DOUBLE, adj_factor DOUBLE,
  open_hfq DOUBLE, open_qfq DOUBLE, close_hfq DOUBLE, close_qfq DOUBLE,
  high_hfq DOUBLE, high_qfq DOUBLE, low_hfq DOUBLE, low_qfq DOUBLE,
  pre_close_hfq DOUBLE, pre_close_qfq DOUBLE,
  macd_dif DOUBLE, macd_dea DOUBLE, macd DOUBLE,
  kdj_k DOUBLE, kdj_d DOUBLE, kdj_j DOUBLE,
  rsi_6 DOUBLE, rsi_12 DOUBLE, rsi_24 DOUBLE,
  boll_upper DOUBLE, boll_mid DOUBLE, boll_lower DOUBLE, cci DOUBLE
) TIMESTAMP(trade_date) PARTITION BY YEAR WAL
  DEDUP UPSERT KEYS(ts_code, trade_date);
```

The Java writer is deliberately non-creating: preflight requires exact existing columns, types, dedup keys, designated timestamp, YEAR partition, and a settled WAL. It does not repair drift or apply DDL.

## Current limits

This is source/code evidence only. No Tushare request, QuestDB connection, table creation, write, readback, or Gradle command was run by this implementation worker. The checkpoint planner and target-bound execution are implemented but have not been exercised against a real ledger/QuestDB target. The owner is registered in the shared CLI and WriteGroup dispatch; grouped writes use the same configured physical table as the frozen target identity. See the D009 result record.
