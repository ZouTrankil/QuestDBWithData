# D017 source contract review

Review date: 2026-09-30. This is a static source/schema review only; no Tushare request, QuestDB operation, build, or test was run.

## Provider contract

- Official Tushare [document 359](https://tushare.pro/wctapi/documents/359.md) identifies `fund_factor_pro` as daily ETF technical factors, documents request inputs `ts_code`, `start_date`, `end_date`, and `trade_date`, and sets a per-request maximum of 8,000 rows. It does not document `limit` or `offset` inputs.
- The current Python connector `D:/work/fund_2/back-monitor/src/quant_platform/data/adapters/connectors/etf/etf_factor_sync.py` calls `pro.fund_factor_pro(trade_date=trade_date)` once per date from `_get_etf_factor_by_date`, and iterates the D001 trading-date calendar in `sync_etf_factor`. Its decorator says 30 calls per 60 seconds. This is a source-code setting, not proof of the current credential's quota.
- The Java source uses one unpaged `trade_date` request per frozen SSE trade date. It uses the shared Tushare request budget and does not raise the active endpoint quota. It rejects an 8,000-row response as potentially truncated and persists an `unverified_raw_response` receipt before failing. A provider error is never converted to a successful empty date.

## Field and model reconciliation

- The D017 card's physical inventory and Python `ETFFactor.get_questdb_schema()` each contain 89 columns. A static comparison of the card and Java `EtfFactorDataset` found 89 unique physical fields and no field-name differences.
- Python's Pydantic `ETFFactor` declares 49 attributes, while its QuestDB schema enumerates all 89 columns. The real `BaseDataModel.prepare_questdb_dataframe` preserves schema columns that are not explicit model attributes when they are present in the incoming frame. The Java DTO/domain/mapper therefore map the full schema contract rather than treating the partial Pydantic declarations as the source field list.
- Official doc 359 also lists `trade_date_doris`; it is not part of the physical schema and the Python sync explicitly drops it when present. Java requests and stores the exact 89 physical fields and does not map that helper column.
- `ts_code` is preserved verbatim. `trade_date` is parsed as a calendar date and stored as the UTC-midnight TIMESTAMP carrier. The 87 numeric columns are nullable finite DOUBLEs with no scaling: `vol` is in lots (手), `amount` in thousand yuan (千元), and `pct_change` remains in the provider's percent units. Technical names ending/containing `_bfq` retain the documented unadjusted (不复权) meaning and are not recomputed.

## Existing object and DDL boundary

- The task snapshot records physical `trade_date` timestamp, YEAR partitioning, WAL, and DEDUP UPSERT key `(ts_code,trade_date)`. Java's explicit isolated DDL mirrors this identity and schema only for `java_d017_etf_factor_<suffix>` targets.
- The formal `etf_factor` is externally owned. This task adds no Flyway or production-table migration.

## Implementation pointers

- `TushareEtfFactorDto`, `EtfFactor`, `EtfFactorKey`, `EtfFactorDataset`, and `EtfFactorMapper` implement the frozen 89-column mapping.
- `EtfFactorSource` preserves deterministic canonical response evidence, enforces the source cap and date/key checks, and reopens receipts by SHA-256.
- `EtfFactorCoverage` admits only contiguous same-target verified incremental intervals and reconciles the physical date inventory and all values against receipts.
- `EtfFactorJobService` freezes the isolated physical target, bounded request dates, checkpoint anchor/overlap and target date range; it provides plan/run/resume/status/entries/cancel.
- `artifacts/java-migration/operations/EtfFactorIndependentReadback.java` independently validates raw receipt SHA/range/field contract and compares the isolated QuestDB row values across the complete 89-column schema. It does not invoke D017 source, mapper, or write-port code; the coordinator can call its `verify(JdbcTemplate, Path, String, String)` method after an accepted run.

## Not established by this review

No current credential permission, actual source response, live exact-field support, isolated target readback, nonempty write, idempotent rerun, later incremental, or failure/recovery behavior was observed. Those remain coordinator acceptance items.
