# D018 source contract review

Review date: 2026-09-30. This is a static source/schema review only; no Tushare call or QuestDB operation was made.

## Evidence consulted

- Task card: `docs/migration-tasks-20260929/04-etf/D018-etf_portfolio.md`.
- Common contract: `docs/migration-tasks-20260929/00-common-contract.md`.
- Python connector: `D:/work/fund_2/back-monitor/src/quant_platform/data/adapters/connectors/etf/etf_portfolio_sync.py`.
- Python QuestDB model/catalog: `D:/work/fund_2/back-monitor/src/quant_platform/data/adapters/questdb/models/etf/market_data.py`, `src/quant_platform/data/adapters/questdb/catalog_definitions/table_etf.py`, and `src/quant_platform/data/adapters/config/table_definitions/funds.py`.
- Official Tushare endpoint documentation: [公募基金持仓数据（fund_portfolio）](https://tushare.pro/document/2?doc_id=121).
- Read-only F001 snapshot: `artifacts/java-migration/F001/baseline.json`, observed 2026-09-29.

## Source behavior and limits

The Python owner synchronizes each calendar `ann_date` separately. Its `_get_etf_portfolio_by_ann_date` requests `limit=8000` and advances `offset`; the connector has a 200 requests/minute decorator and configured 18:00 daily schedule / 3600-second timeout. Python batches writes across 30 announcement dates and deduplicates by `(ts_code,ann_date,end_date,symbol)`. The Python preprocessing path adds UTC `update_time`; it is not returned by the Tushare API.

The official page confirms `fund_portfolio` and its report/announcement date parameters and eight fields (`ts_code`, `ann_date`, `end_date`, `symbol`, `mkv`, `amount`, `stk_mkv_ratio`, `stk_float_ratio`). The documentation does not define offset/limit pagination semantics or a total row cap. Java follows the observed Python pagination shape but treats it as an implementation-derived contract, now caps one announcement date at 256,000 rows/33 requests, and requires a short terminal page. A cap hit or incomplete paging sequence cannot produce a complete receipt/checkpoint. Missing or blank `ann_date` fails closed; the Python compatibility fallback to `end_date` is not accepted as evidence that a response belongs to the frozen announcement-date slice.

The coordinator performed a source-only provider probe after the initial 64,000-row cap was found too small: 2026-08-27 returned 18,718 rows across pages of 8,000, 8,000, and 2,718; 2026-08-28 returned nine full 8,000-row pages at offsets 0 through 64,000 (72,000 rows captured) before the old 64,000-row policy failed closed, so the complete announcement-date total remains unknown. Probe receipts are under `artifacts/java-migration/D018/provider-diagnostic/00cd6407-a7e9-46ab-b463-c2286a70ef46`. This probe did not write or read QuestDB. The 256,000-row/33-request policy is a finite operational ceiling informed by that observation, not an official provider guarantee; exceeding it still fails closed.

A later D018 incremental source run used the expanded cap and preserved an incomplete receipt after 27 full pages (216,000 rows). Static analysis of all captured rows found 215,998 unique complete keys and two cross-page duplicate keys, both with conflicting `mkv` and `amount`: `(021461.OF, 20260828, 20260630, 601112.SH)` at offsets 48,000 and 208,000; `(161217.SZ, 20260828, 20260630, 601112.SH)` at the same offsets. There were zero identical duplicate keys and zero within-page duplicate keys. Since the repeated keys conflict, there is no defensible provider winner; the full date remains incomplete, and no 2026-08-28 runner chunk was emitted or written. The D018 run stayed PARTIAL at its prior verified through date. Summary: `artifacts/java-migration/D018/source-diagnostic/2026-08-28-conflicting-duplicate-summary.json`; unchanged raw evidence: `artifacts/java-migration/market-live-d1639bce7aaf406c9421d1c393dfe0ef/sync-evidence/etf-portfolio-ef180bc9-0a16-4538-870d-1a265bd8341b/source/incomplete-20260828-d618f394-c403-49f2-b875-9e6addb32bd4.json`.

## Physical snapshot

The F001 read-only snapshot records existing table id 307, directory `etf_portfolio~307`, designated timestamp `end_date`, YEAR partitioning, WAL and DEDUP enabled. It records the complete upsert key `(ts_code,ann_date,end_date,symbol)` and these types: `ts_code SYMBOL`, `ann_date TIMESTAMP`, `end_date TIMESTAMP`, `symbol SYMBOL`, `mkv DOUBLE`, `amount DOUBLE`, `stk_mkv_ratio DOUBLE`, `stk_float_ratio DOUBLE`, `update_time TIMESTAMP`. This snapshot is audit evidence, not permission to mutate the externally owned formal table. Java acceptance must use an isolated `java_d018_etf_portfolio_<suffix>` table.

## Static mapping decisions

| Source/physical field | Java representation |
| --- | --- |
| `ts_code`, `symbol` | Required exchange-qualified strings; complete-key dimensions |
| `ann_date`, `end_date` | `LocalDate`, stored as UTC-midnight calendar carriers; distinct key dimensions; `end_date` designated timestamp |
| `mkv`, `amount`, `stk_mkv_ratio`, `stk_float_ratio` | Nullable finite `Double`, source units and numeric values retained |
| `update_time` | Frozen UTC run observation `Instant` at microsecond precision; derived/non-provider field |

Implementation limitations: RECONCILE is not exposed because the append/upsert writer cannot safely remove revoked source keys. Every processed date is compared as a complete key/value set after writes; extra physical rows therefore fail closed. Coordinator-reported scoped compilation and nonempty 2026-08-27 isolated readback succeeded, but the 2026-08-28 source conflict correctly prevented full incremental acceptance. Separate multi-chunk cancellation/resume acceptance still needs to be run with frozen `observedAt` and independent nine-column verification.
